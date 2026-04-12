#!/usr/bin/env python3
"""A100-scale HYBR meta-trainer launcher.

Usage:
    export PYTHONPATH="ml/BR-NeXT:ml/HYBR:ml/brml:$PYTHONPATH"
    python ml/scripts/a100/train_hybr_meta.py --config ml/configs/hybr_a100.yaml
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

root = Path(__file__).resolve().parent.parent.parent
for pkg in ["BR-NeXT", "HYBR", "brml"]:
    p = str(root / pkg)
    if p not in sys.path:
        sys.path.insert(0, p)

import yaml
from hybr.training.meta_trainer import HYBRTrainer, HYBRConfig


def load_yaml(path: str) -> dict:
    with open(path, "r", encoding="utf-8") as f:
        return yaml.safe_load(f)


def main():
    parser = argparse.ArgumentParser(description="HYBR Meta-Trainer A100")
    parser.add_argument("--config", type=str, default=str(root / "configs" / "hybr_a100.yaml"))
    args = parser.parse_args()

    cfg_dict = load_yaml(args.config)
    for key in ("output_dir", "cache_dir"):
        if key in cfg_dict and not Path(cfg_dict[key]).is_absolute():
            cfg_dict[key] = str(root / cfg_dict[key])

    cfg = HYBRConfig(**cfg_dict)
    trainer = HYBRTrainer(cfg)
    params, model, history = trainer.run()

    # Export materialized ONNX for Minecraft deployment
    onnx_path = trainer.export(params, model, grid_size=cfg.grid_size)
    print(f"Training complete. ONNX exported to: {onnx_path}")


if __name__ == "__main__":
    main()
