#!/usr/bin/env python3
"""A100-scale reborn-ml style trainer launcher.

Usage:
    export PYTHONPATH="ml/reborn-ml/src:ml/HYBR:ml/BR-NeXT:ml/brml:$PYTHONPATH"
    python ml/scripts/a100/train_reborn_style.py --config ml/configs/reborn_a100.yaml
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

root = Path(__file__).resolve().parent.parent.parent
for pkg in ["reborn-ml/src", "HYBR", "BR-NeXT", "brml"]:
    p = str(root / pkg)
    if p not in sys.path:
        sys.path.insert(0, p)

import yaml
from reborn_ml.config import TrainingConfig
from reborn_ml.training.style_trainer import RebornStyleTrainer


def load_yaml(path: str) -> dict:
    with open(path, "r", encoding="utf-8") as f:
        return yaml.safe_load(f)


def main():
    parser = argparse.ArgumentParser(description="Reborn Style Trainer A100")
    parser.add_argument("--config", type=str, default=str(root / "configs" / "reborn_a100.yaml"))
    args = parser.parse_args()

    cfg_dict = load_yaml(args.config)
    for key in ("checkpoint_dir", "cache_dir"):
        if key in cfg_dict and not Path(cfg_dict[key]).is_absolute():
            cfg_dict[key] = str(root / cfg_dict[key])

    cfg = TrainingConfig(**cfg_dict)
    trainer = RebornStyleTrainer(cfg)
    params, model, history = trainer.run()
    print(f"Training complete. Checkpoints: {cfg.checkpoint_dir}")


if __name__ == "__main__":
    main()
