"""Checkpoint utilities."""
from __future__ import annotations

from pathlib import Path

import orbax.checkpoint as ocp


def save_checkpoint(params, step: int, output_dir: str | Path):
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    checkpointer = ocp.PyTreeCheckpointer()
    path = output_dir / f"checkpoint_{step}"
    checkpointer.save(str(path), params)
    return path


def load_checkpoint(checkpoint_dir: str | Path):
    checkpointer = ocp.PyTreeCheckpointer()
    return checkpointer.restore(str(checkpoint_dir))
