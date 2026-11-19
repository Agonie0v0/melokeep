<#
.SYNOPSIS
    MeloKeep 纯原生移动端开发联调脚本（KMP + Navic + Jetpack Compose）：
    1) 自动检测/初始化便携 OpenJDK 21 与 Android SDK 37.2 工具链（.dev-bin/android-toolchain）
    2) 启动 Go 后端热编译监听（dev.ps1 -ListenHost 0.0.0.0 -BackendOnly）
    3) 可选 -InstallApk：编译 Debug APK、更新 melokeep_outputs/MeloKeep-android-debug.apk、adb 安装并强制重启
    4) 可选 -WatchAndroid：监听 Android/shared Kotlin 与资源改动，自动增量重编并 adb install -r + am start -S 热重启
    5) 可选 -Release：编译开启 R8 优化与资源压缩的 Release APK，完成 zipalign 对齐与本地测试签名输出至 melokeep_outputs/MeloKeep-android-release-local.apk（配合 -InstallApk 可直接安装并热重启）
#>
[CmdletBinding()]
param(
    [string]$ListenHost = '0.0.0.0',
    [string]$LanIp = '',
    [int]$BackendPort = 8765,
    [switch]$InstallApk,
    [switch]$WatchAndroid,
    [switch]$UseAdbReverse,
    [switch]$SkipBackend,
    [switch]$Offline,
    [switch]$Release
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = $PSScriptRoot
$mobileDir = Join-Path $repoRoot 'mobile'
$toolchainRoot = Join-Path $repoRoot '.dev-bin\android-toolchain'
$jdkDir = Join-Path $toolchainRoot 'jdk-21'
$sdkDir = Join-Path $toolchainRoot 'android-sdk'
$setupScript = Join-Path $mobileDir 'scripts\setup-android-toolchain.ps1'
$devScript = Join-Path $repoRoot 'dev.ps1'
$outputsDir = Join-Path $repoRoot 'melokeep_outputs'
$outputApk = Join-Path $outputsDir 'MeloKeep-android-debug.apk'
$releaseOutputApk = Join-Path $outputsDir 'MeloKeep-android-release-local.apk'
$fallbackReleaseKeystore = Join-Path $outputsDir '.local-release-debug.keystore'
$debugComponent = 'io.github.agonie0v0.melokeep.debug/paige.navic.MainActivity'
$releaseComponent = 'io.github.agonie0v0.melokeep/paige.navic.MainActivity'

function Get-PrimaryLanIPv4 {
    try {
        $candidates = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop |
            Where-Object {
                $_.IPAddress -notlike '127.*' -and
                $_.IPAddress -notlike '169.254.*' -and
                $_.PrefixOrigin -ne 'WellKnown' -and
                $_.InterfaceAlias -notmatch 'vEthernet|WSL|Loopback|Docker|VirtualBox|VMware|Hyper-V'
            } |
            Sort-Object -Property InterfaceMetric
        if ($candidates -and $candidates.Count -gt 0) {
            return $candidates[0].IPAddress
        }
    } catch {
        # 回退到套接字探测
    }

    try {
        $sock = [System.Net.Sockets.UdpClient]::new('8.8.8.8', 80)
        try {
            return $sock.Client.LocalEndPoint.Address.IPAddressToString
        } finally {
            $sock.Dispose()
        }
    } catch {
        return '127.0.0.1'
    }
}

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
        # 进程可能已退出
    } finally {
        $Process.Dispose()
    }
}

function Ensure-AndroidToolchain {
    $hasJdk = Test-Path (Join-Path $jdkDir 'bin\java.exe')
    $hasSdk = (Test-Path (Join-Path $sdkDir 'platforms\android-37.2\android.jar')) -and
        (Test-Path (Join-Path $sdkDir 'build-tools\37.0.0\aapt2.exe'))
    if (-not $hasJdk -or -not $hasSdk) {
        Write-Host '[mobile-dev] 检测到便携 Android 工具链不完整，正在自动初始化...' -ForegroundColor Yellow
        & $setupScript -ToolchainRoot $toolchainRoot
    }
    $env:JAVA_HOME = $jdkDir
    $env:ANDROID_HOME = $sdkDir
    $env:ANDROID_SDK_ROOT = $sdkDir
    $env:PATH = "$(Join-Path $jdkDir 'bin');$(Join-Path $sdkDir 'platform-tools');$env:PATH"
}

function Get-GradleProxyArgs {
    $argsList = [System.Collections.Generic.List[string]]::new()
    if ($env:HTTPS_PROXY -match '^https?://([^:]+):(\d+)') {
        $argsList.Add("-Dhttps.proxyHost=$($Matches[1])")
        $argsList.Add("-Dhttps.proxyPort=$($Matches[2])")
    }
    if ($env:HTTP_PROXY -match '^https?://([^:]+):(\d+)') {
        $argsList.Add("-Dhttp.proxyHost=$($Matches[1])")
        $argsList.Add("-Dhttp.proxyPort=$($Matches[2])")
    }
    if ($Offline) {
        $argsList.Add('--offline')
    }
    return $argsList.ToArray()
}

function Find-AndroidBuildTool([string]$ToolName) {
    $buildToolsRoot = Join-Path $sdkDir 'build-tools'
    if (-not (Test-Path $buildToolsRoot)) {
        throw "未找到 Android SDK build-tools 目录: $buildToolsRoot"
    }
    $candidate = Get-ChildItem -Path $buildToolsRoot -Directory -ErrorAction SilentlyContinue |
        Sort-Object -Descending -Property {
            $parsedVersion = $null
            $cleanName = $_.Name -replace '[^0-9.].*$', ''
            [System.Version]::TryParse($cleanName, [ref]$parsedVersion) ? $parsedVersion : [System.Version]::new(0, 0)
        } |
        ForEach-Object { Join-Path $_.FullName $ToolName } |
        Where-Object { Test-Path $_ } |
        Select-Object -First 1

    if ([string]::IsNullOrWhiteSpace($candidate)) {
        throw "在 $buildToolsRoot 中未找到工具: $ToolName"
    }
    return $candidate
}

function Resolve-LocalReleaseKeystore {
    $userDebugKeystore = Join-Path $env:USERPROFILE '.android\debug.keystore'
    if (Test-Path $userDebugKeystore) {
        return $userDebugKeystore
    }

    if (Test-Path $fallbackReleaseKeystore) {
        return $fallbackReleaseKeystore
    }

    $keytoolExe = Join-Path $jdkDir 'bin\keytool.exe'
    if (-not (Test-Path $keytoolExe)) {
        throw "未找到 keytool.exe，无法自动生成本地签名 keystore: $keytoolExe"
    }

    New-Item -ItemType Directory -Force -Path $outputsDir | Out-Null
    Write-Host "[mobile-dev] 未找到 $userDebugKeystore，正在自动生成本地测试 keystore: $fallbackReleaseKeystore ..." -ForegroundColor Yellow
    & $keytoolExe -genkeypair `
        -keystore $fallbackReleaseKeystore `
        -storetype PKCS12 `
        -storepass android `
        -alias androiddebugkey `
        -keypass android `
        -keyalg RSA `
        -keysize 2048 `
        -validity 10000 `
        -dname 'CN=Android Debug,O=Android,C=US'
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $fallbackReleaseKeystore)) {
        throw "keytool 生成本地测试 keystore 失败 ($LASTEXITCODE)"
    }
    return $fallbackReleaseKeystore
}

function Invoke-AdbInstallAndRestart(
    [string]$ApkPath,
    [string]$Component,
    [string]$BuildLabel
) {
    $adbExe = Join-Path $sdkDir 'platform-tools\adb.exe'
    if (-not (Test-Path $adbExe)) {
        return
    }
    $devicesOutput = & $adbExe devices
    $onlineDevices = $devicesOutput | Select-String -Pattern '\tdevice$'
    if ($onlineDevices) {
        Write-Host "[mobile-dev] 检测到在线 ADB 设备，正在覆盖安装并热重启 $BuildLabel App ($Component)..." -ForegroundColor Cyan
        & $adbExe install -r $ApkPath
        if ($LASTEXITCODE -ne 0) {
            throw "adb install 失败 ($LASTEXITCODE)。若设备已安装不同签名的同包名应用，请先卸载后重试。"
        }
        if ($UseAdbReverse) {
            & $adbExe reverse "tcp:$BackendPort" "tcp:$BackendPort" | Out-Null
        }
        & $adbExe shell am start -S -n $Component
        if ($LASTEXITCODE -ne 0) {
            throw "adb am start 失败 ($LASTEXITCODE)"
        }
    } else {
        Write-Host '[mobile-dev] 未检测到在线 ADB 设备，已跳过自动安装与重启（可手动安装上述 APK）。' -ForegroundColor Yellow
    }
}

function Invoke-BuildAndDeployApk([bool]$DeployToDevice = $true) {
    Ensure-AndroidToolchain
    $gradleTask = $Release ? ':app:assembleRelease' : ':app:assembleDebug'
    $gradleArgs = @($gradleTask, '--no-daemon', '--console=plain') + (Get-GradleProxyArgs)

    if ($Release) {
        Write-Host '[mobile-dev] 正在编译 Android Release APK (R8 代码优化 + 资源压缩: :app:assembleRelease)...' -ForegroundColor Cyan
    } else {
        Write-Host '[mobile-dev] 正在编译 Android Debug APK (:app:assembleDebug)...' -ForegroundColor Cyan
    }

    Push-Location $mobileDir
    try {
        & .\gradlew.bat @gradleArgs
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle 编译失败 ($LASTEXITCODE)"
        }
    } finally {
        Pop-Location
    }

    New-Item -ItemType Directory -Force -Path $outputsDir | Out-Null

    if ($Release) {
        $releaseOutDir = Join-Path $mobileDir 'app\build\outputs\apk\release'
        $builtApkItem = Get-ChildItem -Path $releaseOutDir -Filter '*.apk' -Recurse -ErrorAction SilentlyContinue |
            Sort-Object -Property LastWriteTimeUtc -Descending |
            Select-Object -First 1
        if ($null -eq $builtApkItem) {
            throw "未找到 Android Release APK 编译产物 ($releaseOutDir)"
        }

        $zipalignExe = Find-AndroidBuildTool 'zipalign.exe'
        $apksignerBat = Find-AndroidBuildTool 'apksigner.bat'
        $keystorePath = Resolve-LocalReleaseKeystore
        $alignedApk = Join-Path $outputsDir '.MeloKeep-android-release-aligned.tmp.apk'

        Write-Host "[mobile-dev] 正在对 Release APK 执行 zipalign 对齐与本地签名 ($keystorePath)..." -ForegroundColor Cyan
        try {
            & $zipalignExe -f -P 16 4 $builtApkItem.FullName $alignedApk
            if ($LASTEXITCODE -ne 0) {
                Write-Host '[mobile-dev] zipalign -P 16 不可用，回退至 4KB 页对齐 (-p)...' -ForegroundColor Yellow
                & $zipalignExe -f -p 4 $builtApkItem.FullName $alignedApk
                if ($LASTEXITCODE -ne 0) {
                    throw "zipalign 对齐失败 ($LASTEXITCODE)"
                }
            }

            & $apksignerBat sign `
                --ks $keystorePath `
                --ks-key-alias androiddebugkey `
                --ks-pass pass:android `
                --key-pass pass:android `
                --v4-signing-enabled false `
                --out $releaseOutputApk `
                $alignedApk
            if ($LASTEXITCODE -ne 0) {
                throw "apksigner 签名失败 ($LASTEXITCODE)"
            }

            & $apksignerBat verify $releaseOutputApk
            if ($LASTEXITCODE -ne 0) {
                throw "apksigner 签名校验失败 ($LASTEXITCODE)"
            }
        } finally {
            Remove-Item -Force -ErrorAction SilentlyContinue $alignedApk, "$releaseOutputApk.idsig"
        }

        Write-Host "[mobile-dev] 已输出可直装本地签名 Release APK: $releaseOutputApk ($([math]::Round((Get-Item $releaseOutputApk).Length / 1MB, 2)) MB)" -ForegroundColor Green

        if ($DeployToDevice) {
            Invoke-AdbInstallAndRestart -ApkPath $releaseOutputApk -Component $releaseComponent -BuildLabel 'Release'
        } else {
            Write-Host '[mobile-dev] 已完成本地签名 Release APK 构建（如需自动安装并热重启请附加 -InstallApk）。' -ForegroundColor Gray
        }
        return
    }

    $builtApkItem = Get-ChildItem -Path (Join-Path $mobileDir 'app\build\outputs\apk\debug') -Filter '*.apk' -Recurse -ErrorAction SilentlyContinue |
        Sort-Object -Property LastWriteTimeUtc -Descending |
        Select-Object -First 1
    if ($null -eq $builtApkItem) {
        throw '未找到 Android Debug APK 编译产物'
    }

    Copy-Item -Force $builtApkItem.FullName $outputApk
    Write-Host "[mobile-dev] 已输出 APK: $outputApk ($([math]::Round((Get-Item $outputApk).Length / 1MB, 2)) MB)" -ForegroundColor Green

    if ($DeployToDevice) {
        Invoke-AdbInstallAndRestart -ApkPath $outputApk -Component $debugComponent -BuildLabel 'Debug'
    }
}

function Get-MobileSourceFingerprint {
    $watchPaths = @(
        (Join-Path $mobileDir 'app\src'),
        (Join-Path $mobileDir 'shared\src'),
        (Join-Path $mobileDir 'app\build.gradle.kts'),
        (Join-Path $mobileDir 'shared\build.gradle.kts'),
        (Join-Path $mobileDir 'gradle\libs.versions.toml')
    )
    $maxTicks = 0L
    $fileCount = 0
    foreach ($p in $watchPaths) {
        if (-not (Test-Path $p)) {
            continue
        }
        if (Test-Path $p -PathType Leaf) {
            $item = Get-Item $p
            $fileCount++
            if ($item.LastWriteTimeUtc.Ticks -gt $maxTicks) {
                $maxTicks = $item.LastWriteTimeUtc.Ticks
            }
            continue
        }
        $files = Get-ChildItem -Path $p -Recurse -File -Include '*.kt', '*.kts', '*.xml', '*.json' -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -notmatch '\\(build|\.gradle|\.kotlin)\\' }
        foreach ($f in $files) {
            $fileCount++
            if ($f.LastWriteTimeUtc.Ticks -gt $maxTicks) {
                $maxTicks = $f.LastWriteTimeUtc.Ticks
            }
        }
    }
    return "${fileCount}:${maxTicks}"
}

$targetHost = if ($UseAdbReverse) {
    '127.0.0.1'
} elseif (-not [string]::IsNullOrWhiteSpace($LanIp)) {
    $LanIp.Trim()
} else {
    Get-PrimaryLanIPv4
}

$backendLanUrl = "http://${targetHost}:${BackendPort}"

$buildModeLabel = $Release ? 'Release (R8 优化 + 本地测试签名)' : 'Debug (开发调试)'

Write-Host '======================================================' -ForegroundColor Cyan
Write-Host ' MeloKeep 纯原生移动端联调 (Navic + KMP)' -ForegroundColor Cyan
Write-Host "  - 构建模式         : $buildModeLabel" -ForegroundColor ($Release ? 'Magenta' : 'Cyan')
Write-Host "  - 后端服务建议地址 : $backendLanUrl" -ForegroundColor Green
if ($UseAdbReverse) {
    Write-Host "  - 传输模式         : USB ADB Reverse (127.0.0.1:$BackendPort)" -ForegroundColor Yellow
} else {
    Write-Host "  - 传输模式         : 局域网 Wi-Fi ($targetHost)" -ForegroundColor Yellow
}
Write-Host '  - 提示             : 请在 App「设置 -> MeloKeep 服务器」填入上述地址' -ForegroundColor Gray
Write-Host '======================================================' -ForegroundColor Cyan

if ($Release -or $InstallApk -or $WatchAndroid -or $UseAdbReverse) {
    Ensure-AndroidToolchain
}

if ($UseAdbReverse) {
    $adb = Get-Command adb -ErrorAction SilentlyContinue
    if ($null -ne $adb) {
        Write-Host "[mobile-dev] 配置 adb reverse tcp:$BackendPort ..." -ForegroundColor Cyan
        & adb reverse "tcp:$BackendPort" "tcp:$BackendPort" | Out-Null
    }
}

$backendProc = $null
$pwshExe = (Get-Process -Id $PID).Path

try {
    if ($Release -or $InstallApk -or $WatchAndroid) {
        $shouldDeploy = $InstallApk.IsPresent -or $WatchAndroid.IsPresent
        Invoke-BuildAndDeployApk -DeployToDevice $shouldDeploy
    }

    if (-not $SkipBackend) {
        Write-Host "[mobile-dev] 启动 Go 后端热重载守护 (dev.ps1 -ListenHost $ListenHost -Port $BackendPort -BackendOnly)..." -ForegroundColor Green
        $backendProc = Start-Process -FilePath $pwshExe `
            -ArgumentList @('-NoLogo', '-File', $devScript, '-ListenHost', $ListenHost, '-Port', "$BackendPort", '-BackendOnly') `
            -WorkingDirectory $repoRoot -NoNewWindow -PassThru
    }

    if (-not $WatchAndroid -and $SkipBackend) {
        Write-Host '[mobile-dev] 单次构建/部署任务完成。' -ForegroundColor Green
        return
    }

    $lastFingerprint = Get-MobileSourceFingerprint
    if ($WatchAndroid) {
        Write-Host '[mobile-dev] 已开启 Android 原生源码热重启监听（修改 .kt/.kts/.xml 后自动增量重编、adb install -r 与 am start -S）。按 Ctrl+C 退出。' -ForegroundColor Yellow
    } else {
        Write-Host '[mobile-dev] Go 后端热重载已启动（可加 -WatchAndroid 开启客户端修改自动重编重启）。按 Ctrl+C 退出。' -ForegroundColor Yellow
    }

    while ($true) {
        Start-Sleep -Milliseconds 800
        if ($null -ne $backendProc -and $backendProc.HasExited) {
            Write-Host "[mobile-dev] 后端进程意外退出 (ExitCode=$($backendProc.ExitCode))。" -ForegroundColor Red
            break
        }
        if ($WatchAndroid) {
            $currentFingerprint = Get-MobileSourceFingerprint
            if ($currentFingerprint -ne $lastFingerprint) {
                Start-Sleep -Milliseconds 1200
                $stableFingerprint = Get-MobileSourceFingerprint
                while ($stableFingerprint -ne $currentFingerprint) {
                    $currentFingerprint = $stableFingerprint
                    Start-Sleep -Milliseconds 1000
                    $stableFingerprint = Get-MobileSourceFingerprint
                }
                Write-Host "`n[mobile-dev] 检测到 mobile 源码变更，触发增量构建与热重启..." -ForegroundColor Yellow
                try {
                    Invoke-BuildAndDeployApk
                } catch {
                    Write-Host "[mobile-dev] 增量重编或安装失败：$($_.Exception.Message)" -ForegroundColor Red
                }
                $lastFingerprint = Get-MobileSourceFingerprint
            }
        }
    }
} finally {
    if (-not $SkipBackend -or $WatchAndroid) {
        Write-Host "`n[mobile-dev] 正在停止联调进程..." -ForegroundColor Yellow
    }
    Stop-ProcessSafe $backendProc
}
