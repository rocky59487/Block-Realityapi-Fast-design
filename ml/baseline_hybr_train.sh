#!/bin/bash
cd /mnt/c/Users/wmc02/Desktop/git/Block-Realityapi-Fast-design-1
source ml/.venv_wsl/bin/activate
export PYTHONPATH="ml/BR-NeXT:ml/HYBR:ml/brml:$PYTHONPATH"
python3 ml/scripts/a100/train_hybr_meta.py --config ml/configs/hybr_a100.yaml > ml/baseline_hybr_train.log 2>&1
