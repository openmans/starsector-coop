<#
.SYNOPSIS
Compiles the native Windows GUI bootstrapper/installer "Starsector Coop Launcher.exe".

.DESCRIPTION
Uses Windows' built-in .NET Framework 4.x C# compiler (csc.exe) so the resulting ~25 KB .exe runs
on any stock Windows 10/11 PC with zero extra runtimes, downloads the latest Starsector Coop
release from GitHub when needed, configures vmparams + enabled_mods.json, and launches the Java
Coop Launcher without any console window.
#>
[CmdletBinding()]
param(
    [string] $OutExe
)

$ErrorActionPreference = 'Stop'
$modRoot = Split-Path -Parent $PSScriptRoot
$srcFile = Join-Path $modRoot 'src\launcher-exe\StarsectorCoopLauncher.cs'
if (-not (Test-Path -LiteralPath $srcFile -PathType Leaf)) {
    throw "Missing source file: $srcFile"
}
if ([string]::IsNullOrWhiteSpace($OutExe)) {
    $OutExe = Join-Path $modRoot 'Starsector Coop Launcher.exe'
}

$csc = @(
    'C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe',
    'C:\Windows\Microsoft.NET\Framework\v4.0.30319\csc.exe'
) | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1

if (-not $csc) {
    throw 'Could not find .NET Framework csc.exe.'
}

# Generate a crisp Starsector-styled cyan + amber icon (.ico) for the .exe
Add-Type -AssemblyName System.Drawing
$icoPath = Join-Path ([System.IO.Path]::GetTempPath()) ('starsector-coop-' + [guid]::NewGuid().ToString('N') + '.ico')
try {
    $bmp = New-Object System.Drawing.Bitmap 64, 64
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.Clear([System.Drawing.Color]::Transparent)

    # Dark Starsector badge circle
    $bgBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 11, 22, 34))
    $g.FillEllipse($bgBrush, 2, 2, 60, 60)

    # Cyan ring
    $cyanPen = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(255, 70, 222, 255)), 4.0
    $g.DrawEllipse($cyanPen, 4, 4, 56, 56)

    # Two interlocked co-op chevrons / fleet arrows (cyan + amber)
    $cyanBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 70, 222, 255))
    $amberBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 255, 158, 44))
    $ship1 = [System.Drawing.Point[]]@(
        (New-Object System.Drawing.Point 32, 12),
        (New-Object System.Drawing.Point 45, 36),
        (New-Object System.Drawing.Point 32, 29),
        (New-Object System.Drawing.Point 19, 36)
    )
    $ship2 = [System.Drawing.Point[]]@(
        (New-Object System.Drawing.Point 32, 30),
        (New-Object System.Drawing.Point 47, 50),
        (New-Object System.Drawing.Point 32, 43),
        (New-Object System.Drawing.Point 17, 50)
    )
    $g.FillPolygon($cyanBrush, $ship1)
    $g.FillPolygon($amberBrush, $ship2)
    $g.Dispose()

    # Write PNG-in-ICO container (supported natively by Windows Vista/7/10/11)
    $pngStream = New-Object System.IO.MemoryStream
    $bmp.Save($pngStream, [System.Drawing.Imaging.ImageFormat]::Png)
    $pngBytes = $pngStream.ToArray()
    $bmp.Dispose()

    $fs = [System.IO.File]::Create($icoPath)
    $bw = New-Object System.IO.BinaryWriter $fs
    $bw.Write([UInt16]0)        # reserved
    $bw.Write([UInt16]1)        # type = icon
    $bw.Write([UInt16]1)        # count = 1
    $bw.Write([byte]64)         # width
    $bw.Write([byte]64)         # height
    $bw.Write([byte]0)          # colors
    $bw.Write([byte]0)          # reserved
    $bw.Write([UInt16]1)        # planes
    $bw.Write([UInt16]32)       # bpp
    $bw.Write([UInt32]$pngBytes.Length)
    $bw.Write([UInt32]22)       # offset
    $bw.Write($pngBytes)
    $bw.Dispose()
    $fs.Dispose()

    & $csc /nologo /target:winexe /optimize+ /platform:anycpu `
        "/out:$OutExe" `
        "/win32icon:$icoPath" `
        /r:System.dll `
        /r:System.Drawing.dll `
        /r:System.Windows.Forms.dll `
        /r:System.IO.Compression.dll `
        /r:System.IO.Compression.FileSystem.dll `
        /r:System.Web.Extensions.dll `
        $srcFile

    if ($LASTEXITCODE -ne 0) {
        throw "csc.exe failed with exit code $LASTEXITCODE"
    }

    $info = Get-Item -LiteralPath $OutExe
    Write-Host "Built $($info.FullName) ($($info.Length) bytes)"
}
finally {
    if (Test-Path -LiteralPath $icoPath) {
        Remove-Item -LiteralPath $icoPath -Force -ErrorAction SilentlyContinue
    }
}
