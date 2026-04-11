"""Lightweight MLflow wrapper for BR-NeXT experiment tracking."""
from __future__ import annotations

from pathlib import Path
from typing import Any


class ExperimentTracker:
    """Auto-fallback experiment tracker (MLflow if available, else no-op)."""

    def __init__(self, experiment_name: str = "brnext_cmfd", tracking_uri: str | None = None):
        self._available = False
        self._mlflow = None
        try:
            import mlflow
            if tracking_uri:
                mlflow.set_tracking_uri(tracking_uri)
            mlflow.set_experiment(experiment_name)
            self._mlflow = mlflow
            self._available = True
        except ImportError:
            pass

    def start_run(self, run_name: str | None = None):
        if self._available and self._mlflow:
            self._mlflow.start_run(run_name=run_name)

    def log_params(self, params: dict[str, Any]):
        if not self._available or not self._mlflow:
            return
        for k, v in params.items():
            try:
                self._mlflow.log_param(k, v)
            except Exception:
                pass

    def log_metrics(self, metrics: dict[str, Any], step: int):
        if not self._available or not self._mlflow:
            return
        for k, v in metrics.items():
            try:
                self._mlflow.log_metric(k, float(v), step=step)
            except Exception:
                pass

    def log_artifact(self, local_path: str | Path):
        if not self._available or not self._mlflow:
            return
        try:
            self._mlflow.log_artifact(str(local_path))
        except Exception:
            pass

    def end_run(self):
        if self._available and self._mlflow:
            self._mlflow.end_run()
