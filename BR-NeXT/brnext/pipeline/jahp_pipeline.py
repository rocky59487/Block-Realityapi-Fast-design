"""JAX Async Hybrid Pipeline (JAHP).

Overlaps FEM data generation with GPU training via:
  - multiprocessing FEM worker pool
  - async host-to-device prefetch ring buffer
"""
from __future__ import annotations

import multiprocessing as mp
import queue
import threading
import time
from dataclasses import dataclass
from typing import Callable, Iterator

import numpy as np


@dataclass
class FEMSample:
    struct: Any
    fem_result: Any
    phi: np.ndarray


class FEMWorker:
    """Callable target for mp.Pool — must be picklable."""

    def __call__(self, struct):
        from brnext.teachers.fem_teacher import solve_fem
        from brnext.teachers.pfsf_teacher import solve_pfsf_phi
        fem = solve_fem(
            struct.occupancy, struct.anchors,
            struct.E_field, struct.nu_field, struct.density_field,
        )
        if not fem.converged:
            return None
        phi = solve_pfsf_phi(struct.occupancy, struct.E_field, struct.density_field)
        return (struct, fem, phi)


class JAHPPipeline:
    """Producer-consumer pipeline with FEM workers + async prefetch."""

    def __init__(self, generator: Iterator, n_workers: int = 4,
                 prefetch_depth: int = 2):
        self.generator = generator
        self.n_workers = n_workers
        self.prefetch_depth = prefetch_depth
        self._pool = None

    def __enter__(self):
        ctx = mp.get_context("spawn")
        self._pool = ctx.Pool(self.n_workers)
        return self

    def __exit__(self, *args):
        if self._pool:
            self._pool.terminate()
            self._pool.join()
            self._pool = None

    def _fem_generator(self):
        """Submit structures to FEM pool and yield solved samples."""
        # Async map with small chunksize
        for result in self._pool.imap_unordered(FEMWorker(), self.generator, chunksize=2):
            if result is not None:
                yield result

    def _prefetch_ring(self, device):
        """Yield batches already on `device`."""
        import jax
        q: queue.Queue = queue.Queue(maxsize=self.prefetch_depth)

        def _fill():
            for item in self._fem_generator():
                # item = (struct, fem, phi)
                # We just pass the raw tuple; device_put happens in consumer
                q.put(item)
            q.put(None)

        t = threading.Thread(target=_fill, daemon=True)
        t.start()

        while True:
            item = q.get()
            if item is None:
                break
            yield item

    def iter_batches(self, device, batch_size: int = 4):
        """Yield prefetched batches ready for training."""
        batch = []
        for item in self._prefetch_ring(device):
            batch.append(item)
            if len(batch) == batch_size:
                yield batch
                batch = []
        if batch:
            yield batch
