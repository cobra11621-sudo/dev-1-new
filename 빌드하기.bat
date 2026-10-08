@echo off
setlocal EnableExtensions

rem Dev-1-new debug APK builder. It does not install the APK or run tests.
cd /d "%~dp0"

set "ANDROID_HOME=E:\ddd\android-sdk"
if not exist "%ANDROID_HOME%\platform-tools\adb.exe" set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
set "GRADLE_USER_HOME=E:\ddd\nodelete\gradle-dev1new"
set "WRAPPER_JAR=gradle\wrapper\gradle-wrapper.jar"
set "APK_OUTPUT=E:\ddd\delete\dev-1-new-build\outputs\apk\debug\app-debug.apk"

if not exist "%ANDROID_HOME%\platform-tools\adb.exe" (
    echo Android SDK was not found: %ANDROID_HOME%
    echo Check E:\ddd\android-sdk or %%LOCALAPPDATA%%\Android\Sdk.
    pause
    exit /b 1
)

if not exist "%WRAPPER_JAR%" (
    echo Gradle wrapper was not found: %WRAPPER_JAR%
    echo Check the project's gradle\wrapper folder.
    pause
    exit /b 1
)

where java >nul 2>&1
if errorlevel 1 (
    echo Java 17 was not found on PATH.
    echo Install JDK 17, then open a new command prompt and run this file again.
    pause
    exit /b 1
)

echo.
echo [1/2] Building Dev-1-new APK...
java -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain :app:assembleDebug --no-daemon --console=plain
if errorlevel 1 (
    echo.
    echo APK build failed.
    pause
    exit /b 1
)

if not exist "%APK_OUTPUT%" (
    echo.
    echo Build finished but APK was not found: %APK_OUTPUT%
    pause
    exit /b 1
)

echo.
echo [2/2] Done
echo APK: %APK_OUTPUT%
start "" explorer.exe /select,"%APK_OUTPUT%"
pause
