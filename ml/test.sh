#!/bin/bash
cd /mnt/c/Users/wmc02/Desktop/git/Block-Realityapi-Fast-design-1
source ml/.venv_wsl/bin/activate
python3 -c "import jax; print(jax.default_backend())"