@echo off
rem Headless (no window, no GPU) Dedicated Server launcher for Starsector Coop.
rem
rem Runs the real engine with jars\coop-headless-lwjgl.jar first on the classpath: no window is
rem created and no native OpenGL/OpenAL library is loaded. Everything else - saves, mods, network -
rem is the same code the graphical client runs.
rem
rem Usage: Start Headless Dedicated Server.cmd [port] [maxGuests] [tps]
setlocal

set "MOD_DIR=%~dp0"
if "%MOD_DIR:~-1%"=="\" set "MOD_DIR=%MOD_DIR:~0,-1%"

set "ROOT="
if exist "%MOD_DIR%\..\..\jre\bin\java.exe" if exist "%MOD_DIR%\..\..\starsector-core\starfarer_obf.jar" (
    pushd "%MOD_DIR%\..\.." >nul 2>&1
    call :set_root_cd
    popd
)
if not defined ROOT call :try_root "C:\@\Starsector"
if not defined ROOT call :try_root "C:\Starsector"
if not defined ROOT call :try_root "D:\Starsector"
if not defined ROOT call :try_root "C:\Program Files (x86)\Fractal Softworks\Starsector"
if not defined ROOT call :try_root "C:\Program Files\Fractal Softworks\Starsector"

if not defined ROOT (
    echo [ERROR] Could not find Starsector installation root ^(jre\bin\java.exe + starsector-core\starfarer_obf.jar^).
    echo [ERROR] Run this file from ^<root^>\mods\coop, or install the coop mod into your game first.
    pause
    exit /b 1
)

if not exist "%MOD_DIR%\jars\coop-headless-lwjgl.jar" (
    echo [ERROR] jars\coop-headless-lwjgl.jar is missing.
    echo [ERROR] Build the mod first: gradlew.bat build  - it produces the headless jar.
    pause
    exit /b 1
)

set "PORT=7777"
if not "%~1"=="" set "PORT=%~1"
set "MAX_GUESTS=4"
if not "%~2"=="" set "MAX_GUESTS=%~2"
set "TPS=30"
if not "%~3"=="" set "TPS=%~3"

powershell -NoProfile -ExecutionPolicy Bypass -File "%MOD_DIR%\scripts\start-headless.ps1" -Root "%ROOT%" -Port %PORT% -MaxGuests %MAX_GUESTS% -Tps %TPS%
set "EXITCODE=%errorlevel%"
if not "%EXITCODE%"=="0" (
    echo [ERROR] Headless server exited with code %EXITCODE%.
    pause
)
endlocal & exit /b %EXITCODE%

:set_root_cd
set "ROOT=%CD%"
exit /b 0

:try_root
if not defined ROOT if exist "%~1\jre\bin\java.exe" if exist "%~1\starsector-core\starfarer_obf.jar" set "ROOT=%~1"
exit /b 0
