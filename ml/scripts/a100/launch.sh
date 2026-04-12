#!/bin/bash
# Master launcher for A100 training jobs
# Usage: ./ml/scripts/a100/launch.sh [brnext|hybr|reborn]

set -e

TARGET=${1:-brnext}
REPO_ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
export PYTHONPATH="$REPO_ROOT/ml/BR-NeXT:$REPO_ROOT/ml/HYBR:$REPO_ROOT/ml/brml:$REPO_ROOT/ml/reborn-ml/src:$PYTHONPATH"

cd "$REPO_ROOT"

case $TARGET in
  brnext)
    echo "Launching BR-NeXT CMFD A100 training..."
    python ml/scripts/a100/train_brnext_cmfd.py --config ml/configs/brnext_a100.yaml
    ;;
  hybr)
    echo "Launching HYBR Meta-Trainer A100 training..."
    python ml/scripts/a100/train_hybr_meta.py --config ml/configs/hybr_a100.yaml
    ;;
  reborn)
    echo "Launching Reborn Style Trainer A100 training..."
    python ml/scripts/a100/train_reborn_style.py --config ml/configs/reborn_a100.yaml
    ;;
  *)
    echo "Unknown target: $TARGET"
    echo "Usage: $0 [brnext|hybr|reborn]"
    exit 1
    ;;
esac
