#!/bin/bash
# A100 environment setup for Block Reality ML stack
# Run once on a fresh CUDA-enabled Linux node

set -e

echo "=== Installing core Python deps with CUDA 12 JAX ==="
pip install -U pip
pip install "jax[cuda12]>=0.4.20" jaxlib>=0.4.20 flax>=0.8.0 optax>=0.2.0
pip install numpy>=1.24 scipy>=1.11 pyyaml>=6.0 zarr>=2.16 h5py>=3.9
pip install onnx>=1.15 onnxruntime-gpu>=1.17 orbax-checkpoint>=0.4.0

echo "=== Installing monorepo packages in editable mode ==="
# Assumes run from repo root
pip install -e ml/brml
pip install -e ml/BR-NeXT
pip install -e ml/HYBR
pip install -e ml/reborn-ml

echo "=== Verifying GPU visibility ==="
python -c "import jax; print('JAX devices:', jax.devices()); print('JAX backend:', jax.default_backend())"

echo "=== A100 env ready ==="
