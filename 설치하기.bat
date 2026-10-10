@echo off
setlocal EnableExtensions

rem Install the already-built Dev-1-new APK as an in-place update.
rem This script never uninstalls the existing app or clears its data.
cd /d "%~dp0"

for %%I in ("%~dp0..") do set "WORKSPACE_ROOT=%%~fI"
set "ADB=%WORKSPACE_ROOT%\flutter-sdk-home\Android\sdk\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
set "APK=%WORKSPACE_ROOT%\delete\dev-1-new-build\outputs\apk\debug\app-debug.apk"

if not exist "%ADB%" (
    echo adb was not found.
    echo Check %WORKSPACE_ROOT%\flutter-sdk-home\Android\sdk or %%LOCALAPPDATA%%\Android\Sdk.
    if not defined DDD_CHAINED pause
    exit /b 1
)

if not exist "%APK%" (
    echo Built APK was not found:
    echo %APK%
    echo Build first with 빌드하기.bat.
    if not defined DDD_CHAINED pause
    exit /b 1
)

echo.
echo Checking USB debugging connection...
"%ADB%" start-server
"%ADB%" -d get-state
if errorlevel 1 (
    echo.
    echo No authorized USB device was found.
    echo Unlock the phone, allow USB debugging, then run this file again.
    if not defined DDD_CHAINED pause
    exit /b 1
)

echo.
echo Installing update without deleting existing app data...
"%ADB%" -d install -r "%APK%"
if errorlevel 1 (
    echo.
    echo Update installation failed. Do not uninstall the existing app.
    echo If the error says INSTALL_FAILED_UPDATE_INCOMPATIBLE, use the original signing key.
    if not defined DDD_CHAINED pause
    exit /b 1
)

echo.
echo Installation completed.
if not defined DDD_CHAINED pause
