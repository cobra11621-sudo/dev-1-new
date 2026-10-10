@echo off
setlocal EnableExtensions

rem Dev-1-new debug APK builder. It does not install the APK or run tests.
cd /d "%~dp0"

for %%I in ("%~dp0..") do set "WORKSPACE_ROOT=%%~fI"
set "ANDROID_HOME=%WORKSPACE_ROOT%\flutter-sdk-home\Android\sdk"
if not exist "%ANDROID_HOME%\platform-tools\adb.exe" set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
set "GRADLE_USER_HOME=%WORKSPACE_ROOT%\nodelete\gradle-dev1new"
set "WRAPPER_JAR=gradle\wrapper\gradle-wrapper.jar"
set "APK_OUTPUT=%WORKSPACE_ROOT%\delete\dev-1-new-build\outputs\apk\debug\app-debug.apk"

if not exist "%ANDROID_HOME%\platform-tools\adb.exe" (
    echo Android SDK was not found: %ANDROID_HOME%
    echo Check %WORKSPACE_ROOT%\flutter-sdk-home\Android\sdk or %%LOCALAPPDATA%%\Android\Sdk.
    if not defined DDD_CHAINED pause
    exit /b 1
)

if not exist "%WRAPPER_JAR%" (
    echo Gradle wrapper was not found: %WRAPPER_JAR%
    echo Check the project's gradle\wrapper folder.
    if not defined DDD_CHAINED pause
    exit /b 1
)

where java >nul 2>&1
if errorlevel 1 (
    echo Java 17 was not found on PATH.
    echo Install JDK 17, then open a new command prompt and run this file again.
    if not defined DDD_CHAINED pause
    exit /b 1
)

echo.
echo [1/2] Building Dev-1-new APK...
java -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain :app:assembleDebug --no-daemon --console=plain
if errorlevel 1 (
    echo.
    echo APK build failed.
    if not defined DDD_CHAINED pause
    exit /b 1
)

if not exist "%APK_OUTPUT%" (
    echo.
    echo Build finished but APK was not found: %APK_OUTPUT%
    if not defined DDD_CHAINED pause
    exit /b 1
)

echo.
echo [2/2] Done
echo APK: %APK_OUTPUT%
if not defined DDD_NO_EXPLORER start "" explorer.exe /select,"%APK_OUTPUT%"
if not defined DDD_CHAINED pause
