#!/bin/bash
# ═══════════════════════════════════════════════════════════════
# Block Reality ML — Training Launcher
# Usage: bash ml/scripts/a100/launch.sh [brnext|hybr|reborn|smoke]
# ═══════════════════════════════════════════════════════════════
set -e

TARGET=${1:-brnext}
REPO_ROOT=$(cd "$(dirname "$0")/../../.." && pwd)

# Activate venv if exists
if [ -f "$REPO_ROOT/ml/.venv/bin/activate" ]; then
    source "$REPO_ROOT/ml/.venv/bin/activate"
fi

export PYTHONPATH="$REPO_ROOT/ml/BR-NeXT:$REPO_ROOT/ml/HYBR:$REPO_ROOT/ml/brml:$REPO_ROOT/ml/reborn-ml/src:$PYTHONPATH"
cd "$REPO_ROOT"

echo "═══════════════════════════════════════════════"
echo " Block Reality ML — Training Launcher"
echo " Target: $TARGET"
echo " Time: $(date)"
echo "═══════════════════════════════════════════════"

# Show GPU info
python3 -c "import jax; print(f'JAX backend: {jax.default_backend()}, devices: {len(jax.devices())}')" 2>/dev/null || true

case $TARGET in
  smoke)
    echo ""
    echo "Running smoke test (8³ grid, 100 steps each stage)..."
    python3 -m brnext.train.train_robust_ssgo \
        --grid 8 \
        --steps-s1 100 --steps-s2 100 --steps-s3 200 \
        --batch-size 2 \
        --output ml/experiments/outputs/brnext/smoke_test
    echo ""
    echo "Smoke test complete! Check ml/experiments/outputs/brnext/smoke_test/"
    ;;
  brnext)
    echo ""
    echo "Launching BR-NeXT CMFD A100 training..."
    python3 ml/scripts/a100/train_brnext_cmfd.py --config ml/configs/brnext_a100.yaml
    echo ""
    echo "Training complete. Models in: ml/experiments/outputs/brnext/a100_cmfd/"
    echo ""
    echo "To deploy: copy the .onnx file to your Minecraft instance:"
    echo "  cp ml/experiments/outputs/brnext/a100_cmfd/brnext_ssgo.onnx \\"
    echo "     <minecraft>/config/blockreality/models/bifrost_surrogate.onnx"
    ;;
  hybr)
    echo ""
    echo "Launching HYBR Meta-Trainer A100 training..."
    python3 ml/scripts/a100/train_hybr_meta.py --config ml/configs/hybr_a100.yaml
    echo ""
    echo "Training complete. Models in: ml/experiments/outputs/hybr/a100_meta/"
    ;;
  reborn)
    echo ""
    echo "Launching Reborn Style Trainer A100 training..."
    python3 ml/scripts/a100/train_reborn_style.py --config ml/configs/reborn_a100.yaml
    echo ""
    echo "Training complete. Models in: ml/experiments/outputs/reborn/a100_style/"
    ;;
  benchmark)
    echo ""
    echo "Running A100 performance benchmark..."
    python3 -m reborn.experiments.exp_008_a100_benchmark --grid 8
    ;;
  *)
    echo "Unknown target: $TARGET"
    echo ""
    echo "Available targets:"
    echo "  smoke     — Quick validation (8³, ~5 min on GPU)"
    echo "  brnext    — Production SSGO training (16³, ~3-4h on A100)"
    echo "  hybr      — HyperNetwork meta-learning (16³, ~4-6h on A100)"
    echo "  reborn    — Style-conditioned training (16³, ~3h on A100)"
    echo "  benchmark — GPU performance measurement"
    exit 1
    ;;
esac

echo ""
echo "═══ Done at $(date) ═══"
