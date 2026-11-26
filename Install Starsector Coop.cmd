@echo off
rem ============================================================================
rem  Starsector Coop - One-Click Installer (Windows)
rem  Double-click this file, select starsector.exe, and it will automatically:
rem    1. Download the latest Starsector Coop release from GitHub
rem    2. Extract it into <Starsector>\mods\coop
rem    3. Back up & patch vmparams (also repairs broken tuning-pack classpaths)
rem    4. Enable "coop" in mods\enabled_mods.json (preserving your other mods)
rem    5. Launch Coop Launcher
rem ============================================================================
setlocal
title Starsector Coop - One-Click Installer

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$self = '%~f0'; $argsIn = @('%~1', '%~2'); $lines = Get-Content -LiteralPath $self; $idx = [Array]::IndexOf($lines, '###__PS_PAYLOAD__###'); if ($idx -lt 0) { throw 'Installer payload missing.' }; $script = ($lines[($idx + 1)..($lines.Length - 1)] -join [Environment]::NewLine); & ([ScriptBlock]::Create($script)) $self $argsIn"

set "EC=%ERRORLEVEL%"
if not "%EC%"=="0" (
    echo.
    echo Installer finished with error code %EC%.
    pause
)
exit /b %EC%

###__PS_PAYLOAD__###
param([string]$SelfCmdPath, [string[]]$RawArgs)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.IO.Compression.FileSystem

$Repo = 'openmans/starsector-coop'
$LatestReleaseApi = "https://api.github.com/repos/$Repo/releases/latest"
$ReleasesListApi  = "https://api.github.com/repos/$Repo/releases?per_page=5"
$ForksEntry       = '..\mods\coop\jars\coop-forks.jar;'
$CanonicalStockJars = @(
    'janino.jar','commons-compiler.jar','commons-compiler-jdk.jar',
    'starfarer.api.jar','starfarer_obf.jar','jogg-0.0.7.jar','jorbis-0.0.15.jar',
    'json.jar','jinput.jar','log4j-1.2.9.jar','lwjgl.jar','lwjgl_util.jar',
    'fs.sound_obf.jar','fs.common_obf.jar','xstream-1.4.10.jar'
)

$NoLaunch = $false
$TargetArg = ''
foreach ($a in $RawArgs) {
    if ([string]::IsNullOrWhiteSpace($a)) { continue }
    if ($a -ieq '--no-launch' -or $a -ieq '-NoLaunch') { $NoLaunch = $true; continue }
    if ([string]::IsNullOrWhiteSpace($TargetArg)) { $TargetArg = $a }
}

function Test-StarsectorRoot([string]$Dir) {
    if ([string]::IsNullOrWhiteSpace($Dir) -or -not (Test-Path -LiteralPath $Dir -PathType Container)) { return $false }
    return (Test-Path -LiteralPath (Join-Path $Dir 'starsector.exe') -PathType Leaf) `
       -and (Test-Path -LiteralPath (Join-Path $Dir 'vmparams') -PathType Leaf) `
       -and (Test-Path -LiteralPath (Join-Path $Dir 'starsector-core') -PathType Container)
}

function Resolve-StarsectorRoot([string]$Candidate) {
    if ([string]::IsNullOrWhiteSpace($Candidate)) { return $null }
    if (Test-Path -LiteralPath $Candidate -PathType Leaf) {
        $parent = Split-Path -Parent $Candidate
        if (Test-StarsectorRoot $parent) { return $parent }
        return $null
    }
    if (Test-StarsectorRoot $Candidate) { return $Candidate }
    $leaf = Split-Path -Leaf $Candidate
    if ($leaf -ieq 'starsector-core' -or $leaf -ieq 'mods') {
        $parent = Split-Path -Parent $Candidate
        if (Test-StarsectorRoot $parent) { return $parent }
    }
    return $null
}

function Find-DefaultStarsectorDir {
    $candidates = @(
        'C:\Program Files (x86)\Fractal Softworks\Starsector',
        'C:\Program Files\Fractal Softworks\Starsector',
        'C:\Starsector',
        'D:\Starsector',
        'D:\Games\Starsector',
        'E:\Starsector',
        'G:\Starsector'
    )
    foreach ($c in $candidates) {
        if (Test-StarsectorRoot $c) { return $c }
    }
    return 'C:\'
}

function Test-DirWritable([string]$Dir) {
    $probe = Join-Path $Dir ('.coop-write-test-' + [guid]::NewGuid().ToString('N') + '.tmp')
    try {
        [System.IO.File]::WriteAllText($probe, 'ok')
        Remove-Item -LiteralPath $probe -Force -ErrorAction SilentlyContinue
        return $true
    } catch {
        return $false
    }
}

function Resolve-LatestReleaseAsset {
    $headers = @{ 'User-Agent' = 'starsector-coop-installer'; 'Accept' = 'application/vnd.github+json' }
    $release = $null
    try {
        $release = Invoke-RestMethod -Uri $LatestReleaseApi -Headers $headers -TimeoutSec 15
    } catch {
        $list = Invoke-RestMethod -Uri $ReleasesListApi -Headers $headers -TimeoutSec 15
        foreach ($item in $list) {
            if (-not $item.draft -and $item.assets) {
                $release = $item
                break
            }
        }
    }
    if ($null -eq $release) {
        throw "No published release found on GitHub ($Repo)."
    }
    $asset = $release.assets | Where-Object { $_.name -like 'coop-*.zip' } | Select-Object -First 1
    if ($null -eq $asset) {
        $asset = $release.assets | Where-Object { $_.name -like '*.zip' } | Select-Object -First 1
    }
    if ($null -eq $asset) {
        throw "Release $($release.tag_name) has no downloadable .zip asset."
    }
    return [PSCustomObject]@{
        Version     = ($release.tag_name -replace '^[vV]', '')
        AssetName   = $asset.name
        DownloadUrl = $asset.browser_download_url
        HtmlUrl     = $release.html_url
    }
}

function Patch-Vmparams([string]$VmparamsPath) {
    $enc = [System.Text.Encoding]::GetEncoding('ISO-8859-1')
    $raw = [System.IO.File]::ReadAllText($VmparamsPath, $enc)
    $body = $raw.TrimEnd("`r", "`n")
    if ($body.IndexOf("`n") -ge 0 -or $body.IndexOf("`r") -ge 0) {
        throw 'vmparams has multiple lines; cannot patch safely automatically.'
    }
    $marker = ' -classpath '
    $idx = $body.IndexOf($marker)
    if ($idx -lt 0) {
        throw 'vmparams has no " -classpath " section.'
    }
    $head = $body.Substring(0, $idx + $marker.Length)
    $tail = $body.Substring($idx + $marker.Length)

    # Detect broken tuning-pack classpath (quotes, absolute drive paths, non-jar entries, missing janino.jar)
    $firstSpace = $tail.IndexOf(' ')
    $cpSegment = if ($firstSpace -ge 0) { $tail.Substring(0, $firstSpace) } else { $tail }
    $mainClass = 'com.fs.starfarer.StarfarerLauncher'
    $lastSpace = $tail.LastIndexOf(' ')
    if ($lastSpace -ge 0 -and $lastSpace + 1 -lt $tail.Length) {
        $mc = $tail.Substring($lastSpace + 1).Trim()
        if ($mc -match '^[A-Za-z0-9_.]+$') { $mainClass = $mc }
    }

    $needsRepair = ($tail.IndexOf('"') -ge 0) -or ($cpSegment -match '[A-Za-z]:\\') -or ($tail -notlike '*janino.jar*')
    if ($needsRepair) {
        $patched = $head + $ForksEntry + ($CanonicalStockJars -join ';') + ' ' + $mainClass
    } else {
        $parts = New-Object System.Collections.Generic.List[string]
        foreach ($p in $tail.Split(';')) {
            if ($p -match 'coop-forks\.jar$' -and $p -notmatch '\s') { continue }
            $parts.Add($p)
        }
        $patched = $head + $ForksEntry + ($parts -join ';')
    }

    if ($patched -ceq $body) {
        Write-Host '  vmparams is already configured.'
        return
    }
    $backup = "$VmparamsPath.backup"
    if (-not (Test-Path -LiteralPath $backup)) {
        Copy-Item -LiteralPath $VmparamsPath -Destination $backup -Force
        Write-Host "  Backed up vmparams -> $backup"
    }
    [System.IO.File]::WriteAllText($VmparamsPath, $patched, $enc)
    Write-Host "  Patched vmparams ($(if ($needsRepair) { 'repaired classpath + ' } else { '' })coop-forks.jar first)."
}

function Enable-CoopMod([string]$EnabledModsPath) {
    $modsDir = Split-Path -Parent $EnabledModsPath
    if (-not (Test-Path -LiteralPath $modsDir)) {
        New-Item -ItemType Directory -Path $modsDir -Force | Out-Null
    }
    $mods = New-Object System.Collections.Generic.List[string]
    if (Test-Path -LiteralPath $EnabledModsPath -PathType Leaf) {
        $json = Get-Content -LiteralPath $EnabledModsPath -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($json.enabledMods) {
            foreach ($m in $json.enabledMods) {
                if ($m -eq 'coop') {
                    Write-Host '  "coop" is already enabled in enabled_mods.json.'
                    return
                }
                $mods.Add([string]$m)
            }
        }
    }
    $mods.Add('coop')
    $quoted = ($mods | ForEach-Object { '    "' + ($_ -replace '\\', '\\' -replace '"', '\"') + '"' }) -join ",`n"
    $out = "{`n  `"enabledMods`": [`n$quoted`n  ]`n}`n"
    [System.IO.File]::WriteAllText($EnabledModsPath, $out, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host '  Enabled "coop" in mods\enabled_mods.json.'
}

Write-Host '============================================================' -ForegroundColor Cyan
Write-Host '  Starsector Coop - One-Click Installer' -ForegroundColor Cyan
Write-Host '============================================================' -ForegroundColor Cyan
Write-Host ''

# 1. Pick starsector.exe (or use passed argument)
$installRoot = Resolve-StarsectorRoot $TargetArg
if (-not $installRoot) {
    Write-Host 'Select your starsector.exe file in the dialog...'
    $dialog = New-Object System.Windows.Forms.OpenFileDialog
    $dialog.Title = 'Select starsector.exe (Starsector install)'
    $dialog.Filter = 'Starsector (starsector.exe)|starsector.exe|All files (*.*)|*.*'
    $dialog.FileName = 'starsector.exe'
    $dialog.InitialDirectory = Find-DefaultStarsectorDir
    $dialog.CheckFileExists = $true
    if ($dialog.ShowDialog() -ne [System.Windows.Forms.DialogResult]::OK) {
        Write-Host 'Cancelled by user.'
        exit 0
    }
    $installRoot = Resolve-StarsectorRoot $dialog.FileName
    if (-not $installRoot) {
        [System.Windows.Forms.MessageBox]::Show(
            "Selected file is not inside a valid Starsector folder (starsector.exe + vmparams + starsector-core required).",
            'Starsector Coop Installer',
            [System.Windows.Forms.MessageBoxButtons]::OK,
            [System.Windows.Forms.MessageBoxIcon]::Error) | Out-Null
        exit 1
    }
}

Write-Host "Game folder: $installRoot" -ForegroundColor Green

# 2. Elevate automatically if folder is protected (e.g. Program Files)
if (-not (Test-DirWritable $installRoot)) {
    $isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    if (-not $isAdmin) {
        Write-Host 'Starsector folder requires Administrator rights (e.g. Program Files). Requesting elevation...' -ForegroundColor Yellow
        $exePath = Join-Path $installRoot 'starsector.exe'
        $argStr = "/c `"`"$SelfCmdPath`" `"$exePath`"$(if ($NoLaunch) { ' --no-launch' } else { '' })`""
        Start-Process -FilePath 'cmd.exe' -ArgumentList $argStr -Verb RunAs
        exit 0
    }
    throw "Cannot write to $installRoot even as Administrator."
}

# 3. Resolve latest GitHub release & download
Write-Host "Checking GitHub ($Repo) for the latest release..."
$release = Resolve-LatestReleaseAsset
Write-Host "Latest release: v$($release.Version) ($($release.AssetName))" -ForegroundColor Green

$tempZip = Join-Path ([System.IO.Path]::GetTempPath()) ("starsector-coop-" + [guid]::NewGuid().ToString('N') + ".zip")
try {
    Write-Host "Downloading $($release.DownloadUrl) ..."
    $wc = New-Object System.Net.WebClient
    $wc.Headers.Add('User-Agent', 'starsector-coop-installer')
    $wc.DownloadFile($release.DownloadUrl, $tempZip)
    $sizeMb = [Math]::Round((Get-Item -LiteralPath $tempZip).Length / 1MB, 2)
    Write-Host "Downloaded $sizeMb MiB."

    # 4. Extract coop/* into <Starsector>\mods\coop
    $modRoot = Join-Path (Join-Path $installRoot 'mods') 'coop'
    New-Item -ItemType Directory -Path $modRoot -Force | Out-Null
    $normRoot = [System.IO.Path]::GetFullPath($modRoot).TrimEnd('\') + '\'

    Write-Host "Extracting to $modRoot ..."
    $archive = [System.IO.Compression.ZipFile]::OpenRead($tempZip)
    $count = 0
    try {
        foreach ($entry in $archive.Entries) {
            if ([string]::IsNullOrEmpty($entry.Name)) { continue }
            $rel = $entry.FullName.Replace('\', '/').TrimStart('/')
            if ($rel -imatch '^coop/') { $rel = $rel.Substring(5) }
            if ([string]::IsNullOrEmpty($rel)) { continue }
            $dest = [System.IO.Path]::GetFullPath((Join-Path $modRoot ($rel -replace '/', '\')))
            if (-not $dest.StartsWith($normRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
                throw "Unsafe zip entry: $($entry.FullName)"
            }
            $destDir = Split-Path -Parent $dest
            if (-not (Test-Path -LiteralPath $destDir)) {
                New-Item -ItemType Directory -Path $destDir -Force | Out-Null
            }
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $dest, $true)
            $count++
        }
    } finally {
        $archive.Dispose()
    }
    Write-Host "Extracted $count files." -ForegroundColor Green

    # 5. Configure vmparams & enabled_mods.json
    Write-Host 'Configuring Starsector...'
    Patch-Vmparams (Join-Path $installRoot 'vmparams')
    Enable-CoopMod (Join-Path (Join-Path $installRoot 'mods') 'enabled_mods.json')

    Write-Host ''
    Write-Host "SUCCESS! Starsector Coop v$($release.Version) is installed and ready." -ForegroundColor Green
    Write-Host "Launcher: $(Join-Path $modRoot 'Coop Launcher.cmd')"

    if (-not $NoLaunch) {
        $launcherCmd = Join-Path $modRoot 'Coop Launcher.cmd'
        if (Test-Path -LiteralPath $launcherCmd -PathType Leaf) {
            $launchNow = [System.Windows.Forms.MessageBox]::Show(
                "Starsector Coop v$($release.Version) has been installed and configured!`n`nFolder: $modRoot`n`nLaunch Coop Launcher now?",
                'Starsector Coop Installed',
                [System.Windows.Forms.MessageBoxButtons]::YesNo,
                [System.Windows.Forms.MessageBoxIcon]::Information)
            if ($launchNow -eq [System.Windows.Forms.DialogResult]::Yes) {
                Start-Process -FilePath $launcherCmd -WorkingDirectory $modRoot
            }
        }
    }
} finally {
    if (Test-Path -LiteralPath $tempZip) {
        Remove-Item -LiteralPath $tempZip -Force -ErrorAction SilentlyContinue
    }
}
