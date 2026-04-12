#!/usr/bin/env python3
"""A100-scale BR-NeXT CMFD trainer launcher.

Usage (from repo root on A100 machine):
    export PYTHONPATH="ml/BR-NeXT:ml/HYBR:ml/brml:$PYTHONPATH"
    python ml/scripts/a100/train_brnext_cmfd.py --config ml/configs/brnext_a100.yaml
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

# Bootstrap
root = Path(__file__).resolve().parent.parent.parent
for pkg in ["BR-NeXT", "HYBR", "brml"]:
    p = str(root / pkg)
    if p not in sys.path:
        sys.path.insert(0, p)

import yaml
from brnext.pipeline.cmfd_trainer import CMFDTrainer, CMFDConfig


def load_yaml(path: str) -> dict:
    with open(path, "r", encoding="utf-8") as f:
        return yaml.safe_load(f)


def main():
    parser = argparse.ArgumentParser(description="BR-NeXT CMFD A100 Trainer")
    parser.add_argument("--config", type=str, default=str(root / "configs" / "brnext_a100.yaml"))
    args = parser.parse_args()

    cfg_dict = load_yaml(args.config)
    # Resolve relative paths against repo root
    for key in ("output_dir", "cache_dir"):
        if key in cfg_dict and not Path(cfg_dict[key]).is_absolute():
            cfg_dict[key] = str(root / cfg_dict[key])

    cfg = CMFDConfig(**cfg_dict)
    trainer = CMFDTrainer(cfg)
    result = trainer.run()
    if result is None:
        print("Training was stopped early.")
        return
    params, model, history = result
    print(f"Training complete. Output: {cfg.output_dir}")


if __name__ == "__main__":
    main()
