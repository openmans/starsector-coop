@echo off
rem Dedicated Host Server launcher for Starsector Coop.
rem Reads your install's vmparams, ensures coop-forks.jar is first on classpath,
rem and starts Starsector in unattended Dedicated Host mode.
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
    if not "%COOP_DEDICATED_DRY_RUN%"=="1" pause
    exit /b 1
)

set "PORT=7777"
if not "%~1"=="" set "PORT=%~1"

echo Starting Starsector Coop Dedicated Server...
echo   Root: %ROOT%
echo   Port: %PORT%

powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $root=$env:ROOT; $port=$env:PORT; $dry=($env:COOP_DEDICATED_DRY_RUN -eq '1'); $core=Join-Path $root 'starsector-core'; $java=Join-Path $root 'jre\bin\java.exe'; $vmPath=Join-Path $root 'vmparams'; if (Test-Path -LiteralPath $vmPath) { $vmArgs=(Get-Content -LiteralPath $vmPath -Raw).Trim() -replace '^\s*\S*javaw?(\.exe)?\s+','' } else { $vmArgs='-Djava.library.path=native\windows --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.text=ALL-UNNAMED --add-opens=java.desktop/java.awt=ALL-UNNAMED --add-opens=java.desktop/java.awt.font=ALL-UNNAMED -Xmx2048m -Xms1024m -Xss4m -Dcom.fs.starfarer.settings.paths.saves=..\saves -Dcom.fs.starfarer.settings.paths.screenshots=..\screenshots -Dcom.fs.starfarer.settings.paths.mods=..\mods -Dcom.fs.starfarer.settings.paths.logs=. -classpath ..\mods\coop\jars\coop-forks.jar;janino.jar;commons-compiler.jar;commons-compiler-jdk.jar;starfarer.api.jar;starfarer_obf.jar;jogg-0.0.7.jar;jorbis-0.0.15.jar;json.jar;lwjgl.jar;jinput.jar;log4j-1.2.9.jar;lwjgl_util.jar;fs.sound_obf.jar;fs.common_obf.jar;xstream-1.4.10.jar;txw2-3.0.2.jar;jaxb-api-2.4.0-b180830.0359.jar;webp-imageio-0.1.6.jar com.fs.starfarer.StarfarerLauncher' }; if ($vmArgs -notmatch 'coop-forks\.jar') { $vmArgs=$vmArgs -replace '(-classpath|-cp)\s+(\S+)','$1 ..\mods\coop\jars\coop-forks.jar;$2' }; $coopFlags='-Dcoop.dedicatedHost=true -Dcoop.hostPort=' + $port + ' -Dcoop.maxGuests=4 -Dcoop.liveWorldInCombat=true -Dcoop.allowMidSessionJoin=true'; $fullArgs=$coopFlags + ' ' + $vmArgs; if ($dry) { Write-Host ('DRY_RUN java=' + $java); Write-Host ('DRY_RUN cwd=' + $core); Write-Host ('DRY_RUN flags=' + $coopFlags); Write-Host ('DRY_RUN hasForks=' + $fullArgs.Contains('coop-forks.jar')); exit 0 }; $p=Start-Process -FilePath $java -ArgumentList $fullArgs -WorkingDirectory $core -PassThru; if ($null -eq $p) { exit 1 }"

if errorlevel 1 (
    echo [ERROR] Dedicated Server launch failed ^(exit code %errorlevel%^).
    if not "%COOP_DEDICATED_DRY_RUN%"=="1" pause
    exit /b %errorlevel%
)
endlocal
exit /b 0

:set_root_cd
set "ROOT=%CD%"
exit /b 0

:try_root
if not defined ROOT if exist "%~1\jre\bin\java.exe" if exist "%~1\starsector-core\starfarer_obf.jar" set "ROOT=%~1"
exit /b 0
