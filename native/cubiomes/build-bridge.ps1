$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$src = Join-Path $PSScriptRoot "cubiomes_bridge.c"
$outDir = Join-Path $root ".."
$out = Join-Path $outDir "cubiomes_bridge.dll"

Write-Host "Building cubiomes bridge -> $out"
gcc -shared -O2 -std=c11 -Wall -Wextra -o $out $src

if (!(Test-Path $out)) {
    throw "Bridge build failed: $out not found"
}

Write-Host "Built $out"
