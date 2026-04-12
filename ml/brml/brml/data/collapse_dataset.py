"""
Collapse prediction dataset.

Each sample is a structure graph with:
  - Node features: material properties, position, connectivity degree
  - Edge features: conductivity between adjacent voxels
  - Labels: failure type per node (0=OK, 1-4=failure modes)
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import numpy as np


@dataclass
class CollapseSample:
    """Single structure graph for collapse prediction."""

    island_id: int

    # Node features: [num_nodes, node_feat_dim]
    #   [density, rcomp, rtens, youngs, poisson, is_anchor, height, degree]
    node_features: np.ndarray  # float32

    # Edge connectivity: [2, num_edges] (symmetric)
    edge_index: np.ndarray  # int32

    # Edge features: [num_edges, edge_feat_dim]
    #   [conductivity, direction_x, direction_y, direction_z]
    edge_features: np.ndarray  # float32

    # Labels: failure type per node
    #   0=OK, 1=cantilever, 2=crushing, 3=no_support, 4=tension
    failure_labels: np.ndarray  # int32

    # Collapse sequence order (if available)
    #   -1 = no collapse, 0 = first to fail, 1 = second, ...
    collapse_order: np.ndarray | None  # int32


class CollapseDataset:
    """Dataset of structure graphs with collapse labels."""

    def __init__(self):
        self.samples: list[CollapseSample] = []

    def load_from_journal(self, journal_dir: str | Path) -> int:
        """Load from CollapseJournal binary exports.

        Expected format per file (.brcolj):
          header: island_id(i32), num_blocks(i32), num_events(i32)
          blocks[]: x(i32), y(i32), z(i32), material_id(i16),
                    density(f32), rcomp(f32), rtens(f32), is_anchor(u8)
          events[]: block_index(i32), failure_type(u8), tick(i32)
        """
        journal_dir = Path(journal_dir)
        count = 0
        for path in sorted(journal_dir.glob("*.brcolj")):
            sample = self._load_journal(path)
            if sample is not None:
                self.samples.append(sample)
                count += 1
        return count

    def generate_synthetic(self, count: int = 500, max_blocks: int = 200,
                           seed: int = 42) -> int:
        """Generate synthetic collapse samples from random structures."""
        rng = np.random.default_rng(seed)
        generated = 0

        for _ in range(count):
            n_blocks = rng.integers(10, max_blocks + 1)

            # Generate connected structure using random walk
            positions = [(0, 0, 0)]
            dirs = [(1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)]
            pos_set = {(0, 0, 0)}

            for _ in range(n_blocks - 1):
                base = positions[rng.integers(len(positions))]
                d = dirs[rng.integers(6)]
                new = (base[0] + d[0], base[1] + d[1], base[2] + d[2])
                if new not in pos_set:
                    positions.append(new)
                    pos_set.add(new)

            n = len(positions)

            # Node features
            density = rng.choice([2400, 7850, 500, 2600], size=n).astype(np.float32)
            rc = rng.uniform(15, 250, size=n).astype(np.float32)
            rt = rc * rng.uniform(0.05, 0.15, size=n).astype(np.float32)
            youngs = rng.uniform(10, 200, size=n).astype(np.float32)
            poisson = np.full(n, 0.2, dtype=np.float32)
            heights = np.array([p[1] for p in positions], dtype=np.float32)
            is_anchor = (heights == heights.min()).astype(np.float32)

            # Build adjacency
            pos_to_idx = {p: i for i, p in enumerate(positions)}
            src, dst = [], []
            edge_feats = []
            for i, p in enumerate(positions):
                for d_idx, (dx, dy, dz) in enumerate(dirs):
                    nb = (p[0] + dx, p[1] + dy, p[2] + dz)
                    if nb in pos_to_idx:
                        j = pos_to_idx[nb]
                        src.append(i)
                        dst.append(j)
                        cond = min(youngs[i], youngs[j]) * 1e3
                        edge_feats.append([cond, float(dx), float(dy), float(dz)])

            degree = np.zeros(n, dtype=np.float32)
            for s in src:
                degree[s] += 1

            node_features = np.stack([
                density, rc, rt, youngs, poisson, is_anchor, heights, degree
            ], axis=-1)

            edge_index = np.array([src, dst], dtype=np.int32) if src else \
                np.zeros((2, 0), dtype=np.int32)
            edge_features_arr = np.array(edge_feats, dtype=np.float32) if edge_feats else \
                np.zeros((0, 4), dtype=np.float32)

            # Simple heuristic labels: high unsupported blocks fail
            failure = np.zeros(n, dtype=np.int32)
            for i in range(n):
                if is_anchor[i]:
                    continue
                stress_ratio = (density[i] * 9.81 * (heights[i] - heights.min() + 1)) / (rc[i] * 1e6)
                if stress_ratio > 0.9:
                    failure[i] = 2  # crushing
                elif degree[i] <= 1 and heights[i] > heights.min() + 3:
                    failure[i] = 1  # cantilever
                elif degree[i] == 0:
                    failure[i] = 3  # no support

            self.samples.append(CollapseSample(
                island_id=generated,
                node_features=node_features,
                edge_index=edge_index,
                edge_features=edge_features_arr,
                failure_labels=failure,
                collapse_order=None,
            ))
            generated += 1

        return generated

    def __len__(self) -> int:
        return len(self.samples)

    @staticmethod
    def _load_journal(path: Path) -> CollapseSample | None:
        """Load a collapse journal binary file."""
        try:
            data = path.read_bytes()
            offset = 0

            island_id = int.from_bytes(data[offset:offset + 4], "little"); offset += 4
            num_blocks = int.from_bytes(data[offset:offset + 4], "little"); offset += 4
            num_events = int.from_bytes(data[offset:offset + 4], "little"); offset += 4

            # Parse blocks
            node_feats = []
            for _ in range(num_blocks):
                x = int.from_bytes(data[offset:offset + 4], "little", signed=True); offset += 4
                y = int.from_bytes(data[offset:offset + 4], "little", signed=True); offset += 4
                z = int.from_bytes(data[offset:offset + 4], "little", signed=True); offset += 4
                _mat_id = int.from_bytes(data[offset:offset + 2], "little"); offset += 2
                density = np.frombuffer(data[offset:offset + 4], dtype=np.float32)[0]; offset += 4
                rcomp = np.frombuffer(data[offset:offset + 4], dtype=np.float32)[0]; offset += 4
                rtens = np.frombuffer(data[offset:offset + 4], dtype=np.float32)[0]; offset += 4
                is_anchor = data[offset]; offset += 1
                node_feats.append([density, rcomp, rtens, 30.0, 0.2,
                                   float(is_anchor), float(y), 0.0])

            failure = np.zeros(num_blocks, dtype=np.int32)
            order = np.full(num_blocks, -1, dtype=np.int32)
            for evt_i in range(num_events):
                bidx = int.from_bytes(data[offset:offset + 4], "little"); offset += 4
                ftype = data[offset]; offset += 1
                _tick = int.from_bytes(data[offset:offset + 4], "little"); offset += 4
                if 0 <= bidx < num_blocks:
                    failure[bidx] = ftype
                    order[bidx] = evt_i

            return CollapseSample(
                island_id=island_id,
                node_features=np.array(node_feats, dtype=np.float32),
                edge_index=np.zeros((2, 0), dtype=np.int32),  # rebuild from positions
                edge_features=np.zeros((0, 4), dtype=np.float32),
                failure_labels=failure,
                collapse_order=order,
            )
        except Exception:
            return None
