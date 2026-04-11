#!/usr/bin/env bash
set -euo pipefail

# BR-NeXT full CMFD pipeline launcher

echo "╔════════════════════════════════════════════════════════╗"
echo "║  BR-NeXT — Full CMFD Pipeline                          ║"
echo "║  LEA → PFSF → FEM → ONNX                               ║"
echo "╚════════════════════════════════════════════════════════╝"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."

# Default to tiny config for safety; override with CONFIG=...
CONFIG="${CONFIG:-configs/ssgo_tiny.yaml}"

echo "Using config: $CONFIG"
python -m brnext.pipeline.cmfd_trainer \
    --grid 12 \
    --steps-s1 5000 \
    --steps-s2 5000 \
    --steps-s3 10000 \
    --output brnext_output

echo ""
echo "Pipeline complete. Check brnext_output/ for:"
echo "  - brnext_ssgo.onnx   (Java-compatible surrogate)"
echo "  - checkpoint_...     (Flax training checkpoints)"
