# BIFROST ML Trainer — One-Click Launcher (PowerShell)
# Usage: Right-click → Run with PowerShell
#   or: powershell -ExecutionPolicy Bypass -File start-trainer.ps1

$ErrorActionPreference = "SilentlyContinue"
$Host.UI.RawUI.WindowTitle = "BIFROST ML Trainer"

Write-Host ""
Write-Host "  BIFROST ML - Block Reality AI Trainer" -ForegroundColor Cyan
Write-Host "  =====================================" -ForegroundColor Cyan
Write-Host ""

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$BrmlDir = Join-Path $ScriptDir "brml"
$VenvDir = Join-Path $BrmlDir ".venv"

# ── Check Python ──
$PyCmd = $null
foreach ($cmd in @("python", "python3", "py")) {
    $found = Get-Command $cmd -ErrorAction SilentlyContinue
    if ($found) {
        $ver = & $cmd -c "import sys; print(sys.version_info.major * 100 + sys.version_info.minor)" 2>$null
        if ([int]$ver -ge 310) {
            $PyCmd = $cmd
            break
        }
    }
}

if (-not $PyCmd) {
    Write-Host "  [ERROR] Python 3.10+ not found." -ForegroundColor Red
    Write-Host "  Install from https://python.org"
    Write-Host "  Check 'Add Python to PATH' during install."
    Write-Host ""
    Read-Host "  Press Enter to exit"
    exit 1
}

$PyVer = & $PyCmd --version 2>&1
Write-Host "  Python: $PyVer"

# ── Create venv ──
$ActivateScript = Join-Path $VenvDir "Scripts\Activate.ps1"
if (-not (Test-Path $ActivateScript)) {
    Write-Host "  Creating virtual environment..."
    & $PyCmd -m venv $VenvDir
    if (-not (Test-Path $ActivateScript)) {
        Write-Host "  [ERROR] Failed to create venv." -ForegroundColor Red
        Read-Host "  Press Enter to exit"
        exit 1
    }
}

& $ActivateScript
Write-Host "  Venv: $VenvDir"
Write-Host ""

# ── Install deps ──
Write-Host "  [1/3] Upgrading pip..."
& pip install --quiet --upgrade pip 2>$null

Write-Host "  [2/3] Installing core dependencies..."
$deps = @("numpy", "scipy", "jax", "flax", "optax", "tqdm")
foreach ($dep in $deps) {
    & pip install --quiet $dep 2>$null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "    ($dep install issue - continuing)" -ForegroundColor Yellow
    }
}
& pip install --quiet -e $BrmlDir --no-deps 2>$null

Write-Host "  [3/3] Installing UI (Gradio)..."
& pip install --quiet gradio 2>$null

$HasGradio = $false
& $PyCmd -c "import gradio" 2>$null
if ($LASTEXITCODE -eq 0) { $HasGradio = $true }

# Verify core
& $PyCmd -c "import numpy, scipy" 2>$null
if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "  [ERROR] Core dependencies failed to install." -ForegroundColor Red
    Write-Host "  Try manually: cd brml; pip install numpy scipy"
    Read-Host "  Press Enter to exit"
    exit 1
}

$GradioStatus = if ($HasGradio) { "yes" } else { "no - using terminal UI" }
Write-Host ""
Write-Host "  Ready! (Gradio: $GradioStatus)" -ForegroundColor Green
Write-Host ""

# ── Launch ──
Set-Location $BrmlDir

if ($args -contains "--tui") {
    Write-Host "  Starting terminal UI..."
    Write-Host ""
    & $PyCmd -m brml.ui.tui
}
elseif ($args -contains "--auto") {
    Write-Host "  Starting auto-train..."
    Write-Host ""
    $passArgs = $args | Where-Object { $_ -ne "--auto" }
    & $PyCmd -m brml.pipeline.auto_train @passArgs
}
elseif ($HasGradio) {
    Write-Host "  Starting web UI -> http://localhost:7860"
    Write-Host "  (Ctrl+C to stop)"
    Write-Host ""
    & $PyCmd -m brml.ui.web_ui
}
else {
    Write-Host "  Starting terminal UI..."
    Write-Host ""
    & $PyCmd -m brml.ui.tui
}

Write-Host ""
if ($LASTEXITCODE -ne 0) {
    Write-Host "  Exited with code $LASTEXITCODE" -ForegroundColor Yellow
}
Read-Host "  Press Enter to close"
