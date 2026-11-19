@echo off
setlocal EnableExtensions

set "SCRIPT_DIR=%~dp0"
if "%SCRIPT_DIR:~-1%"=="\" set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"
for %%I in ("%SCRIPT_DIR%\..\..") do set "REPO_ROOT=%%~fI"

set "PS_SCRIPT=%SCRIPT_DIR%\setup-android-emulator.ps1"
set "ADB_EXE=%REPO_ROOT%\.dev-bin\android-toolchain\android-sdk\platform-tools\adb.exe"
set "APK_PATH=%REPO_ROOT%\melokeep_outputs\MeloKeep-android-debug.apk"
set "PKG_NAME=io.github.agonie0v0.melokeep.debug"
set "MAIN_ACTIVITY=%PKG_NAME%/paige.navic.MainActivity"

set "PWSH_EXE="
where pwsh >nul 2>&1
if %ERRORLEVEL%==0 (
    set "PWSH_EXE=pwsh"
) else if exist "C:\Program Files\PowerShell\7\pwsh.exe" (
    set "PWSH_EXE=C:\Program Files\PowerShell\7\pwsh.exe"
) else (
    set "PWSH_EXE=powershell"
)

echo [MeloKeep] Starting Android emulator (melokeep_studio, GpuMode=auto)...
"%PWSH_EXE%" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%PS_SCRIPT%" -GpuMode auto -WaitBoot
if errorlevel 1 (
    echo [MeloKeep] Failed to launch emulator.
    pause
    exit /b 1
)

if not exist "%ADB_EXE%" (
    echo [MeloKeep] adb.exe not found: %ADB_EXE%
    pause
    exit /b 1
)

echo [MeloKeep] Configuring adb reverse tcp:8765 tcp:8765...
"%ADB_EXE%" reverse tcp:8765 tcp:8765 >nul 2>&1

"%ADB_EXE%" shell pm path %PKG_NAME% >nul 2>&1
if errorlevel 1 (
    if exist "%APK_PATH%" (
        echo [MeloKeep] Installing Debug APK...
        "%ADB_EXE%" install -r "%APK_PATH%"
        if errorlevel 1 (
            echo [MeloKeep] Failed to install APK.
            pause
            exit /b 1
        )
    ) else (
        echo [MeloKeep] %PKG_NAME% is not installed and APK not found at %APK_PATH%.
        pause
        exit /b 0
    )
)

echo [MeloKeep] Launching %MAIN_ACTIVITY%...
"%ADB_EXE%" shell am start -n %MAIN_ACTIVITY%
if errorlevel 1 (
    echo [MeloKeep] Failed to launch MainActivity.
    pause
    exit /b 1
)

echo [MeloKeep] Emulator and MeloKeep app are ready!
exit /b 0
