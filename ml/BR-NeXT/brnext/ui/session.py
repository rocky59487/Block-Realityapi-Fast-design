"""Training session backend for BR-NeXT UI."""
from __future__ import annotations

import time
import threading
from dataclasses import dataclass, field
from typing import Callable

from brnext.pipeline.cmfd_trainer import CMFDConfig, CMFDTrainer


@dataclass
class TrainParams:
    grid_size: int = 12
    stage1_samples: int = 10000
    stage2_samples: int = 2000
    stage3_samples: int = 200
    stage1_steps: int = 5000
    stage2_steps: int = 5000
    stage3_steps: int = 10000
    lr: float = 1e-3
    hidden: int = 48
    modes: int = 6
    seed: int = 42
    output_dir: str = "brnext_output"

    def to_cmfd_config(self) -> CMFDConfig:
        return CMFDConfig(
            grid_size=self.grid_size,
            stage1_samples=self.stage1_samples,
            stage2_samples=self.stage2_samples,
            stage3_samples=self.stage3_samples,
            stage1_steps=self.stage1_steps,
            stage2_steps=self.stage2_steps,
            stage3_steps=self.stage3_steps,
            lr=self.lr,
            hidden=self.hidden,
            modes=self.modes,
            seed=self.seed,
            output_dir=self.output_dir,
        )


@dataclass
class TrainState:
    status: str = "idle"          # idle | LEA | PFSF | FEM | exporting | done | error
    current_step: int = 0
    total_steps: int = 0
    stage: str = ""
    loss: float = 0.0
    loss_history: list = field(default_factory=list)
    message: str = ""
    elapsed_sec: float = 0.0
    gpu_detected: bool = False
    samples_queued: int = 0


class TrainingSession:
    """Live training session wrapper around CMFDTrainer."""

    def __init__(self, params: TrainParams,
                 on_update: Callable[[TrainState], None] | None = None):
        self.params = params
        self.on_update = on_update or (lambda s: None)
        self.state = TrainState()
        self._trainer: CMFDTrainer | None = None
        self._thread: threading.Thread | None = None
        self._start_time = 0.0
        self._stop_flag = False

    def _update(self, **kwargs):
        for k, v in kwargs.items():
            setattr(self.state, k, v)
        self.on_update(self.state)

    def start(self):
        if self._thread and self._thread.is_alive():
            return
        self._stop_flag = False
        self._start_time = time.time()
        self.state.loss_history = []
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def stop(self):
        self._stop_flag = True
        if self._trainer:
            self._trainer.stop()
        if self._thread:
            self._thread.join(timeout=30)

    def get_state(self) -> TrainState:
        self.state.elapsed_sec = time.time() - self._start_time
        return self.state

    def _run(self):
        try:
            self._update(status="running", stage="LEA", current_step=0,
                         total_steps=self.params.stage1_steps, message="Starting LEA pre-training...")

            def on_log(msg: str):
                self._update(message=msg)

            def on_step(stage: str, step: int, loss: float):
                self._update(stage=stage, current_step=step, loss=loss)
                self.state.loss_history.append({"step": len(self.state.loss_history), "loss": loss})
                # Estimate total steps for progress bar
                if stage == "LEA":
                    self._update(total_steps=self.params.stage1_steps)
                elif stage == "PFSF":
                    self._update(total_steps=self.params.stage2_steps)
                elif stage == "FEM":
                    self._update(total_steps=self.params.stage3_steps)

            self._trainer = CMFDTrainer(
                self.params.to_cmfd_config(),
                on_log=on_log,
                on_step=on_step,
            )
            result = self._trainer.run()

            if self._stop_flag:
                self._update(status="stopped", message="Training stopped by user.")
            elif result is not None:
                self._update(status="done", stage="Export", message="Training complete. ONNX exported.")
            else:
                self._update(status="error", message="Training returned None (possible early stop).")
        except Exception as e:
            self._update(status="error", message=f"Error: {e}")
