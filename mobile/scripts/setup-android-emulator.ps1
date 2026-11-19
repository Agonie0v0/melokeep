<#
.SYNOPSIS
    在便携工具链 (.dev-bin/android-toolchain/) 中幂等安装 Android Emulator 与 Android 34 x86_64 镜像，
    并在隔离目录 (.dev-bin/android-toolchain/avd) 创建名为 melokeep_studio 的旗舰竖屏 AVD (1080x2400 / 420dpi)。
#>
[CmdletBinding()]
param(
    [string]$ToolchainRoot = '',
    [string]$AvdName = 'melokeep_studio',
    [string]$SystemImagePackage = 'system-images;android-34;default;x86_64',
    [string]$PlatformPackage = 'platforms;android-34',
    [ValidateSet('auto', 'host', 'swiftshader_indirect')]
    [string]$GpuMode = 'auto',
    [switch]$ForceRecreate,
    [switch]$SkipStart,
    [switch]$Headless,
    [switch]$WaitBoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$effectiveGpuMode = if ($Headless -and -not $PSBoundParameters.ContainsKey('GpuMode')) {
    'swiftshader_indirect'
} else {
    $GpuMode
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if ([string]::IsNullOrWhiteSpace($ToolchainRoot)) {
    $ToolchainRoot = Join-Path $repoRoot '.dev-bin\android-toolchain'
}

$jdkDir = Join-Path $ToolchainRoot 'jdk-21'
$sdkDir = Join-Path $ToolchainRoot 'android-sdk'
$avdHome = Join-Path $ToolchainRoot 'avd'
$cmdlineBin = Join-Path $sdkDir 'cmdline-tools\latest\bin'
$sdkManagerBat = Join-Path $cmdlineBin 'sdkmanager.bat'
$avdManagerBat = Join-Path $cmdlineBin 'avdmanager.bat'
$emulatorExe = Join-Path $sdkDir 'emulator\emulator.exe'
$adbExe = Join-Path $sdkDir 'platform-tools\adb.exe'

if (-not (Test-Path (Join-Path $jdkDir 'bin\java.exe')) -or -not (Test-Path $sdkManagerBat)) {
    $toolchainScript = Join-Path $PSScriptRoot 'setup-android-toolchain.ps1'
    if (Test-Path $toolchainScript) {
        Write-Host '[emulator] 基础 JDK/SDK 尚未就绪，先执行 setup-android-toolchain.ps1...' -ForegroundColor Cyan
        & $toolchainScript -ToolchainRoot $ToolchainRoot
    } else {
        throw "未找到便携 JDK 21 或 Android SDK: $ToolchainRoot"
    }
}

New-Item -ItemType Directory -Force -Path $avdHome | Out-Null

$env:JAVA_HOME = $jdkDir
$env:ANDROID_HOME = $sdkDir
$env:ANDROID_SDK_ROOT = $sdkDir
$env:ANDROID_AVD_HOME = $avdHome
$env:PATH = "$(Join-Path $jdkDir 'bin');$(Join-Path $sdkDir 'platform-tools');$(Join-Path $sdkDir 'emulator');$env:PATH"

$proxyArgs = @()
$proxyRaw = if (-not [string]::IsNullOrWhiteSpace($env:HTTPS_PROXY)) {
    $env:HTTPS_PROXY
} elseif (-not [string]::IsNullOrWhiteSpace($env:HTTP_PROXY)) {
    $env:HTTP_PROXY
} else {
    ''
}
if (-not [string]::IsNullOrWhiteSpace($proxyRaw)) {
    try {
        $proxyUri = [Uri]$proxyRaw
        if ($proxyUri.Host -and $proxyUri.Port -gt 0) {
            $proxyArgs = @('--proxy=http', "--proxy_host=$($proxyUri.Host)", "--proxy_port=$($proxyUri.Port)")
            Write-Host "[emulator] 使用代理下载 SDK 组件: $($proxyUri.Host):$($proxyUri.Port)" -ForegroundColor DarkCyan
        }
    } catch {
        Write-Warning "[emulator] 忽略无法解析的代理地址: $proxyRaw"
    }
}

$imgRelative = ($SystemImagePackage -split ';') -join '\'
$platformRelative = ($PlatformPackage -split ';') -join '\'
$requiredPackages = @(
    @{ Name = 'emulator'; CheckPath = $emulatorExe },
    @{ Name = $PlatformPackage; CheckPath = (Join-Path $sdkDir "$platformRelative\android.jar") },
    @{ Name = $SystemImagePackage; CheckPath = (Join-Path $sdkDir "$imgRelative\system.img") }
)

$missingPackages = @($requiredPackages | Where-Object { -not (Test-Path $_.CheckPath) } | ForEach-Object { $_.Name })
if ($missingPackages.Count -gt 0) {
    Write-Host '[emulator] 自动确认 Android SDK 许可...' -ForegroundColor Cyan
    1..50 | ForEach-Object { 'y' } | & $sdkManagerBat "--sdk_root=$sdkDir" @proxyArgs --licenses | Out-Null

    Write-Host "[emulator] 安装组件: $($missingPackages -join ', ')" -ForegroundColor Cyan
    1..50 | ForEach-Object { 'y' } | & $sdkManagerBat "--sdk_root=$sdkDir" @proxyArgs @missingPackages
    if ($LASTEXITCODE -ne 0) {
        throw "sdkmanager 安装模拟器组件失败 ($LASTEXITCODE)"
    }
}

Write-Host '[emulator] 检查硬件加速状态 (-accel-check)...' -ForegroundColor Cyan
$accelOutput = & $emulatorExe -accel-check 2>&1
$accelExit = $LASTEXITCODE
$accelText = ($accelOutput | Out-String).Trim()
Write-Host $accelText
if ($accelExit -ne 0) {
    throw "硬件加速不可用 (exit=$accelExit)。请确保已启用 WHPX / Intel HAXM / AMD SVM。"
}

function Set-IniKeyValues {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][hashtable]$Entries
    )
    $lines = [System.Collections.Generic.List[string]]::new()
    if (Test-Path $FilePath) {
        foreach ($rawLine in (Get-Content -Path $FilePath -Encoding UTF8)) {
            $lines.Add([string]$rawLine)
        }
    }

    foreach ($key in $Entries.Keys) {
        $value = $Entries[$key]
        $pattern = "^\s*" + [regex]::Escape($key) + "\s*="
        $updated = $false
        for ($i = 0; $i -lt $lines.Count; $i++) {
            if ($lines[$i] -match $pattern) {
                $lines[$i] = "$key=$value"
                $updated = $true
                break
            }
        }
        if (-not $updated) {
            $lines.Add("$key=$value")
        }
    }

    Set-Content -Path $FilePath -Value $lines -Encoding utf8NoBOM
}

$avdDir = Join-Path $avdHome "$AvdName.avd"
$avdConfigIni = Join-Path $avdDir 'config.ini'

if ($ForceRecreate -or -not (Test-Path $avdConfigIni)) {
    Write-Host "[emulator] 创建 AVD '$AvdName' ($SystemImagePackage)..." -ForegroundColor Cyan
    'no' | & $avdManagerBat create avd --name $AvdName --package $SystemImagePackage --force
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $avdConfigIni)) {
        throw "avdmanager 创建 AVD '$AvdName' 失败 ($LASTEXITCODE)"
    }
} else {
    Write-Host "[emulator] 复用已存在的 AVD: $avdConfigIni" -ForegroundColor Green
}

# 幂等应用现代旗舰竖屏 (1080x2400 @ 420dpi) 与物理键盘配置
Set-IniKeyValues -FilePath $avdConfigIni -Entries @{
    'hw.lcd.width'            = '1080'
    'hw.lcd.height'           = '2400'
    'hw.lcd.density'          = '420'
    'hw.keyboard'             = 'yes'
    'hw.ramSize'              = '4096'
    'vm.heapSize'             = '512'
    'disk.dataPartition.size' = '4096M'
    'hw.gpu.enabled'          = 'yes'
    'hw.gpu.mode'             = $effectiveGpuMode
    'hw.audioInput'           = 'yes'
    'hw.audioOutput'          = 'yes'
    'showDeviceFrame'         = 'no'
    'skin.dynamic'            = 'yes'
    'hw.mainKeys'             = 'no'
}

Write-Host "[emulator] AVD 就绪: name=$AvdName, home=$avdHome (1080x2400, 420dpi, hw.keyboard=yes, gpu=$effectiveGpuMode)" -ForegroundColor Green

if ($SkipStart) {
    return
}

$runningDevices = (& $adbExe devices 2>$null | Select-String -Pattern '^emulator-\d+\s+device$')
if ($runningDevices) {
    Write-Host "[emulator] 检测到运行中的模拟器实例: $($runningDevices -join ', ')" -ForegroundColor Yellow
} else {
    $activeQemu = Get-Process -Name 'qemu-system-x86_64', 'emulator' -ErrorAction SilentlyContinue
    if (-not $activeQemu) {
        Get-ChildItem -Path $avdDir -Filter '*.lock' -ErrorAction SilentlyContinue | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
    }
    $emuArgs = @(
        '-avd', $AvdName,
        '-no-snapshot-save',
        '-no-boot-anim',
        '-gpu', $effectiveGpuMode
    )
    if ($Headless) {
        $emuArgs += '-no-window', '-no-audio'
    }
    Write-Host "[emulator] 启动模拟器: $emulatorExe $($emuArgs -join ' ')" -ForegroundColor Cyan
    Start-Process -FilePath $emulatorExe -ArgumentList $emuArgs -WindowStyle Normal | Out-Null
}

if ($WaitBoot) {
    Write-Host '[emulator] 等待 adb 设备在线与系统启动完成...' -ForegroundColor Cyan
    & $adbExe wait-for-device
    $deadline = (Get-Date).AddMinutes(3)
    while ((Get-Date) -lt $deadline) {
        $booted = (& $adbExe shell getprop sys.boot_completed 2>$null | Out-String).Trim()
        if ($booted -eq '1') {
            Write-Host '[emulator] 模拟器已完全启动 (sys.boot_completed=1)!' -ForegroundColor Green
            return
        }
        Start-Sleep -Seconds 2
    }
    throw '等待模拟器启动超时 (sys.boot_completed != 1)'
}
