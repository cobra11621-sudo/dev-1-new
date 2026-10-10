@echo off
setlocal EnableExtensions
chcp 65001 >nul
set "DDD_CHAINED=1"
set "DDD_NO_EXPLORER=1"
cd /d "%~dp0"

echo Building...
call "%~dp0빌드하기.bat"
if errorlevel 1 (
    echo.
    echo Build failed. Installation was not started.
    pause
    exit /b 1
)

echo.
echo Installing...
call "%~dp0설치하기.bat"
set "RESULT=%ERRORLEVEL%"
if not "%RESULT%"=="0" echo Installation failed.
if "%RESULT%"=="0" echo Build and installation completed.
pause
exit /b %RESULT%
