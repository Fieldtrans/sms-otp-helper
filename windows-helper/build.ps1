$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$outDir = Join-Path $root "bin"
$source = Join-Path $root "SmsOtpHelper.Win.cs"
$exe = Join-Path $outDir "SmsOtpHelper.Win.exe"
$csc = Join-Path $env:WINDIR "Microsoft.NET\Framework64\v4.0.30319\csc.exe"

if (-not (Test-Path $csc)) {
    throw "找不到 csc.exe：$csc"
}

New-Item -ItemType Directory -Force -Path $outDir | Out-Null

& $csc `
    /nologo `
    /target:winexe `
    /platform:x64 `
    /optimize+ `
    /codepage:65001 `
    /out:$exe `
    /reference:System.dll `
    /reference:System.Core.dll `
    /reference:System.Drawing.dll `
    /reference:System.Windows.Forms.dll `
    $source

if ($LASTEXITCODE -ne 0) {
    throw "csc failed: $LASTEXITCODE"
}

Write-Host "Built: $exe"
