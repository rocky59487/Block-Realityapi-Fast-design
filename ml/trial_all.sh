#!/bin/bash
set -e

# Setup environment
export XLA_PYTHON_CLIENT_PREALLOCATE=false
export PYTHONUNBUFFERED=1
PYTHON="/mnt/c/Users/wmc02/Desktop/git/Block-Realityapi-Fast-design-1/ml/.venv_wsl/bin/python3"
BASE_DIR="/mnt/c/Users/wmc02/Desktop/git/Block-Realityapi-Fast-design-1/ml"

mkdir -p $BASE_DIR/data/models
export PYTHONPATH="$BASE_DIR/brml:$BASE_DIR/BR-NeXT:$BASE_DIR/HYBR:$PYTHONPATH"

echo "==== Starting Unified Block Reality ML Trial ===="

# 1. Fluid Training (FNO)
echo "Step 1: Training Fluid Model..."
$PYTHON -m brml.train.train_fnofluid \
    --data-dir $BASE_DIR/data/fluid \
    --steps 1000 --lr 0.003 --eval-every 200 \
    --out-dir $BASE_DIR/experiments/outputs/trial_fluid
cp $BASE_DIR/experiments/outputs/trial_fluid/bifrost_fluid.onnx $BASE_DIR/data/models/ || true

# 2. Physics Training (Robust SSGO)
echo "Step 2: Training Physics Model (Robust SSGO)..."
$PYTHON -m brnext.train.train_robust_ssgo \
    --grid 12 --steps-s1 500 --steps-s2 500 --steps-s3 1000 \
    --batch-size 2 --lr 5e-4 --hidden 32 --modes 6 \
    --output $BASE_DIR/experiments/outputs/trial_physics
cp $BASE_DIR/experiments/outputs/trial_physics/ssgo_robust_medium.onnx $BASE_DIR/data/models/ || true

echo "==== All Tasks Completed Successfully ===="
