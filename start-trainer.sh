#!/usr/bin/env bash
# ╔═══════════════════════════════════════════════════╗
# ║  BIFROST ML Trainer — One-Click Launcher (Linux)  ║
# ╚═══════════════════════════════════════════════════╝
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BRML_DIR="$SCRIPT_DIR/brml"
VENV_DIR="$BRML_DIR/.venv"

echo "╔══════════════════════════════════════════╗"
echo "║  BIFROST ML — Block Reality AI Trainer   ║"
echo "╚══════════════════════════════════════════╝"
echo ""

# ── Check Python ──
if command -v python3 &>/dev/null; then
    PY=python3
elif command -v python &>/dev/null; then
    PY=python
else
    echo "ERROR: Python 3.10+ not found. Install from https://python.org"
    exit 1
fi

PY_VER=$($PY -c "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')")
echo "  Python: $PY ($PY_VER)"

# ── Create venv if needed ──
if [ ! -d "$VENV_DIR" ]; then
    echo "  Creating virtual environment..."
    $PY -m venv "$VENV_DIR"
fi
source "$VENV_DIR/bin/activate"
echo "  Venv:   $VENV_DIR"

# ── Install/upgrade brml ──
echo "  Installing dependencies (first run may take a few minutes)..."
pip install --quiet --upgrade pip
pip install --quiet -e "$BRML_DIR" 2>&1 | tail -1 || {
    echo "  NOTE: Full JAX install failed (expected without GPU)."
    echo "  Installing CPU-only fallback..."
    pip install --quiet numpy scipy flax optax jax 2>&1 | tail -1
    pip install --quiet -e "$BRML_DIR" --no-deps 2>&1 | tail -1
}

# ── Try install Gradio for web UI ──
pip install --quiet gradio 2>&1 | tail -1 && HAS_GRADIO=1 || HAS_GRADIO=0

echo ""
echo "  ✓ Ready"
echo ""

# ── Launch ──
if [ "$1" = "--tui" ]; then
    echo "  Launching terminal UI..."
    python -m brml.ui.tui
elif [ "$1" = "--auto" ]; then
    shift
    echo "  Launching auto-train (no UI)..."
    python -m brml.pipeline.auto_train "$@"
elif [ "$HAS_GRADIO" = "1" ]; then
    echo "  Launching web UI at http://localhost:7860"
    echo "  (Press Ctrl+C to stop)"
    echo ""
    python -m brml.ui.web_ui
else
    echo "  Gradio not available, launching terminal UI..."
    python -m brml.ui.tui
fi
