"""Weighted FNO — spectral convolution with learnable mode rebalancing."""
from __future__ import annotations

import jax
import jax.numpy as jnp
import flax.linen as nn


class WeightedSpectralConv3D(nn.Module):
    """3D spectral convolution with learnable per-mode weights."""

    in_channels: int
    out_channels: int
    modes: int = 8

    @nn.compact
    def __call__(self, x: jnp.ndarray) -> jnp.ndarray:
        B, Lx, Ly, Lz, _ = x.shape
        mx = min(self.modes, Lx)
        my = min(self.modes, Ly)
        mz = min(self.modes, Lz // 2 + 1)

        scale = 1.0 / (self.in_channels * self.out_channels)
        weights_r = self.param(
            "weights_r", nn.initializers.normal(stddev=scale),
            (self.in_channels, self.out_channels, mx, my, mz),
        )
        weights_i = self.param(
            "weights_i", nn.initializers.normal(stddev=scale),
            (self.in_channels, self.out_channels, mx, my, mz),
        )
        weights = weights_r + 1j * weights_i

        # Learnable mode rebalancing
        mode_w = self.param(
            "mode_w", nn.initializers.ones, (mx, my, mz)
        )
        mode_w = jax.nn.sigmoid(mode_w)

        x_ft = jnp.fft.rfftn(x, axes=(1, 2, 3))
        x_modes = x_ft[:, :mx, :my, :mz, :]
        out_modes = jnp.einsum("bxyzi,ioxyz->bxyzo", x_modes, weights * mode_w)

        out_ft = jnp.zeros((B, Lx, Ly, Lz // 2 + 1, self.out_channels), dtype=jnp.complex64)
        out_ft = out_ft.at[:, :mx, :my, :mz, :].set(out_modes)
        return jnp.fft.irfftn(out_ft, s=(Lx, Ly, Lz), axes=(1, 2, 3)).real


class FNOBlock(nn.Module):
    """Standard FNO block: spectral conv + linear bypass + activation."""

    channels: int
    modes: int = 8

    @nn.compact
    def __call__(self, x: jnp.ndarray) -> jnp.ndarray:
        x_spec = WeightedSpectralConv3D(
            in_channels=self.channels,
            out_channels=self.channels,
            modes=self.modes,
        )(x)
        x_local = nn.Dense(self.channels)(x)
        return nn.gelu(x_spec + x_local)
