<#
.SYNOPSIS
    Melokeep 开发模式启动脚本：后端 Go 文件变动后自动停机、重编译并重启，前端使用 Vite HMR 热更新。
#>
[CmdletBinding()]
param(
    [string]$ListenHost = '127.0.0.1',
    [int]$Port = 8765,
    [string]$DataDir = 'data',
    [string]$MusicRoot = 'melokeep_outputs',
    [switch]$BackendOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Get-Command go -ErrorAction SilentlyContinue) -and (Test-Path 'C:\Go\bin\go.exe')) {
    $env:PATH = "C:\Go\bin;$env:PATH"
}

$repoRoot = $PSScriptRoot
$goDir = Join-Path $repoRoot 'go'
$frontendDir = Join-Path $repoRoot 'frontend'
$binDir = Join-Path $repoRoot '.dev-bin'
$exePath = Join-Path $binDir 'melokeep.exe'

function Resolve-RepoPath([string]$RawPath) {
    if ([System.IO.Path]::IsPathRooted($RawPath)) {
        return $RawPath
    }
    return Join-Path $repoRoot $RawPath
}

$resolvedDataDir = Resolve-RepoPath $DataDir
$resolvedMusicRoot = Resolve-RepoPath $MusicRoot

New-Item -ItemType Directory -Force -Path $binDir | Out-Null
New-Item -ItemType Directory -Force -Path $resolvedDataDir | Out-Null
New-Item -ItemType Directory -Force -Path $resolvedMusicRoot | Out-Null

function Stop-ProcessSafe([System.Diagnostics.Process]$Process) {
    if ($null -eq $Process) {
        return
    }
    try {
        if (-not $Process.HasExited) {
            $Process.Kill($true)
            $null = $Process.WaitForExit(3000)
        }
    } catch {
        # 进程可能已自行退出
    } finally {
        $Process.Dispose()
    }
}

function Build-Backend {
    Write-Host "[dev] 正在编译 Go 后端..." -ForegroundColor Cyan
    Push-Location $goDir
    try {
        & go build -o $exePath .\cmd\melokeep
        return ($LASTEXITCODE -eq 0)
    } finally {
        Pop-Location
    }
}

function Get-LanIPv4Addresses {
    $virtualPattern = '(?i)(vEthernet|Hyper-V|WSL|Docker|VMware|VirtualBox|Loopback|ZeroTier|Tailscale|Clash|Mihomo|sing-box|WireGuard|TAP|TUN)'
    $candidates = @()
    try {
        $defaultRoutes = @(Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue |
            Sort-Object -Property RouteMetric, InterfaceMetric)
        $preferredIfaces = @($defaultRoutes | Select-Object -ExpandProperty InterfaceIndex -Unique)

        $adapters = @{}
        foreach ($ad in @(Get-NetAdapter -ErrorAction SilentlyContinue)) {
            $adapters[[int]$ad.ifIndex] = $ad
        }

        $ips = @(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object {
            $_.IPAddress -and
            $_.IPAddress -ne '127.0.0.1' -and
            -not $_.IPAddress.StartsWith('169.254.') -and
            $_.AddressState -eq 'Preferred'
        })

        $scored = foreach ($ip in $ips) {
            $ifIdx = [int]$ip.InterfaceIndex
            $alias = [string]$ip.InterfaceAlias
            $ad = if ($adapters.ContainsKey($ifIdx)) { $adapters[$ifIdx] } else { $null }
            $desc = if ($null -ne $ad) { [string]$ad.InterfaceDescription } else { '' }
            $up = if ($null -ne $ad) { $ad.Status -eq 'Up' } else { $true }
            if (-not $up) { continue }

            $isVirtual = ($alias -match $virtualPattern) -or ($desc -match $virtualPattern)
            $isDefaultRoute = $preferredIfaces -contains $ifIdx
            $isPrivateLan = ($ip.IPAddress -match '^(192\.168\.|10\.|172\.(1[6-9]|2\d|3[0-1])\.)')
            $score = 0
            if ($isDefaultRoute) { $score += 100 }
            if (-not $isVirtual) { $score += 50 }
            if ($isPrivateLan) { $score += 20 }
            if ($ip.IPAddress.StartsWith('192.168.')) { $score += 10 }
            [PSCustomObject]@{
                IPAddress = $ip.IPAddress
                Score     = $score
                IsVirtual = $isVirtual
            }
        }
        $candidates = @($scored |
            Where-Object { -not $_.IsVirtual -or $_.Score -ge 100 } |
            Sort-Object -Property Score -Descending |
            Select-Object -ExpandProperty IPAddress -Unique)
    } catch {
        $candidates = @()
    }

    if ($candidates.Count -eq 0) {
        try {
            $fallback = @()
            $nets = @([System.Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces() | Where-Object {
                $_.OperationalStatus -eq [System.Net.NetworkInformation.OperationalStatus]::Up -and
                $_.NetworkInterfaceType -ne [System.Net.NetworkInformation.NetworkInterfaceType]::Loopback
            })
            foreach ($ni in $nets) {
                if ($ni.Name -match $virtualPattern -or $ni.Description -match $virtualPattern) {
                    continue
                }
                foreach ($ua in $ni.GetIPProperties().UnicastAddresses) {
                    if ($ua.Address.AddressFamily -eq [System.Net.Sockets.AddressFamily]::InterNetwork) {
                        $addr = $ua.Address.ToString()
                        if ($addr -ne '127.0.0.1' -and -not $addr.StartsWith('169.254.')) {
                            $fallback += $addr
                        }
                    }
                }
            }
            $candidates = @($fallback | Select-Object -Unique)
        } catch {
            $candidates = @()
        }
    }
    return $candidates
}

function Show-LanUrls {
    if ($ListenHost -ne '0.0.0.0') {
        return
    }
    Write-Host "[dev] 本机访问地址: http://127.0.0.1:${Port}" -ForegroundColor Green
    $lanIPs = @(Get-LanIPv4Addresses)
    if ($lanIPs.Count -gt 0) {
        Write-Host "============================================================" -ForegroundColor Cyan
        Write-Host "[dev] 手机/局域网连接地址 (LAN URL):" -ForegroundColor Cyan
        foreach ($ip in $lanIPs) {
            Write-Host "      -> http://${ip}:${Port}" -ForegroundColor Yellow
        }
        Write-Host "============================================================" -ForegroundColor Cyan
    } else {
        Write-Host "[dev] 提示: 已监听 0.0.0.0:${Port}，但未自动检测到活跃局域网 IPv4 地址，请检查网络适配器。" -ForegroundColor DarkYellow
    }
}

function Start-Backend {
    $backendArgs = @(
        '--host', $ListenHost,
        '--port', "$Port",
        '--data-dir', $resolvedDataDir,
        '--music-root', $resolvedMusicRoot
    )
    Write-Host "[dev] 启动后端: http://${ListenHost}:${Port}" -ForegroundColor Green
    Show-LanUrls
    return Start-Process -FilePath $exePath -ArgumentList $backendArgs -WorkingDirectory $repoRoot -NoNewWindow -PassThru
}

function Start-Frontend {
    Write-Host "[dev] 启动前端 Vite HMR 开发服务器..." -ForegroundColor Green
    return Start-Process -FilePath 'npm.cmd' -ArgumentList @('run', 'dev', '--prefix', $frontendDir) -WorkingDirectory $repoRoot -NoNewWindow -PassThru
}

$backendProc = $null
$frontendProc = $null
$watcher = $null
$subscriptions = @()

try {
    if (-not (Build-Backend)) {
        throw "首次 Go 编译失败，请先修复编译错误。"
    }
    $backendProc = Start-Backend
    if (-not $BackendOnly) {
        $frontendProc = Start-Frontend
    }

    $watcher = [System.IO.FileSystemWatcher]::new($goDir)
    $watcher.IncludeSubdirectories = $true
    $watcher.NotifyFilter = [System.IO.NotifyFilters]'FileName, LastWrite, DirectoryName'
    $watcher.EnableRaisingEvents = $true

    $syncState = [hashtable]::Synchronized(@{
        Dirty       = $false
        LastChanged = [datetime]::MinValue
        LastPath    = ''
    })

    $onFsEvent = {
        $fullPath = $EventArgs.FullPath
        if (-not [string]::IsNullOrWhiteSpace($fullPath)) {
            $ext = [System.IO.Path]::GetExtension($fullPath).ToLowerInvariant()
            $name = [System.IO.Path]::GetFileName($fullPath).ToLowerInvariant()
            if (($ext -eq '.go') -or ($ext -eq '.sql') -or ($name -eq 'go.mod') -or ($name -eq 'go.sum')) {
                $state = $Event.MessageData
                $state.Dirty = $true
                $state.LastChanged = [datetime]::UtcNow
                $state.LastPath = $fullPath
            }
        }
    }

    foreach ($evtName in 'Changed', 'Created', 'Deleted', 'Renamed') {
        $subscriptions += Register-ObjectEvent -InputObject $watcher -EventName $evtName -Action $onFsEvent -MessageData $syncState
    }

    Write-Host "[dev] 监听 go\ 源码变更中（修改后自动编译并重启后端），按 Ctrl+C 退出。" -ForegroundColor Yellow

    while ($true) {
        Start-Sleep -Milliseconds 250
        if ($syncState.Dirty -and (([datetime]::UtcNow - $syncState.LastChanged).TotalMilliseconds -ge 500)) {
            $changedPath = $syncState.LastPath
            $syncState.Dirty = $false
            Write-Host "[dev] 检测到变更: $changedPath" -ForegroundColor Yellow
            Stop-ProcessSafe $backendProc
            $backendProc = $null
            if (Build-Backend) {
                $backendProc = Start-Backend
            } else {
                Write-Host "[dev] 编译失败，保持等待下一次文件保存后自动重试..." -ForegroundColor Red
            }
        }
    }
} finally {
    Write-Host "`n[dev] 正在停止开发环境进程..." -ForegroundColor Yellow
    foreach ($sub in $subscriptions) {
        Unregister-Event -SubscriptionId $sub.Id -ErrorAction SilentlyContinue
        Remove-Job -Id $sub.Id -Force -ErrorAction SilentlyContinue
    }
    if ($null -ne $watcher) {
        $watcher.EnableRaisingEvents = $false
        $watcher.Dispose()
    }
    Stop-ProcessSafe $backendProc
    Stop-ProcessSafe $frontendProc
}
