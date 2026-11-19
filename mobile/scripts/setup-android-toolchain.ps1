<#
.SYNOPSIS
    幂等安装便携版 OpenJDK 21 与 Android SDK（platform-tools / platforms;android-37.2 / build-tools;37.0.0）
    到 .dev-bin/android-toolchain/，无需全局修改系统环境变量。
#>
[CmdletBinding()]
param(
    [string]$ToolchainRoot = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if ([string]::IsNullOrWhiteSpace($ToolchainRoot)) {
    $ToolchainRoot = Join-Path $repoRoot '.dev-bin\android-toolchain'
}

$jdkDir = Join-Path $ToolchainRoot 'jdk-21'
$sdkDir = Join-Path $ToolchainRoot 'android-sdk'
$cmdlineLatestDir = Join-Path $sdkDir 'cmdline-tools\latest'
$tmpDir = Join-Path $ToolchainRoot '_tmp'

New-Item -ItemType Directory -Force -Path $ToolchainRoot, $sdkDir, $tmpDir | Out-Null

function Invoke-DownloadFile {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [Parameter(Mandatory = $true)][string]$DestPath
    )
    if (Test-Path $DestPath) {
        Remove-Item -Force $DestPath
    }
    Write-Host "[toolchain] 下载: $Url" -ForegroundColor Cyan
    & curl.exe -fL --retry 3 --retry-delay 2 --connect-timeout 20 -o $DestPath $Url
    if ($LASTEXITCODE -ne 0) {
        throw "下载失败 ($LASTEXITCODE): $Url"
    }
}

$javaExe = Join-Path $jdkDir 'bin\java.exe'
if (-not (Test-Path $javaExe)) {
    $jdkZip = Join-Path $tmpDir 'openjdk21-win-x64.zip'
    $jdkUrl = 'https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse'
    Invoke-DownloadFile -Url $jdkUrl -DestPath $jdkZip

    $jdkExtract = Join-Path $tmpDir 'jdk-extract'
    if (Test-Path $jdkExtract) { Remove-Item -Recurse -Force $jdkExtract }
    New-Item -ItemType Directory -Force -Path $jdkExtract | Out-Null

    Write-Host "[toolchain] 解压 OpenJDK 21..." -ForegroundColor Cyan
    Expand-Archive -Path $jdkZip -DestinationPath $jdkExtract -Force

    $extractedRoot = Get-ChildItem -Path $jdkExtract -Directory | Select-Object -First 1
    if ($null -eq $extractedRoot) {
        throw "OpenJDK 21 解压目录为空"
    }
    if (Test-Path $jdkDir) { Remove-Item -Recurse -Force $jdkDir }
    Move-Item -Path $extractedRoot.FullName -Destination $jdkDir
    Remove-Item -Force $jdkZip -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force $jdkExtract -ErrorAction SilentlyContinue
    Write-Host "[toolchain] OpenJDK 21 已就绪: $jdkDir" -ForegroundColor Green
}

$env:JAVA_HOME = $jdkDir
$env:ANDROID_HOME = $sdkDir
$env:ANDROID_SDK_ROOT = $sdkDir
$env:PATH = "$(Join-Path $jdkDir 'bin');$env:PATH"

$sdkManagerBat = Join-Path $cmdlineLatestDir 'bin\sdkmanager.bat'
if (-not (Test-Path $sdkManagerBat)) {
    $cmdZip = Join-Path $tmpDir 'commandlinetools-win.zip'
    $cmdUrl = 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip'
    Invoke-DownloadFile -Url $cmdUrl -DestPath $cmdZip

    $cmdExtract = Join-Path $tmpDir 'cmdline-extract'
    if (Test-Path $cmdExtract) { Remove-Item -Recurse -Force $cmdExtract }
    New-Item -ItemType Directory -Force -Path $cmdExtract | Out-Null

    Write-Host "[toolchain] 解压 Android command-line tools..." -ForegroundColor Cyan
    Expand-Archive -Path $cmdZip -DestinationPath $cmdExtract -Force

    $innerDir = Join-Path $cmdExtract 'cmdline-tools'
    if (-not (Test-Path $innerDir)) {
        $innerDir = (Get-ChildItem -Path $cmdExtract -Directory | Select-Object -First 1).FullName
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $cmdlineLatestDir) | Out-Null
    if (Test-Path $cmdlineLatestDir) { Remove-Item -Recurse -Force $cmdlineLatestDir }
    Move-Item -Path $innerDir -Destination $cmdlineLatestDir
    Remove-Item -Force $cmdZip -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force $cmdExtract -ErrorAction SilentlyContinue
}

$requiredPackages = @(
    @{ Name = 'platform-tools'; CheckPath = (Join-Path $sdkDir 'platform-tools\adb.exe') },
    @{ Name = 'platforms;android-37.2'; CheckPath = (Join-Path $sdkDir 'platforms\android-37.2\android.jar') },
    @{ Name = 'build-tools;37.0.0'; CheckPath = (Join-Path $sdkDir 'build-tools\37.0.0\aapt2.exe') }
)

$missing = $requiredPackages | Where-Object { -not (Test-Path $_.CheckPath) } | ForEach-Object { $_.Name }
if ($missing.Count -gt 0) {
    Write-Host "[toolchain] 接受 Android SDK 许可协议..." -ForegroundColor Cyan
    1..20 | ForEach-Object { 'y' } | & $sdkManagerBat "--sdk_root=$sdkDir" --licenses | Out-Null

    Write-Host "[toolchain] 安装 Android SDK 组件: $($missing -join ', ')" -ForegroundColor Cyan
    1..20 | ForEach-Object { 'y' } | & $sdkManagerBat "--sdk_root=$sdkDir" @missing
    if ($LASTEXITCODE -ne 0) {
        throw "sdkmanager 安装组件失败 ($LASTEXITCODE)"
    }
}

if (Test-Path $tmpDir) {
    Remove-Item -Recurse -Force $tmpDir -ErrorAction SilentlyContinue
}

Write-Host "[toolchain] 工具链准备完成! JAVA_HOME=$jdkDir ANDROID_HOME=$sdkDir" -ForegroundColor Green
& $javaExe -version
& (Join-Path $sdkDir 'platform-tools\adb.exe') version
