#!/bin/bash
cd /mnt/c/Users/wmc02/Desktop/git/Block-Realityapi-Fast-design-1
source ml/.venv_wsl/bin/activate
python3 -m brnext.train.train_robust_ssgo --grid 12 --steps-s1 2000 --steps-s2 3000 --steps-s3 4000 --batch-size 4 --lr 5e-5 --hidden 64 --modes 6 --output ml/experiments/outputs/baseline_v5