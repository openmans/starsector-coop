<#
  Starts the windowless dedicated server.

  The only thing that makes this different from "Start Dedicated Server.cmd" is the classpath: the
  Null-LWJGL jar is placed strictly before lwjgl.jar, so every org.lwjgl class the engine links
  against resolves to a stub that creates no window and loads no native library. Everything else
  (engine, mod, saves, network) is the real thing.

  Arguments:
    -Root        Starsector install root (the folder holding vmparams/jre/starsector-core)
    -Port        host port, default 7777
    -MaxGuests   guest slots, default 4
    -Tps         frame pacing target, default 30
    -Awt         'auto' (default) picks per OS; '1' forces java.awt.headless=true, '0' forces false
    -DryRun      print the resolved command line and exit
#>
param(
    [Parameter(Mandatory = $true)][string]$Root,
    [int]$Port = 7777,
    [int]$MaxGuests = 4,
    [int]$Tps = 30,
    [string]$Awt = 'auto',
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

function Fail([string]$message) {
    Write-Host "[ERROR] $message" -ForegroundColor Red
    exit 1
}

$rootPath = (Resolve-Path -LiteralPath $Root).Path
$core = Join-Path $rootPath 'starsector-core'
$java = Join-Path $rootPath 'jre\bin\java.exe'
if (-not (Test-Path -LiteralPath $java)) { $java = Join-Path $rootPath 'jre\bin\javaw.exe' }
$vmPath = Join-Path $rootPath 'vmparams'

if (-not (Test-Path -LiteralPath $core)) { Fail "starsector-core not found under $rootPath" }
if (-not (Test-Path -LiteralPath $java)) { Fail "jre\bin\java.exe not found under $rootPath" }
if (-not (Test-Path -LiteralPath $vmPath)) { Fail "vmparams not found under $rootPath" }

$modRoot = Split-Path -Parent $PSScriptRoot
$headlessJar = Join-Path $modRoot 'jars\coop-headless-lwjgl.jar'
$forksJar = Join-Path $modRoot 'jars\coop-forks.jar'
if (-not (Test-Path -LiteralPath $headlessJar)) {
    Fail "jars\coop-headless-lwjgl.jar is missing - run a build (gradlew build) or the release zip is incomplete"
}

# Paths inside the classpath are resolved by the JVM against its working directory (starsector-core),
# so compute them relative to it: this works for any install location, including paths with spaces
# (the JVM receives one argv entry, PowerShell does the quoting).
function Relative-ToCore([string]$path) {
    # Resolve-Path -Relative is unreliable on Windows PowerShell 5.1 (returns an empty string when
    # the target is not under the current directory), so do the URI math explicitly.
    $fromUri = New-Object System.Uri(($core.TrimEnd('\') + '\'))
    $toUri = New-Object System.Uri((Resolve-Path -LiteralPath $path).Path)
    return [System.Uri]::UnescapeDataString($fromUri.MakeRelativeUri($toUri).ToString()).Replace('/', '\')
}
$cpHeadless = Relative-ToCore $headlessJar
$cpForks = Relative-ToCore $forksJar

$raw = (Get-Content -LiteralPath $vmPath -Raw)

# Drop the leading "java.exe" / "javaw.exe" token (the launcher may prefix it with a full path).
$raw = $raw -replace '^\s*"?[^\s"]*javaw?(\.exe)?"?\s+', ''

# Pull -classpath out; the value is a single token (quoted or not).
$cpMatch = [regex]::Match($raw, '(-classpath|-cp)\s+("([^"]*)"|[^\s]+)')
if (-not $cpMatch.Success) { Fail "vmparams has no -classpath entry" }
$vmClasspath = if ($cpMatch.Groups[3].Success) { $cpMatch.Groups[3].Value } else { $cpMatch.Groups[2].Value }
$raw = $raw.Remove($cpMatch.Index, $cpMatch.Length)

$vmTokens = @($raw -split '\s+' | Where-Object { $_ -ne '' })
$launchTokens = @($vmTokens | Where-Object { $_ -ne 'com.fs.starfarer.StarfarerLauncher' })

# coop-forks.jar must stay ahead of the engine jars it overrides; the headless shim must stay ahead
# of lwjgl.jar. If the install check has not patched vmparams yet, add the forks entry ourselves.
$newClasspath = $cpHeadless + ';' + $vmClasspath
if ($vmClasspath -notmatch 'coop-forks\.jar') {
    $newClasspath = $cpHeadless + ';' + $cpForks + ';' + $vmClasspath
}

$headlessFlags = @(
    "-Dcoop.dedicatedHost=true",
    "-Dcoop.hostPort=$Port",
    "-Dcoop.maxGuests=$MaxGuests",
    "-Dcoop.liveWorldInCombat=true",
    "-Dcoop.allowMidSessionJoin=true",
    "-Dcoop.headless=true",
    "-Dcoop.headlessTickRate=$Tps",
    # Keep Java2D off the GPU as well: with the real AWT toolkit on Windows (needed because the
    # launcher's three-argument CombatMain path probes Toolkit.getScreenSize), Java2D would
    # otherwise load opengl32.dll/d3d for its pipelines. The server draws nothing.
    "-Dsun.java2d.opengl=false",
    "-Dsun.java2d.d3d=false",
    "-Dsun.java2d.noddraw=true"
)

if ($Awt -eq '1') { $headlessFlags += '-Djava.awt.headless=true' }
if ($Awt -eq '0') { $headlessFlags += '-Djava.awt.headless=false' }
$appArgs = @('coop.headless.CoopHeadlessServerMain')
$argList = @('-classpath', $newClasspath) + $launchTokens + $headlessFlags + $appArgs

if ($DryRun) {
    Write-Host "DRY_RUN java=$java"
    Write-Host "DRY_RUN cwd=$core"
    Write-Host "DRY_RUN classpath=$newClasspath"
    Write-Host "DRY_RUN main=coop.headless.CoopHeadlessServerMain"
    Write-Host "DRY_RUN argCount=$($argList.Count)"
    exit 0
}

Write-Host "Starting Starsector Coop HEADLESS dedicated server"
Write-Host "  root      : $rootPath"
Write-Host "  port      : $Port"
Write-Host "  maxGuests : $MaxGuests"
Write-Host "  tps       : $Tps"
Write-Host "  classpath : $newClasspath"
Write-Host ""

Push-Location -LiteralPath $core
try {
    & $java @argList
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $code
