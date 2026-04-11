"""Simple throughput / bubble profiling utilities."""
from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Callable


@dataclass
class ThroughputMonitor:
    """Monitor training throughput (steps/sec)."""
    name: str = "train"
    _start_time: float = field(default_factory=time.time)
    _steps: int = 0

    def step(self, n: int = 1):
        self._steps += n

    def report(self) -> str:
        elapsed = time.time() - self._start_time
        rate = self._steps / elapsed if elapsed > 0 else 0.0
        return f"[{self.name}] {self._steps} steps in {elapsed:.1f}s = {rate:.2f} it/s"
