"""Async data loader for overlapping CPU data generation with GPU training.

Provides zero-bubble FEM/PFSF dataset generation via multiprocessing pool.
Adds optional DuckDB+Zarr caching so solved samples survive across runs.
"""
from __future__ import annotations

import hashlib
import itertools
import multiprocessing as mp
from typing import Callable, Iterator

import numpy as np


def compute_sample_id(struct) -> str:
    """Deterministic short hash for a VoxelStructure."""
    data = b""
    for key in [
        "occupancy", "anchors", "E_field", "nu_field",
        "density_field", "rcomp_field", "rtens_field", "mat_ids",
    ]:
        arr = getattr(struct, key)
        data += arr.tobytes()
    return hashlib.sha256(data).hexdigest()[:16]


class AsyncBuffer:
    """Producer-consumer buffer with async worker pool.

    Usage:
        with AsyncBuffer(generator, worker_fn, n_workers=4) as buf:
            buf.prefetch(min_buffer=20)
            for step in range(steps):
                samples = buf.sample(rng, n=batch_size)
                # train...
                buf.poll()   # refill if space
    """

    def __init__(
        self,
        generator: Iterator,
        worker_fn: Callable,
        n_workers: int = 4,
        chunksize: int = 2,
        registry=None,
        zarr_store=None,
        config_hash: str | None = None,
        grid_size: int | None = None,
        target_samples: int | None = None,
    ):
        self.generator = generator
        self.worker_fn = worker_fn
        self.n_workers = n_workers
        self.chunksize = chunksize
        self.buffer: list = []
        self._pool = None
        self._iterator = None
        self._exhausted = False

        self.registry = registry
        self.zarr_store = zarr_store
        self.config_hash = config_hash
        self.grid_size = grid_size
        self.target_samples = target_samples

    def __enter__(self):
        # 1. Load existing cached samples
        if self.registry and self.zarr_store and self.config_hash and self.grid_size:
            sample_ids = self.registry.fetch_sample_ids(self.config_hash, self.grid_size)
            loaded = 0
            for sid in sample_ids:
                result = self.zarr_store.read_sample(
                    self.config_hash, self.grid_size, sid
                )
                if result is not None:
                    # Normalize PFSF-only cached samples from (struct, None, phi) to (struct, phi)
                    if len(result) == 3 and result[1] is None:
                        self.buffer.append((result[0], result[2]))
                    else:
                        self.buffer.append(result)
                    loaded += 1
            if loaded:
                print(f"  AsyncBuffer loaded {loaded} cached samples from Zarr")

        # 2. Decide whether we need the worker pool
        # 2. Decide whether we need the worker pool
        if self.target_samples is not None:
            need = self.target_samples - len(self.buffer)
            if need <= 0:
                self._exhausted = True
                return self
        else:
            need = 1000  # Default prefetch target if unlimited
            
        # Slice generator to avoid excessive CPU work when cache covers most needs
        max_attempts = max(need * 3, self.chunksize * self.n_workers)
        sliced_gen = itertools.islice(self.generator, max_attempts)

        # Use 'spawn' to avoid JAX deadlocks with multithreading
        import sys
        ctx_name = "spawn"
        ctx = mp.get_context(ctx_name)
        self._pool = ctx.Pool(self.n_workers)
        self._iterator = self._pool.imap_unordered(
            self.worker_fn, sliced_gen, chunksize=self.chunksize
        )
        return self

    def __exit__(self, *args):
        if self._pool:
            self._pool.terminate()
            self._pool.join()
            self._pool = None

    def _maybe_cache_result(self, result):
        if not (self.registry and self.zarr_store and self.config_hash and self.grid_size):
            return
        struct = result[0]
        fem = result[1] if len(result) == 3 else None
        phi = result[-1]
        sample_id = compute_sample_id(struct)
        if self.zarr_store.has_sample(self.config_hash, self.grid_size, sample_id):
            return
        zarr_path = self.zarr_store.write_sample(
            self.config_hash, self.grid_size, sample_id, struct, fem, phi
        )
        # Metadata
        n_solid = int(struct.occupancy.sum())
        try:
            from brnext.pipeline.structure_gen import compute_complexity
            irregularity = float(compute_complexity(struct))
        except Exception:
            irregularity = 0.0
        fem_converged = fem.converged if fem is not None else None
        fem_max_vm = float(np.max(fem.von_mises)) if fem is not None else None
        pfsf_max_phi = float(np.max(phi)) if phi is not None else None
        self.registry.register(
            sample_id=sample_id,
            config_hash=self.config_hash,
            grid_size=self.grid_size,
            style=getattr(struct, "style", "unknown"),
            n_solid=n_solid,
            irregularity=irregularity,
            fem_converged=fem_converged,
            fem_max_vm=fem_max_vm,
            pfsf_max_phi=pfsf_max_phi,
            zarr_path=zarr_path,
        )

    def poll(self, max_size: int = 200, timeout: float = 0.0):
        """Pull newly ready items from the pool up to max_size."""
        if self._exhausted:
            return
        deadline = None
        if timeout > 0:
            import time
            deadline = time.time() + timeout
        while len(self.buffer) < max_size:
            if deadline and time.time() > deadline:
                break
            try:
                result = next(self._iterator)
                if result is not None:
                    self.buffer.append(result)
                    self._maybe_cache_result(result)
            except StopIteration:
                self._exhausted = True
                break

    def prefetch(self, min_buffer: int = 20, timeout: float | None = None):
        """Block until at least min_buffer items are available."""
        import time
        t0 = time.time()
        while len(self.buffer) < min_buffer and not self._exhausted:
            self.poll(max_size=min_buffer, timeout=1.0)
            if timeout is not None and (time.time() - t0) > timeout:
                break

    def sample(self, rng: np.random.Generator, n: int = 1):
        """Randomly sample n items from the buffer (with replacement)."""
        if len(self.buffer) == 0:
            raise IndexError("AsyncBuffer is empty")
        idx = rng.integers(len(self.buffer), size=n)
        return [self.buffer[i] for i in idx]

    def __len__(self):
        return len(self.buffer)


def pfsf_worker(struct):
    """Picklable PFSF worker for AsyncBuffer."""
    from brnext.teachers.pfsf_teacher import solve_pfsf_phi
    if int(struct.occupancy.sum()) < 3:
        return None
    phi = solve_pfsf_phi(struct.occupancy, struct.E_field, struct.density_field)
    return (struct, phi)


def fem_worker(struct):
    """Picklable FEM worker for AsyncBuffer."""
    from brnext.teachers.fem_teacher import solve_fem
    from brnext.teachers.pfsf_teacher import solve_pfsf_phi
    if int(struct.occupancy.sum()) < 3:
        return None
    fem = solve_fem(
        struct.occupancy, struct.anchors,
        struct.E_field, struct.nu_field, struct.density_field,
    )
    if not fem.converged:
        return None
    phi = solve_pfsf_phi(struct.occupancy, struct.E_field, struct.density_field)
    return (struct, fem, phi)


def structure_generator(grid_size: int, rng_seed: int, styles: list[str], max_attempts: int):
    """Yield structures for async workers. Must be picklable."""
    from brnext.pipeline.structure_gen import generate_structure
    np_rng = np.random.default_rng(rng_seed)
    for _ in range(max_attempts):
        style = styles[int(np_rng.integers(len(styles)))]
        struct = generate_structure(grid_size, np_rng, style)
        yield struct


def augment_worker(args):
    """Picklable worker that generates + optionally augments a structure."""
    from brnext.pipeline.structure_gen import generate_structure, augment_structure
    grid_size, rng_seed, style, do_aug = args
    rng = np.random.default_rng(rng_seed)
    struct = generate_structure(grid_size, rng, style)
    if do_aug:
        struct = augment_structure(struct, rng)
    return struct


def augmented_pfsf_worker(args):
    """Generate + augment + solve PFSF phi."""
    struct = augment_worker(args)
    return pfsf_worker(struct)


def augmented_fem_worker(args):
    """Generate + augment + solve FEM + PFSF phi."""
    struct = augment_worker(args)
    return fem_worker(struct)


class CurriculumSampler:
    """Sample styles according to curriculum complexity schedule."""

    def __init__(self, styles: list[str], grid_size: int, rng: np.random.Generator):
        self.styles = styles
        self.grid_size = grid_size
        self.rng = rng
        self._complexity_cache = {}

    def _get_complexity(self, style: str) -> float:
        from brnext.pipeline.structure_gen import generate_structure, compute_complexity
        if style not in self._complexity_cache:
            samples = [generate_structure(self.grid_size, self.rng, style) for _ in range(5)]
            self._complexity_cache[style] = float(np.mean([compute_complexity(s) for s in samples]))
        return self._complexity_cache[style]

    def sample_styles(self, n: int, progress: float) -> list[str]:
        """progress in [0, 1]: 0 = easiest, 1 = full distribution."""
        complexities = {s: self._get_complexity(s) for s in self.styles}
        max_c = max(complexities.values()) + 1e-8

        probs = []
        for s in self.styles:
            c = complexities[s] / max_c
            # Curriculum mask: easy styles always available, hard styles unlock with progress
            allowed = c <= (0.3 + 0.7 * progress)
            if allowed:
                # Weight inversely by complexity for early curriculum
                w = 1.0 - 0.8 * c * (1.0 - progress)
                probs.append(max(w, 0.1))
            else:
                probs.append(0.0)

        probs = np.array(probs, dtype=float)
        if probs.sum() == 0:
            probs = np.ones_like(probs)
        probs /= probs.sum()
        return [self.styles[i] for i in self.rng.choice(len(self.styles), size=n, p=probs)]


class MixupCollator:
    """Batch-level mixup for structure-target pairs."""

    def __init__(self, alpha: float = 0.4, prob: float = 0.5,
                 rng: np.random.Generator | None = None):
        self.alpha = alpha
        self.prob = prob
        self.rng = rng or np.random.default_rng()

    def maybe_mix(self, batch_items: list):
        """batch_items: list of tuples, e.g. [(struct, phi), ...].
        Returns mixed batch_items. Only mixes when targets are numpy arrays.
        """
        if len(batch_items) < 2 or self.rng.random() > self.prob:
            return batch_items
        # Only apply mixup if all targets are array-like
        for item in batch_items:
            for j in range(1, len(item)):
                if not hasattr(item[j], "__array__"):
                    return batch_items
        from brnext.pipeline.structure_gen import mixup_structures
        mixed = []
        # Pair adjacent items
        for i in range(0, len(batch_items) - 1, 2):
            a = batch_items[i]
            b = batch_items[i + 1]
            lam = self.rng.beta(self.alpha, self.alpha)
            lam = float(np.clip(lam, 0.1, 0.9))
            s_mix = mixup_structures(a[0], b[0], self.rng)
            # Linearly mix targets
            rest_mix = []
            for j in range(1, len(a)):
                rest_mix.append(lam * np.array(a[j]) + (1.0 - lam) * np.array(b[j]))
            mixed.append((s_mix, *rest_mix))
        if len(batch_items) % 2 == 1:
            mixed.append(batch_items[-1])
        return mixed
