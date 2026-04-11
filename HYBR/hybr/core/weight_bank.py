"""Weight bank: base weights + low-rank additive delta generation."""
from __future__ import annotations

import jax
import jax.numpy as jnp
import flax.linen as nn

from hybr.models.low_rank_factors import cp_reconstruct_5d


class SpectralWeightBank(nn.Module):
    """Holds base weights for a spectral conv and produces W = W_base + alpha*DeltaW(z).

    The base weights are standard flax parameters (trainable).
    The delta is reconstructed from CP factors supplied by the HyperNet.
    """

    in_channels: int
    out_channels: int
    mx: int
    my: int
    mz: int
    alpha_init: float = 0.1

    @nn.compact
    def __call__(self, factors_r: dict, factors_i: dict, mode_w_delta: jnp.ndarray | None = None) -> tuple:
        """
        Args:
            factors_r: CP factors for real part (from HyperNet), each [B, ...]
            factors_i: CP factors for imag part (from HyperNet), each [B, ...]
            mode_w_delta: optional [B, mx, my, mz] additive mode rebalancing
        Returns:
            weights: complex64 [B, C_in, C_out, mx, my, mz]
            mode_w: [B, mx, my, mz]
        """
        scale = 1.0 / (self.in_channels * self.out_channels)

        # Base weights (trainable)
        weights_r_base = self.param(
            "weights_r_base", nn.initializers.normal(stddev=scale),
            (self.in_channels, self.out_channels, self.mx, self.my, self.mz),
        )
        weights_i_base = self.param(
            "weights_i_base", nn.initializers.normal(stddev=scale),
            (self.in_channels, self.out_channels, self.mx, self.my, self.mz),
        )
        mode_w_base = self.param(
            "mode_w_base", nn.initializers.ones, (self.mx, self.my, self.mz)
        )

        # Learnable global mixing coefficient for the residual
        alpha = self.param("alpha", nn.initializers.constant(self.alpha_init), ())
        alpha = jax.nn.softplus(alpha)  # ensure positive

        # Reconstruct delta from CP factors (vmapped over batch)
        def _reconstruct(cp: dict) -> jnp.ndarray:
            return jax.vmap(cp_reconstruct_5d, in_axes=(0, 0, 0, 0, 0, 0))(
                cp["lam"], cp["a"], cp["b"], cp["u"], cp["v"], cp["w"]
            )

        delta_r = _reconstruct(factors_r)
        delta_i = _reconstruct(factors_i)

        # Additive combination
        W_r = weights_r_base[None, ...] + alpha * delta_r
        W_i = weights_i_base[None, ...] + alpha * delta_i

        # Mode rebalancing
        if mode_w_delta is not None:
            mode_w = mode_w_base[None, ...] + mode_w_delta
        else:
            mode_w = jnp.broadcast_to(mode_w_base[None, ...], W_r.shape[:1] + mode_w_base.shape)
        mode_w = jax.nn.sigmoid(mode_w)

        weights = W_r + 1j * W_i
        return weights, mode_w


class BiasBank(nn.Module):
    """Holds base bias and produces b = b_base + alpha*delta_b."""

    features: int
    alpha_init: float = 0.1

    @nn.compact
    def __call__(self, delta_b: jnp.ndarray) -> jnp.ndarray:
        b_base = self.param("b_base", nn.initializers.zeros, (self.features,))
        alpha = self.param("alpha", nn.initializers.constant(self.alpha_init), ())
        alpha = jax.nn.softplus(alpha)
        return b_base[None, :] + alpha * delta_b
