"""Sparse voxel graph attention / message passing using shift-based aggregation.

Fully JIT-compatible: no dynamic shapes, no segment_sum on variable graphs.
"""
from __future__ import annotations

import jax.numpy as jnp
import flax.linen as nn


class SparseVoxelGraphConv(nn.Module):
    """Message-passing layer over 26-connected voxel neighborhood.

    Operates entirely via jnp.roll, making it JIT- and ONNX-friendly.
    """

    out_channels: int

    @nn.compact
    def __call__(self, x: jnp.ndarray, occupancy: jnp.ndarray) -> jnp.ndarray:
        """
        Args:
            x: [B, Lx, Ly, Lz, C]
            occupancy: [B, Lx, Ly, Lz] bool/float mask
        Returns:
            [B, Lx, Ly, Lz, out_channels]
        """
        occ = occupancy[..., None]  # [B, L, L, L, 1]
        C_in = x.shape[-1]

        # Message aggregation over 26 neighbors
        messages = []
        neighbor_masks = []
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for dz in (-1, 0, 1):
                    if dx == 0 and dy == 0 and dz == 0:
                        continue
                    x_nb = jnp.roll(x, (dx, dy, dz), axis=(1, 2, 3))
                    occ_nb = jnp.roll(occ, (dx, dy, dz), axis=(1, 2, 3))
                    # Message function
                    msg_in = jnp.concatenate([x, x_nb], axis=-1)
                    msg = nn.Dense(self.out_channels)(msg_in)
                    msg = nn.relu(msg)
                    messages.append(msg * occ_nb)
                    neighbor_masks.append(occ_nb)

        # Mean aggregation over valid neighbors
        agg = sum(messages)
        denom = sum(neighbor_masks) + 1e-8
        agg = agg / denom

        # Update function
        update_in = jnp.concatenate([x, agg], axis=-1)
        out = nn.Dense(self.out_channels)(update_in)
        return out * occ
