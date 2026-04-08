#!/usr/bin/env bash
# BIFROST ML Trainer — One-Click Launcher
# 不使用 set -e，手動處理每個步驟的錯誤

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BRML_DIR="$SCRIPT_DIR/brml"
VENV_DIR="$BRML_DIR/.venv"

echo ""
echo "  BIFROST ML — Block Reality AI Trainer"
echo "  ====================================="
echo ""

# ── Check Python ──
PY=""
for cmd in python3 python; do
    if command -v "$cmd" &>/dev/null; then
        VER=$("$cmd" -c "import sys; v=sys.version_info; print(v.major*100+v.minor)" 2>/dev/null)
        if [ "$VER" -ge 310 ] 2>/dev/null; then
            PY="$cmd"
            break
        fi
    fi
done

if [ -z "$PY" ]; then
    echo "  [ERROR] Python 3.10+ not found."
    echo "  Please install from https://python.org"
    echo ""
    read -p "  Press Enter to exit..." _
    exit 1
fi

PY_VER=$($PY --version 2>&1)
echo "  Python: $PY_VER"

# ── Create venv ──
if [ ! -f "$VENV_DIR/bin/activate" ]; then
    echo "  Creating virtual environment..."
    $PY -m venv "$VENV_DIR"
    if [ $? -ne 0 ]; then
        echo "  [ERROR] Failed to create venv. Try: $PY -m pip install virtualenv"
        read -p "  Press Enter to exit..." _
        exit 1
    fi
fi

source "$VENV_DIR/bin/activate"
echo "  Venv: $VENV_DIR"
echo ""

# ── Install deps (tolerant of failures) ──
echo "  [1/3] Upgrading pip..."
pip install --quiet --upgrade pip 2>/dev/null

echo "  [2/3] Installing core dependencies..."
# Order: numpy → jaxlib → jax → flax → optax (deps chain)
for dep in numpy scipy tqdm jaxlib jax flax optax orbax-checkpoint; do
    pip install --quiet "$dep" 2>/dev/null || echo "    ($dep failed, retrying...)" && \
    pip install --quiet "$dep" 2>/dev/null || echo "    ($dep still failing)"
done

# Install brml package
pip install --quiet -e "$BRML_DIR" --no-deps 2>/dev/null

# Check critical deps
MISSING=""
$PY -c "import jax" 2>/dev/null || MISSING="$MISSING jax"
$PY -c "import flax" 2>/dev/null || MISSING="$MISSING flax"
if [ -n "$MISSING" ]; then
    echo ""
    echo "  [WARNING] Missing:$MISSING"
    echo "  Training won't work. Try: pip install jax[cpu] flax"
    echo ""
fi

echo "  [3/3] Installing UI (Gradio)..."
pip install --quiet gradio 2>/dev/null
HAS_GRADIO=0
$PY -c "import gradio" 2>/dev/null && HAS_GRADIO=1

# Verify minimum deps
if ! $PY -c "import numpy, scipy" 2>/dev/null; then
    echo ""
    echo "  [ERROR] Core dependencies failed to install."
    echo "  Try manually: cd brml && pip install numpy scipy"
    read -p "  Press Enter to exit..." _
    exit 1
fi

echo ""
echo "  Ready! (Gradio: $([ $HAS_GRADIO -eq 1 ] && echo 'yes' || echo 'no — using terminal UI'))"
echo ""

# ── Launch ──
cd "$BRML_DIR"

if [ "$1" = "--tui" ]; then
    echo "  Starting terminal UI..."
    echo ""
    $PY -m brml.ui.tui
elif [ "$1" = "--auto" ]; then
    shift
    echo "  Starting auto-train..."
    echo ""
    $PY -m brml.pipeline.auto_train "$@"
elif [ "$HAS_GRADIO" -eq 1 ]; then
    echo "  Starting web UI → http://localhost:7860"
    echo "  (Ctrl+C to stop)"
    echo ""
    $PY -m brml.ui.web_ui
else
    echo "  Starting terminal UI..."
    echo ""
    $PY -m brml.ui.tui
fi

EXIT_CODE=$?
if [ $EXIT_CODE -ne 0 ]; then
    echo ""
    echo "  Exited with code $EXIT_CODE"
    read -p "  Press Enter to close..." _
fi
