"""JAX utilities for BR-NeXT."""
from __future__ import annotations

import os
from typing import Iterator

import jax
import jax.numpy as jnp


def ensure_cpu_backend():
    """Force JAX to use CPU (for FEM worker processes)."""
    os.environ["JAX_PLATFORM_NAME"] = "cpu"


def async_prefetch(
    dataset_iter: Iterator[tuple],
    device,
    buffer_size: int = 2,
):
    """Yield batches that are already on `device` via async prefetch.

    Usage:
        for batch in async_prefetch(data_loader, jax.devices()[0]):
            loss = train_step(state, batch)
    """
    import queue

    q: queue.Queue = queue.Queue(maxsize=buffer_size)

    def _prefetch():
        for item in dataset_iter:
            # Move each array in the batch to device asynchronously
            if isinstance(item, tuple):
                placed = tuple(jax.device_put(x, device) for x in item)
            else:
                placed = jax.device_put(item, device)
            q.put(placed)
        q.put(None)  # sentinel

    import threading
    t = threading.Thread(target=_prefetch, daemon=True)
    t.start()

    while True:
        item = q.get()
        if item is None:
            break
        yield item
