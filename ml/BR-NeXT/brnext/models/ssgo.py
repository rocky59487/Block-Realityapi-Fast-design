"""SSGO — Sparse Spectral-Geometry Operator.

Output contract (MUST match Java OnnxPFSFRuntime):
  Input:  [B, L, L, L, 7]  (occ, E_norm, nu, rho_norm, rcomp_norm, rtens_norm, anchor)
  Output: [B, L, L, L, 10] (stress_voigt(6), log_displacement(3), phi(1))
"""
from __future__ import annotations

import jax
import jax.numpy as jnp
import flax.linen as nn

from .weighted_fno import FNOBlock, WeightedSpectralConv3D
from .voxel_gat import SparseVoxelGraphConv
from .moe_head import MoESpectralHead


class SSGO(nn.Module):
    """Sparse Spectral-Geometry Operator for structural physics surrogate."""

    hidden: int = 48
    modes: int = 6
    n_global_layers: int = 3
    n_focal_layers: int = 2
    n_backbone_layers: int = 2
    moe_hidden: int = 32
    dropout_rate: float = 0.0
    output_uncertainty: bool = False

    @nn.compact
    def __call__(self, x: jnp.ndarray, train: bool = False) -> jnp.ndarray:
        """
        Args:
            x: [B, L, L, L, 7]  (occ, E_norm, nu, rho_norm, rcomp_norm, rtens_norm, anchor)
            train: whether to apply dropout
        Returns:
            [B, L, L, L, 10]  or  [B, L, L, L, 11] if output_uncertainty=True
        """
        occ = x[..., 0:1]  # [B, L, L, L, 1]

        # ── Global Branch ──
        g = nn.Dense(self.hidden)(x)
        for _ in range(self.n_global_layers):
            g = FNOBlock(self.hidden, self.modes)(g)
            if self.dropout_rate > 0.0:
                g = nn.Dropout(rate=self.dropout_rate, deterministic=not train)(g)

        # ── Focal Branch ──
        f = nn.Dense(self.hidden)(x)
        for _ in range(self.n_focal_layers):
            f = SparseVoxelGraphConv(self.hidden)(f, occ.squeeze(-1))
            if self.dropout_rate > 0.0:
                f = nn.Dropout(rate=self.dropout_rate, deterministic=not train)(f)

        # ── Gated Fusion ──
        concat = jnp.concatenate([g, f], axis=-1)
        gate = jax.nn.sigmoid(nn.Dense(1)(concat))  # [B, L, L, L, 1]
        fused = gate * g + (1.0 - gate) * f

        # ── Shared Backbone ──
        h = fused
        for _ in range(self.n_backbone_layers):
            h = FNOBlock(self.hidden, self.modes)(h)
            if self.dropout_rate > 0.0:
                h = nn.Dropout(rate=self.dropout_rate, deterministic=not train)(h)

        # ── MoE-Spectral Head ──
        moe_out = MoESpectralHead(
            out_channels=self.hidden, hidden=self.moe_hidden, dropout_rate=self.dropout_rate
        )(h, train=train)
        if self.dropout_rate > 0.0:
            moe_out = nn.Dropout(rate=self.dropout_rate, deterministic=not train)(moe_out)

        # ── Task Heads ──
        head_w = max(self.hidden, 32)

        # Stress head (6 channels)
        s = nn.Dense(head_w)(moe_out)
        s = nn.gelu(s)
        stress = nn.Dense(6)(s)

        # Displacement head (3 channels)
        d = nn.Dense(head_w)(moe_out)
        d = nn.gelu(d)
        disp = nn.Dense(3)(d)

        # Phi head (1 channel, PFSF-compatible)
        p = nn.Dense(head_w)(moe_out)
        p = nn.gelu(p)
        phi = nn.Dense(1)(p)

        out = jnp.concatenate([stress, disp, phi], axis=-1)
        out = out * occ  # zero out air voxels

        if self.output_uncertainty:
            u = nn.Dense(head_w)(moe_out)
            u = nn.gelu(u)
            log_var = nn.Dense(1)(u)
            out = jnp.concatenate([out, log_var], axis=-1)

        return out
