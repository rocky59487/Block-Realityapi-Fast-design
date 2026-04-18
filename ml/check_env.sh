#!/bin/bash
cd /mnt/c/Users/wmc02/Desktop/git/Block-Realityapi-Fast-design-1
source ml/.venv_wsl/bin/activate
python -c "import jax; print(f'Backend: {jax.default_backend()}, Devices: {jax.devices()}')"
nvidia-smi --query-gpu=name,memory.total,memory.free --format=csv,noheader
