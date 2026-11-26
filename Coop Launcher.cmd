@echo off
rem Universal Starsector Coop Launcher (Install / Update, Host a game, Join a game).
rem Uses Starsector's bundled JRE when sitting inside <Starsector>\mods\coop, or auto-detects
rem a Starsector install (or system Java) when launched standalone from any folder.
setlocal

set "MOD_DIR=%~dp0"
if "%MOD_DIR:~-1%"=="\" set "MOD_DIR=%MOD_DIR:~0,-1%"

set "ROOT="
if exist "%MOD_DIR%\..\..\jre\bin\javaw.exe" (
    pushd "%MOD_DIR%\..\.." >nul 2>&1
    call :set_root_cd
    popd
)

if not defined ROOT call :try_root "C:\Starsector"
if not defined ROOT call :try_root "C:\Program Files (x86)\Fractal Softworks\Starsector"
if not defined ROOT call :try_root "C:\Program Files\Fractal Softworks\Starsector"
if not defined ROOT call :try_root "D:\Starsector"
if not defined ROOT call :try_root "D:\Games\Starsector"
if not defined ROOT call :try_root "E:\Starsector"
if not defined ROOT call :try_root "G:\Starsector"

set "JAVAW="
if defined ROOT if exist "%ROOT%\jre\bin\javaw.exe" set "JAVAW=%ROOT%\jre\bin\javaw.exe"
if not defined JAVAW (
    for /f "delims=" %%J in ('where javaw.exe 2^>nul') do if not defined JAVAW set "JAVAW=%%J"
)

if not defined JAVAW (
    echo Could not find Starsector's jre\bin\javaw.exe or a system javaw.exe.
    echo Run "Install Starsector Coop.cmd" first to install the mod into your Starsector folder.
    pause
    exit /b 1
)

call :apply_pending_jars

set "CP=%MOD_DIR%\jars\coop-launcher.jar;%MOD_DIR%\jars\flatlaf.jar;%MOD_DIR%\jars\coop.jar"
if defined ROOT (
    set "CP=%CP%;%ROOT%\starsector-core\json.jar;%ROOT%\starsector-core\log4j-1.2.9.jar"
)

start "" "%JAVAW%" -cp "%CP%" coop.launcher.CoopLauncherApp
endlocal
exit /b 0

:apply_pending_jars
if not exist "%MOD_DIR%\jars\*.jar.pending" exit /b 0
for /L %%R in (1,1,5) do (
    for %%F in ("%MOD_DIR%\jars\*.jar.pending") do (
        if exist "%%~fF" move /Y "%%~fF" "%MOD_DIR%\jars\%%~nF" >nul 2>&1
    )
    if not exist "%MOD_DIR%\jars\*.jar.pending" exit /b 0
    ping -n 2 127.0.0.1 >nul
)
exit /b 0

:set_root_cd
set "ROOT=%CD%"
exit /b 0

:try_root
if not defined ROOT if exist "%~1\jre\bin\javaw.exe" set "ROOT=%~1"
exit /b 0
