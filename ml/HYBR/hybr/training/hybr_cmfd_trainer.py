"""HYBR CMFD Trainer — real-teacher training + materialized ONNX export."""
from __future__ import annotations

from dataclasses import dataclass

from hybr.training.meta_trainer import HYBRTrainer, HYBRConfig


@dataclass
class HYBRCMFDConfig(HYBRConfig):
    """Extended config with export options."""
    materialize_grid_size: int | None = None
    export_onnx: bool = True


class HYBRCMFDTrainer(HYBRTrainer):
    """HYBR trainer that automatically materializes and exports ONNX after training."""

    def __init__(self, cfg: HYBRCMFDConfig, **kwargs):
        super().__init__(cfg, **kwargs)
        self.cfg = cfg

    def run(self):
        params, model, history = super().run()
        if self.cfg.export_onnx:
            try:
                self.export(params, model, grid_size=self.cfg.materialize_grid_size)
            except Exception as e:
                self._log(f"  Export failed: {e}")
        return params, model, history
