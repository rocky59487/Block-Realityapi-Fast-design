#!/bin/bash
# ═══════════════════════════════════════════════════════════════
# Block Reality ML — 完整訓練流程（一鍵到底）
#
# 用法：bash ml/scripts/a100/full_pipeline.sh [v100|a100]
#
# 流程：環境驗證 → Smoke Test → 正式訓練 → OOD 評估 → ONNX 驗證
# ═══════════════════════════════════════════════════════════════
set -e

TIER=${1:-v100}
REPO_ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
OUTPUT_BASE="$REPO_ROOT/ml/experiments/outputs"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
RUN_DIR="$OUTPUT_BASE/run_${TIER}_${TIMESTAMP}"

# Activate venv
if [ -f "$REPO_ROOT/ml/.venv/bin/activate" ]; then
    source "$REPO_ROOT/ml/.venv/bin/activate"
fi
export PYTHONPATH="$REPO_ROOT/ml/BR-NeXT:$REPO_ROOT/ml/HYBR:$REPO_ROOT/ml/brml:$PYTHONPATH"
cd "$REPO_ROOT"

echo "═══════════════════════════════════════════════════════════"
echo " Block Reality ML — Full Training Pipeline"
echo " Tier: $TIER"
echo " Output: $RUN_DIR"
echo " Started: $(date)"
echo "═══════════════════════════════════════════════════════════"

# ─── Step 1: GPU 驗證 ─────────────────────────────────────────
echo ""
echo "╔══ Step 1/6: GPU Verification ══╗"
python3 -c "
import jax
backend = jax.default_backend()
devices = jax.devices()
print(f'  JAX backend: {backend}')
print(f'  Devices: {len(devices)}')
for d in devices:
    print(f'    {d}')
if backend != 'gpu':
    print('  FATAL: JAX is not using GPU!')
    exit(1)
print('  GPU OK.')
"
nvidia-smi --query-gpu=name,memory.total,memory.free --format=csv,noheader 2>/dev/null || true

# ─── Step 2: SSGO Model Smoke Test ───────────────────────────
echo ""
echo "╔══ Step 2/6: Smoke Test (~5 min) ══╗"
SMOKE_DIR="$RUN_DIR/smoke"
python3 -m brnext.train.train_robust_ssgo \
    --grid 8 \
    --steps-s1 100 --steps-s2 100 --steps-s3 200 \
    --batch-size 2 \
    --output "$SMOKE_DIR"

# Check smoke produced an ONNX file
if [ ! -f "$SMOKE_DIR/ssgo_robust_medium.onnx" ]; then
    echo "  FATAL: Smoke test did not produce ONNX file!"
    echo "  Check logs in $SMOKE_DIR"
    exit 1
fi
echo "  Smoke test passed."

# ─── Step 3: Full Training ────────────────────────────────────
echo ""
echo "╔══ Step 3/6: Full Training ══╗"
TRAIN_DIR="$RUN_DIR/production"

if [ "$TIER" = "a100" ]; then
    echo "  A100 config: grid=16, batch=8, 20k steps (~3-4h)"
    python3 -m brnext.train.train_robust_ssgo \
        --grid 16 \
        --steps-s1 5000 --steps-s2 5000 --steps-s3 10000 \
        --batch-size 8 --lr 1e-3 \
        --hidden 48 --modes 8 \
        --dropout 0.05 --physics-residual --pr-weight 0.01 \
        --output "$TRAIN_DIR"
else
    echo "  V100 config: grid=16, batch=4, 14k steps (~6-8h)"
    python3 -m brnext.train.train_robust_ssgo \
        --grid 16 \
        --steps-s1 3000 --steps-s2 3000 --steps-s3 8000 \
        --batch-size 4 --lr 5e-4 \
        --hidden 48 --modes 8 \
        --dropout 0.05 --physics-residual --pr-weight 0.01 \
        --output "$TRAIN_DIR"
fi

if [ ! -f "$TRAIN_DIR/ssgo_robust_medium.onnx" ]; then
    echo "  FATAL: Training did not produce ONNX file!"
    exit 1
fi
echo "  Training complete."

# ─── Step 4: OOD Benchmark ────────────────────────────────────
echo ""
echo "╔══ Step 4/6: OOD Benchmark (~10 min) ══╗"
python3 -c "
from brnext.evaluation.ood_benchmark import run_ood_benchmark
from brnext.models.ssgo import SSGO
from flax import serialization
import jax, jax.numpy as jnp

model = SSGO(hidden=48, modes=8)
rng = jax.random.PRNGKey(0)
dummy = jnp.zeros((1, 16, 16, 16, 7))
params_template = model.init(rng, dummy)['params']

with open('$TRAIN_DIR/ssgo_robust_medium.msgpack', 'rb') as f:
    params = serialization.from_bytes(params_template, f.read())

@jax.jit
def apply_fn(p, x):
    pred = model.apply({'params': p}, x)
    return pred

summary = run_ood_benchmark(apply_fn, params, grid_size=16, n_samples=50, output_dir='$TRAIN_DIR')

# Quality gate
id_phi = summary['id_mae_phi']
ood_phi = summary['ood_mae_phi']
fail_acc = summary['id_failure_accuracy']
print(f'')
print(f'  Quality Gate:')
print(f'    ID MAE(phi):  {id_phi:.4f}  {\"PASS\" if id_phi < 0.15 else \"WARN\"}')
print(f'    OOD MAE(phi): {ood_phi:.4f}  {\"PASS\" if ood_phi < 0.25 else \"WARN\"}')
print(f'    Failure Acc:  {fail_acc:.2%}  {\"PASS\" if fail_acc > 0.70 else \"WARN\"}')
"

# ─── Step 5: ONNX Contract Validation ────────────────────────
echo ""
echo "╔══ Step 5/6: ONNX Contract Validation ══╗"
python3 -c "
import onnxruntime as ort
import numpy as np

path = '$TRAIN_DIR/ssgo_robust_medium.onnx'
sess = ort.InferenceSession(path, providers=['CPUExecutionProvider'])
inp = sess.get_inputs()[0]
out = sess.get_outputs()[0]
print(f'  Model: {path}')
print(f'  Input:  {inp.name} shape={inp.shape} dtype={inp.type}')
print(f'  Output: {out.name} shape={out.shape} dtype={out.type}')

# Contract: [1, L, L, L, 7] → [1, L, L, L, 10]
in_ch = inp.shape[4] if isinstance(inp.shape[4], int) else 7
assert in_ch == 7, f'Input channels mismatch: expected 7, got {in_ch}'

L = inp.shape[1] if isinstance(inp.shape[1], int) else 16
dummy = np.zeros((1, L, L, L, 7), dtype=np.float32)
result = sess.run(None, {inp.name: dummy})
assert result[0].shape == (1, L, L, L, 10), f'Output shape mismatch: {result[0].shape}'
assert np.isfinite(result[0]).all(), 'Output contains NaN/Inf!'

print(f'  Contract: [1,{L},{L},{L},7] → [1,{L},{L},{L},10] PASSED')
print(f'  File size: {__import__(\"os\").path.getsize(path) / 1024:.0f} KB')
"

# ─── Step 6: Copy to Deploy Directory ─────────────────────────
echo ""
echo "╔══ Step 6/6: Prepare Deployment ══╗"
DEPLOY_DIR="$RUN_DIR/deploy"
mkdir -p "$DEPLOY_DIR"
cp "$TRAIN_DIR/ssgo_robust_medium.onnx" "$DEPLOY_DIR/bifrost_surrogate.onnx"
cp "$TRAIN_DIR/ood_benchmark.json" "$DEPLOY_DIR/" 2>/dev/null || true
cp "$TRAIN_DIR/history.json" "$DEPLOY_DIR/" 2>/dev/null || true

echo "  Deployment files ready in: $DEPLOY_DIR/"
echo "  - bifrost_surrogate.onnx (copy to <minecraft>/config/blockreality/models/)"
ls -la "$DEPLOY_DIR/"

# ─── Done ─────────────────────────────────────────────────────
echo ""
echo "═══════════════════════════════════════════════════════════"
echo " PIPELINE COMPLETE"
echo " Started: $TIMESTAMP"
echo " Finished: $(date)"
echo " Output: $RUN_DIR"
echo ""
echo " Next steps:"
echo "   1. Download: scp <vm>:$DEPLOY_DIR/bifrost_surrogate.onnx ~/Desktop/"
echo "   2. Place in: <minecraft>/config/blockreality/models/bifrost_surrogate.onnx"
echo "   3. Build mod: cd 'Block Reality' && ./gradlew mergedJar"
echo "   4. Copy mpd.jar to <minecraft>/mods/"
echo "   5. Launch Minecraft with Forge 1.20.1-47.4.13"
echo "═══════════════════════════════════════════════════════════"
