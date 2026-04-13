#!/bin/bash
# ═══════════════════════════════════════════════════════════════
# Block Reality ML — GPU Training Environment Setup
# Compatible with: V100 / A100 / L4 / H100 on Google Cloud
# ═══════════════════════════════════════════════════════════════
#
# Usage (from repo root):
#   bash ml/scripts/a100/setup_env.sh
#
# Prerequisites:
#   - NVIDIA driver installed (nvidia-smi should work)
#   - CUDA 12.x toolkit
#   - Python 3.10+
#
set -e

echo "═══════════════════════════════════════════════"
echo " Block Reality ML — Environment Setup"
echo "═══════════════════════════════════════════════"

# ── 1. System check ──
echo ""
echo "--- System Check ---"
python3 --version || { echo "ERROR: Python 3 not found"; exit 1; }
nvidia-smi --query-gpu=name,memory.total --format=csv,noheader || { echo "WARNING: nvidia-smi failed, GPU may not be available"; }

# ── 2. Create virtual environment ──
VENV_DIR="ml/.venv"
if [ ! -d "$VENV_DIR" ]; then
    echo ""
    echo "--- Creating virtual environment ---"
    python3 -m venv "$VENV_DIR"
fi
source "$VENV_DIR/bin/activate"

# ── 3. Core dependencies ──
echo ""
echo "--- Installing core dependencies ---"
pip install -U pip setuptools wheel

# JAX with CUDA 12 support (auto-detects GPU)
pip install "jax[cuda12]>=0.4.20" "jaxlib>=0.4.20"

# ML framework
pip install "flax>=0.8.0" "optax>=0.2.0" "orbax-checkpoint>=0.4.0"

# Numerics
pip install "numpy>=1.24" "scipy>=1.11"

# Data & config
pip install "pyyaml>=6.0" "zarr>=2.16" "h5py>=3.9"

# ONNX export & validation
pip install "onnx>=1.15" "onnxruntime-gpu>=1.17"

# Optional but useful
pip install "tqdm>=4.66" "duckdb>=0.10" 2>/dev/null || true

# ── 4. Install monorepo packages in editable mode ──
echo ""
echo "--- Installing BR ML packages (editable) ---"
pip install -e ml/brml
pip install -e ml/BR-NeXT
pip install -e ml/HYBR
pip install -e ml/reborn-ml 2>/dev/null || echo "  (reborn-ml skipped — optional)"

# ── 5. Verify GPU ──
echo ""
echo "--- Verifying JAX GPU access ---"
python3 -c "
import jax
devices = jax.devices()
backend = jax.default_backend()
print(f'  JAX backend: {backend}')
print(f'  Devices ({len(devices)}):')
for d in devices:
    print(f'    {d}')
if backend != 'gpu':
    print('  WARNING: JAX is NOT using GPU! Training will be very slow.')
    print('  Try: pip install jax[cuda12] --upgrade')
"

# ── 6. Quick smoke test ──
echo ""
echo "--- Smoke test: SSGO model init ---"
python3 -c "
import jax
import jax.numpy as jnp
from brnext.models.ssgo import SSGO
model = SSGO(hidden=16, modes=4)
rng = jax.random.PRNGKey(0)
dummy = jnp.zeros((1, 4, 4, 4, 7))
variables = model.init(rng, dummy)
out = model.apply(variables, dummy)
assert out.shape == (1, 4, 4, 4, 10), f'Unexpected shape: {out.shape}'
print(f'  SSGO init OK — output shape: {out.shape}')
print(f'  Params: {sum(p.size for p in jax.tree_util.tree_leaves(variables[\"params\"])):,}')
"

echo ""
echo "═══════════════════════════════════════════════"
echo " Setup complete! Ready to train."
echo ""
echo " Quick start:"
echo "   bash ml/scripts/a100/launch.sh brnext"
echo ""
echo " Or manual:"
echo "   source ml/.venv/bin/activate"
echo "   python -m brnext.train.train_robust_ssgo --grid 8"
echo "═══════════════════════════════════════════════"
