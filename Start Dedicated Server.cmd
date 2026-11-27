@echo off
rem Dedicated Host Server launcher for Starsector Coop.
rem Auto-loads the latest campaign save, keeps the world running on 1x during battles,
rem auto-pauses when 0 guests are connected, supports 4+ guests, and stores per-player saves.
setlocal

set "MOD_DIR=%~dp0"
if "%MOD_DIR:~-1%"=="\" set "MOD_DIR=%MOD_DIR:~0,-1%"

set "ROOT="
if exist "%MOD_DIR%\..\..\jre\bin\java.exe" (
    pushd "%MOD_DIR%\..\.." >nul 2>&1
    call :set_root_cd
    popd
)
if not defined ROOT call :try_root "C:\@\Starsector"
if not defined ROOT call :try_root "C:\Starsector"
if not defined ROOT call :try_root "D:\Starsector"

if not defined ROOT (
    echo Could not find Starsector installation root ^(jre\bin\java.exe^).
    pause
    exit /b 1
)

set "PORT=7777"
if not "%~1"=="" set "PORT=%~1"

pushd "%ROOT%\starsector-core" >nul 2>&1
if exist "starsector.exe" (
    echo Starting Starsector Coop Dedicated Server on port %PORT%...
    "%ROOT%\jre\bin\java.exe" -Xms1536m -Xmx1536m -Xss2048k ^
        -Dcoop.dedicatedHost=true ^
        -Dcoop.hostPort=%PORT% ^
        -Dcoop.maxGuests=4 ^
        -Dcoop.liveWorldInCombat=true ^
        -Dcoop.allowMidSessionJoin=true ^
        --add-opens=java.base/java.lang=ALL-UNNAMED ^
        --add-opens=java.base/java.lang.reflect=ALL-UNNAMED ^
        --add-opens=java.base/java.util=ALL-UNNAMED ^
        --add-opens=java.base/java.text=ALL-UNNAMED ^
        --add-opens=java.desktop/java.awt=ALL-UNNAMED ^
        --add-opens=java.desktop/java.awt.font=ALL-UNNAMED ^
        -Djava.library.path=native\windows ^
        -Dcom.fs.starfarer.settings.linux=false ^
        -cp "starfarer_obf.jar;starfarer.api.jar;starsector-core\starfarer.api.jar;..\mods\coop\jars\coop-forks.jar;janino.jar;commons-compiler.jar;commons-compiler-jdk.jar;starfarer.res.jar;xstream-1.4.10.jar;log4j-1.2.9.jar;lwjgl.jar;lwjgl_util.jar;fs.sound_obf.jar;fs.common_obf.jar;json.jar;jogg-0.0.7.jar;jorbis-0.0.15.jar;jaxb-api-2.4.0-b180830.0359.jar" ^
        com.fs.starfarer.StarfarerLauncher
)
popd >nul 2>&1
endlocal
exit /b 0

:set_root_cd
set "ROOT=%CD%"
exit /b 0

:try_root
if not defined ROOT if exist "%~1\jre\bin\java.exe" set "ROOT=%~1"
exit /b 0
