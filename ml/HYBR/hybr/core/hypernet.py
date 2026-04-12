"""HyperNetwork with Spectral Normalization for stable weight generation."""
from __future__ import annotations

from typing import Sequence

import jax
import jax.numpy as jnp
import flax.linen as nn


class SpectralDense(nn.Module):
    """Dense layer (SpectralNorm disabled for research prototype stability)."""

    features: int
    use_bias: bool = True
    n_steps: int = 1

    @nn.compact
    def __call__(self, x: jnp.ndarray, update_stats: bool = True) -> jnp.ndarray:
        return nn.Dense(self.features, use_bias=self.use_bias)(x)


class HyperMLP(nn.Module):
    """Lightweight MLP hypernet backbone with spectral normalization + LayerNorm.

    Literature: hyperFastRL uses 3-stage ResNet with SpectralNorm on every
    linear layer to control Lipschitz constant (Miyato et al. 2018).
    We adopt a simpler MLP here because our output dimension is tiny
    (low-rank factors) and we want fast convergence.
    """

    hidden_widths: Sequence[int] = (128, 128)
    latent_dim: int = 32
    n_spectral_steps: int = 1

    @nn.compact
    def __call__(self, z: jnp.ndarray, update_stats: bool = True) -> jnp.ndarray:
        """
        Args:
            z: [B, latent_dim] geometry latent code
        Returns:
            h: [B, last_hidden] hypernet features
        """
        h = z
        for width in self.hidden_widths:
            h = SpectralDense(width, n_steps=self.n_spectral_steps)(
                h, update_stats=update_stats
            )
            h = nn.LayerNorm()(h)
            h = nn.gelu(h)
        return h


class SpectralWeightHead(nn.Module):
    """Output head for generating low-rank CP factors of a spectral weight.

    Generates:
      - lam: [R]  (global scale per rank)
      - a:   [R, C_in]
      - b:   [R, C_out]
      - u:   [R, mx]
      - v:   [R, my]
      - w:   [R, mz]
      - mode_w_delta: [mx, my, mz] (optional)
    """

    rank: int
    in_channels: int
    out_channels: int
    mx: int
    my: int
    mz: int
    generate_mode_w: bool = True

    @nn.compact
    def __call__(self, h: jnp.ndarray) -> dict:
        B = h.shape[0]
        R = self.rank
        out_dim = (
            R +                        # lam
            R * self.in_channels +     # a
            R * self.out_channels +    # b
            R * self.mx +              # u
            R * self.my +              # v
            R * self.mz                # w
        )
        if self.generate_mode_w:
            out_dim += self.mx * self.my * self.mz

        raw = nn.Dense(out_dim)(h)

        # Slice and reshape
        idx = 0
        lam = raw[:, idx:idx + R]; idx += R
        a   = raw[:, idx:idx + R * self.in_channels].reshape(B, R, self.in_channels); idx += R * self.in_channels
        b   = raw[:, idx:idx + R * self.out_channels].reshape(B, R, self.out_channels); idx += R * self.out_channels
        u   = raw[:, idx:idx + R * self.mx].reshape(B, R, self.mx); idx += R * self.mx
        v   = raw[:, idx:idx + R * self.my].reshape(B, R, self.my); idx += R * self.my
        w   = raw[:, idx:idx + R * self.mz].reshape(B, R, self.mz); idx += R * self.mz

        # Bound all CP factors to prevent multiplicative explosion
        # DEBUG: verify this code is loaded
        lam = jax.nn.softplus(lam) * 0.1
        a   = jnp.tanh(a)   * 0.1
        b   = jnp.tanh(b)   * 0.1
        u   = jnp.tanh(u)   * 0.1
        v   = jnp.tanh(v)   * 0.1
        w   = jnp.tanh(w)   * 0.1

        # Mode weight delta is kept small via tanh
        if self.generate_mode_w:
            mode_w_delta = raw[:, idx:].reshape(B, self.mx, self.my, self.mz)
            mode_w_delta = jnp.tanh(mode_w_delta) * 0.1
        else:
            mode_w_delta = None

        return {
            "lam": lam,
            "a": a,
            "b": b,
            "u": u,
            "v": v,
            "w": w,
            "mode_w_delta": mode_w_delta,
        }


class PerNeuronScaleHead(nn.Module):
    """Generates adaptive per-neuron scales s = 1 + W·h.

    Literature: hyperFastRL uses per-neuron scales as an adaptive
    feature-wise gain on each generated layer (Keynan et al. 2021).
    """

    channels: int

    @nn.compact
    def __call__(self, h: jnp.ndarray) -> jnp.ndarray:
        s = nn.Dense(self.channels)(h)
        return 1.0 + jnp.tanh(s) * 0.1
