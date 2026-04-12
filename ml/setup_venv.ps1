# Setup unified ML venv inside ml/
$ErrorActionPreference = "Stop"
$repo = "c:\Users\wmc02\Desktop\git\Block-Realityapi-Fast-design"
Set-Location $repo

$legacyVenv = "$repo\reborn-ml\.venv"
$newVenv    = "$repo\ml\.venv"

if (-not (Test-Path $legacyVenv)) {
    Write-Host "Legacy venv not found at $legacyVenv" -ForegroundColor Red
    exit 1
}

Write-Host "Creating new venv at $newVenv ..."
python -m venv $newVenv

Write-Host "Copying installed packages from legacy venv (this may take a minute)..."
$source = "$legacyVenv\Lib\site-packages"
$dest   = "$newVenv\Lib\site-packages"

# Remove empty default site-packages in new venv
if (Test-Path $dest) {
    Remove-Item $dest -Recurse -Force
}

# Use robocopy for reliability with many files
robocopy $source $dest /E /MT:8 /XD __pycache__ | Out-Null

# Update pyvenv.cfg to reflect new path
$pyvenvCfg = "$newVenv\pyvenv.cfg"
$pythonExe = "$newVenv\Scripts\python.exe"
(Get-Content $pyvenvCfg) -replace [regex]::Escape($legacyVenv), $newVenv | Set-Content $pyvenvCfg

Write-Host "Venv ready at $newVenv" -ForegroundColor Green
Write-Host "Test it: $newVenv\Scripts\python.exe -c `"import jax, flax, optax, onnxruntime`""
