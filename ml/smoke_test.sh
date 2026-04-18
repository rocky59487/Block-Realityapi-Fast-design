#!/bin/bash
cd /mnt/c/Users/wmc02/Desktop/git/Block-Realityapi-Fast-design-1
source ml/.venv_wsl/bin/activate
python3 -m brnext.train.train_robust_ssgo --grid 8 --steps-s1 200 --steps-s2 200 --steps-s3 400 --batch-size 2 --output ml/experiments/outputs/smoke