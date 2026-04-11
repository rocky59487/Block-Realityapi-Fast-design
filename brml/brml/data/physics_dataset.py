"""
Physics dataset for PFSF surrogate model training.

Each sample is an (input, target) pair:
  input:  source[L³], conductivity[6,L³], type[L³], rcomp[L³]
  target: phi_steady[L³] (converged potential field)

Data can come from:
  1. Blueprint files with simulated stress fields
  2. Synthetic random structures with PFSF solver ground truth
  3. Recorded GPU readbacks (binary dumps)
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Iterator

import numpy as np

from .blueprint_loader import BlueprintLoader, BlueprintSample


@dataclass
class PhysicsBatch:
    """Mini-batch for training."""
    source: np.ndarray        # [B, Lx, Ly, Lz]
    conductivity: np.ndarray  # [B, 6, Lx, Ly, Lz]
    voxel_type: np.ndarray    # [B, Lx, Ly, Lz]
    rcomp: np.ndarray         # [B, Lx, Ly, Lz]
    phi_target: np.ndarray    # [B, Lx, Ly, Lz]


class PhysicsDataset:
    """Dataset of PFSF physics simulation input/output pairs."""

    def __init__(self, max_grid: int = 32):
        """
        Args:
            max_grid: Maximum grid dimension. Samples larger than this
                      are either cropped or skipped.
        """
        self.max_grid = max_grid
        self.samples: list[BlueprintSample] = []

    def load_from_blueprints(self, blueprint_dir: str | Path) -> int:
        """Load from .brblp files. Returns count of loaded samples."""
        loader = BlueprintLoader(blueprint_dir)
        count = 0
        for path in loader.list_files():
            sample = loader.load(path)
            if sample is None:
                continue
            # Skip oversized
            lx, ly, lz = sample.shape
            if max(lx, ly, lz) > self.max_grid:
                continue
            # Need at least phi_steady or stress as target
            if sample.phi_steady is None and sample.stress is None:
                continue
            self.samples.append(sample)
            count += 1
        return count

    def load_from_binary(self, data_dir: str | Path) -> int:
        """Load from binary dumps (GPU readback exports).

        Expected format per file:
          header: Lx(i32), Ly(i32), Lz(i32)
          source: float32[N]
          conductivity: float32[6N]
          type: uint8[N]
          rcomp: float32[N]
          phi_steady: float32[N]
        """
        data_dir = Path(data_dir)
        count = 0
        for path in sorted(data_dir.glob("*.bin")):
            sample = self._load_binary(path)
            if sample is not None:
                self.samples.append(sample)
                count += 1
        return count

    def generate_synthetic(self, count: int = 1000, grid_size: int = 16,
                           seed: int = 42) -> int:
        """Generate synthetic training data with analytical solutions.

        Creates random block structures and computes approximate ground truth
        using a simple CPU Jacobi solver.
        """
        rng = np.random.default_rng(seed)
        generated = 0
        L = grid_size

        for _ in range(count):
            # Random occupancy (30-70% fill)
            fill_rate = rng.uniform(0.3, 0.7)
            mask = rng.random((L, L, L)) < fill_rate

            # Ground layer is always anchor
            mask[:, 0, :] = True

            voxel_type = np.zeros((L, L, L), dtype=np.uint8)
            voxel_type[mask] = 1
            voxel_type[:, 0, :] = 2  # anchor

            # Random material selection
            density = rng.choice([2400.0, 7850.0, 500.0], size=(L, L, L)).astype(np.float32)
            density[~mask] = 0

            source = density * 9.81 * 1.0
            source[~mask] = 0

            rcomp = np.where(mask, rng.uniform(15.0, 250.0, (L, L, L)).astype(np.float32), 0)
            rtens = rcomp * 0.1

            # Isotropic conductivity
            conductivity = np.zeros((6, L, L, L), dtype=np.float32)
            cond_val = rng.uniform(1e3, 1e5, (L, L, L)).astype(np.float32)
            cond_val[~mask] = 0
            for d in range(6):
                conductivity[d] = cond_val

            # Simple CPU Jacobi solve (approximate ground truth)
            phi = self._cpu_jacobi(source, conductivity, voxel_type, n_iters=200)

            self.samples.append(BlueprintSample(
                shape=(L, L, L),
                source=source,
                conductivity=conductivity,
                voxel_type=voxel_type,
                rcomp=rcomp,
                rtens=rtens,
                stress=None,
                phi_steady=phi,
            ))
            generated += 1

        return generated

    def batches(self, batch_size: int, pad_to: int | None = None,
                shuffle: bool = True, seed: int = 0) -> Iterator[PhysicsBatch]:
        """Yield padded mini-batches.
        Uses concurrent.futures for prefetching to prevent GPU starvation.
        """
        import concurrent.futures
        rng = np.random.default_rng(seed)
        indices = np.arange(len(self.samples))
        if shuffle:
            rng.shuffle(indices)

        L = pad_to or self.max_grid

        def build_batch(start_idx):
            batch_idx = indices[start_idx:start_idx + batch_size]
            B = len(batch_idx)

            src = np.zeros((B, L, L, L), dtype=np.float32)
            cond = np.zeros((B, 6, L, L, L), dtype=np.float32)
            typ = np.zeros((B, L, L, L), dtype=np.uint8)
            rc = np.zeros((B, L, L, L), dtype=np.float32)
            phi = np.zeros((B, L, L, L), dtype=np.float32)

            for i, idx in enumerate(batch_idx):
                s = self.samples[idx]
                lx, ly, lz = s.shape
                src[i, :lx, :ly, :lz] = s.source
                cond[i, :, :lx, :ly, :lz] = s.conductivity
                typ[i, :lx, :ly, :lz] = s.voxel_type
                rc[i, :lx, :ly, :lz] = s.rcomp
                target = s.phi_steady if s.phi_steady is not None else s.stress
                if target is not None:
                    phi[i, :lx, :ly, :lz] = target

            return PhysicsBatch(src, cond, typ, rc, phi)

        starts = list(range(0, len(indices), batch_size))

        # Bounded prefetching to avoid system OOM
        prefetch_count = 2
        with concurrent.futures.ThreadPoolExecutor(max_workers=prefetch_count) as executor:
            futures = []

            # Submit initial futures
            for i in range(min(prefetch_count, len(starts))):
                futures.append(executor.submit(build_batch, starts[i]))

            # Yield and replenish
            for i in range(len(starts)):
                yield futures[0].result()
                futures.pop(0)

                # submit next future if there are more
                next_idx = i + prefetch_count
                if next_idx < len(starts):
                    futures.append(executor.submit(build_batch, starts[next_idx]))

    def __len__(self) -> int:
        return len(self.samples)

    @staticmethod
    def _cpu_jacobi(source: np.ndarray, conductivity: np.ndarray,
                    voxel_type: np.ndarray, n_iters: int = 200) -> np.ndarray:
        """Vectorized Jacobi smoother using np.roll — replaces triple-nested Python loops."""
        phi = np.zeros_like(source)
        
        solid = voxel_type == 1
        anchor = voxel_type == 2
        
        c0, c1, c2, c3, c4, c5 = conductivity
        
        # Mask out boundaries to prevent wrap-around
        m0 = np.ones_like(source); m0[0, :, :] = 0
        m1 = np.ones_like(source); m1[-1, :, :] = 0
        m2 = np.ones_like(source); m2[:, 0, :] = 0
        m3 = np.ones_like(source); m3[:, -1, :] = 0
        m4 = np.ones_like(source); m4[:, :, 0] = 0
        m5 = np.ones_like(source); m5[:, :, -1] = 0

        c0 = c0 * m0
        c1 = c1 * m1
        c2 = c2 * m2
        c3 = c3 * m3
        c4 = c4 * m4
        c5 = c5 * m5

        den = c0 + c1 + c2 + c3 + c4 + c5 + 1e-10

        for _ in range(n_iters):
            num = source + (
                c0 * np.roll(phi, +1, axis=0) +
                c1 * np.roll(phi, -1, axis=0) +
                c2 * np.roll(phi, +1, axis=1) +
                c3 * np.roll(phi, -1, axis=1) +
                c4 * np.roll(phi, +1, axis=2) +
                c5 * np.roll(phi, -1, axis=2)
            )
            phi = num / den
            phi[anchor] = 0.0
            phi[~solid] = 0.0

        return phi

    @staticmethod
    def _load_binary(path: Path) -> BlueprintSample | None:
        """Load a single binary dump file."""
        try:
            data = path.read_bytes()
            offset = 0

            lx = int.from_bytes(data[offset:offset + 4], "little"); offset += 4
            ly = int.from_bytes(data[offset:offset + 4], "little"); offset += 4
            lz = int.from_bytes(data[offset:offset + 4], "little"); offset += 4
            n = lx * ly * lz

            source = np.frombuffer(data, dtype=np.float32, count=n, offset=offset)
            offset += n * 4
            source = source.reshape(lx, ly, lz)

            conductivity = np.frombuffer(data, dtype=np.float32, count=6 * n, offset=offset)
            offset += 6 * n * 4
            conductivity = conductivity.reshape(6, lx, ly, lz)

            voxel_type = np.frombuffer(data, dtype=np.uint8, count=n, offset=offset)
            offset += n
            voxel_type = voxel_type.reshape(lx, ly, lz)

            rcomp = np.frombuffer(data, dtype=np.float32, count=n, offset=offset)
            offset += n * 4
            rcomp = rcomp.reshape(lx, ly, lz)

            phi_steady = np.frombuffer(data, dtype=np.float32, count=n, offset=offset)
            phi_steady = phi_steady.reshape(lx, ly, lz)

            return BlueprintSample(
                shape=(lx, ly, lz),
                source=source, conductivity=conductivity,
                voxel_type=voxel_type, rcomp=rcomp,
                rtens=np.zeros_like(rcomp),
                stress=None, phi_steady=phi_steady,
            )
        except Exception:
            return None
