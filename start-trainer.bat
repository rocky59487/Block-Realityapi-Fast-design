@echo off
chcp 65001 >nul 2>&1
title BIFROST ML Trainer

echo.
echo   BIFROST ML — Block Reality AI Trainer
echo   =====================================
echo.

set "SCRIPT_DIR=%~dp0"
set "BRML_DIR=%SCRIPT_DIR%brml"
set "VENV_DIR=%BRML_DIR%\.venv"

:: ── Check Python ──
where python >nul 2>&1
if %errorlevel% neq 0 (
    echo   [ERROR] Python not found.
    echo   Install from https://python.org
    echo   Make sure "Add Python to PATH" is checked.
    echo.
    pause
    exit /b 1
)

for /f "tokens=*" %%i in ('python -c "import sys; print(sys.version)" 2^>nul') do (
    echo   Python: %%i
)

:: ── Create venv ──
if not exist "%VENV_DIR%\Scripts\activate.bat" (
    echo   Creating virtual environment...
    python -m venv "%VENV_DIR%"
    if %errorlevel% neq 0 (
        echo   [ERROR] Failed to create venv.
        pause
        exit /b 1
    )
)

call "%VENV_DIR%\Scripts\activate.bat"
echo   Venv: %VENV_DIR%
echo.

:: ── Install deps ──
echo   [1/3] Upgrading pip...
pip install --quiet --upgrade pip 2>nul

echo   [2/3] Installing core dependencies...
pip install --quiet numpy 2>nul
pip install --quiet scipy 2>nul
pip install --quiet jax 2>nul
pip install --quiet flax 2>nul
pip install --quiet optax 2>nul
pip install --quiet tqdm 2>nul
pip install --quiet -e "%BRML_DIR%" --no-deps 2>nul

echo   [3/3] Installing UI (Gradio)...
pip install --quiet gradio 2>nul

:: Check Gradio
set HAS_GRADIO=0
python -c "import gradio" 2>nul && set HAS_GRADIO=1

:: Verify core
python -c "import numpy, scipy" 2>nul
if %errorlevel% neq 0 (
    echo.
    echo   [ERROR] Core dependencies failed.
    echo   Try: cd brml ^&^& pip install numpy scipy
    pause
    exit /b 1
)

echo.
if "%HAS_GRADIO%"=="1" (
    echo   Ready! ^(Gradio: yes^)
) else (
    echo   Ready! ^(Gradio: no — using terminal UI^)
)
echo.

:: ── Launch ──
cd /d "%BRML_DIR%"

if "%1"=="--tui" (
    echo   Starting terminal UI...
    echo.
    python -m brml.ui.tui
    goto :done
)

if "%1"=="--auto" (
    echo   Starting auto-train...
    echo.
    python -m brml.pipeline.auto_train %2 %3 %4 %5 %6 %7 %8 %9
    goto :done
)

if "%HAS_GRADIO%"=="1" (
    echo   Starting web UI at http://localhost:7860
    echo   Press Ctrl+C to stop
    echo.
    python -m brml.ui.web_ui
) else (
    echo   Starting terminal UI...
    echo.
    python -m brml.ui.tui
)

:done
echo.
if %errorlevel% neq 0 (
    echo   Exited with error code %errorlevel%
)
pause
