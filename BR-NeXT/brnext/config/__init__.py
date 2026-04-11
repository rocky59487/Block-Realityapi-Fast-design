"""Configuration helpers for BR-NeXT."""
from __future__ import annotations

from pathlib import Path
from typing import Any

import yaml


_DEFAULTS: dict[str, Any] | None = None
_CONFIG_PATH = Path(__file__).with_name("normalization.yaml")


def _load_defaults() -> dict[str, Any]:
    global _DEFAULTS
    if _DEFAULTS is None:
        if _CONFIG_PATH.exists():
            with open(_CONFIG_PATH, "r", encoding="utf-8") as f:
                _DEFAULTS = yaml.safe_load(f)
        else:
            _DEFAULTS = {}
    return _DEFAULTS


def load_norm_constants() -> dict[str, float]:
    """Load normalization constants shared with Java ONNX runtime."""
    cfg = _load_defaults()
    return {
        "E_SCALE": float(cfg.get("E_SCALE", 2.0e11)),
        "RHO_SCALE": float(cfg.get("RHO_SCALE", 7850.0)),
        "RC_SCALE": float(cfg.get("RC_SCALE", 250.0)),
        "RT_SCALE": float(cfg.get("RT_SCALE", 500.0)),
        "INPUT_CHANNELS": int(cfg.get("INPUT_CHANNELS", 6)),
        "OUTPUT_CHANNELS": int(cfg.get("OUTPUT_CHANNELS", 10)),
    }
