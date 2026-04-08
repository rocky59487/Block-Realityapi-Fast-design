@echo off
chcp 65001 >nul 2>&1
:: ╔═══════════════════════════════════════════════════╗
:: ║  BIFROST ML Trainer — One-Click Launcher (Windows)║
:: ╚═══════════════════════════════════════════════════╝

echo ╔══════════════════════════════════════════╗
echo ║  BIFROST ML — Block Reality AI Trainer   ║
echo ╚══════════════════════════════════════════╝
echo.

set "SCRIPT_DIR=%~dp0"
set "BRML_DIR=%SCRIPT_DIR%brml"
set "VENV_DIR=%BRML_DIR%\.venv"

:: ── Check Python ──
where python >nul 2>&1
if %errorlevel% neq 0 (
    echo ERROR: Python not found. Install from https://python.org
    pause
    exit /b 1
)
for /f "tokens=*" %%i in ('python -c "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')"') do set PY_VER=%%i
echo   Python: %PY_VER%

:: ── Create venv if needed ──
if not exist "%VENV_DIR%\Scripts\activate.bat" (
    echo   Creating virtual environment...
    python -m venv "%VENV_DIR%"
)
call "%VENV_DIR%\Scripts\activate.bat"
echo   Venv:   %VENV_DIR%

:: ── Install dependencies ──
echo   Installing dependencies...
pip install --quiet --upgrade pip 2>nul
pip install --quiet -e "%BRML_DIR%" 2>nul || (
    echo   Installing CPU-only fallback...
    pip install --quiet numpy scipy flax optax jax 2>nul
    pip install --quiet -e "%BRML_DIR%" --no-deps 2>nul
)

:: ── Try Gradio ──
pip install --quiet gradio 2>nul && set HAS_GRADIO=1 || set HAS_GRADIO=0

echo.
echo   Ready!
echo.

:: ── Launch ──
if "%1"=="--tui" (
    echo   Launching terminal UI...
    python -m brml.ui.tui
) else if "%1"=="--auto" (
    echo   Launching auto-train...
    python -m brml.pipeline.auto_train %2 %3 %4 %5 %6 %7 %8 %9
) else if "%HAS_GRADIO%"=="1" (
    echo   Launching web UI at http://localhost:7860
    echo   Press Ctrl+C to stop
    echo.
    python -m brml.ui.web_ui
) else (
    echo   Launching terminal UI...
    python -m brml.ui.tui
)

pause
