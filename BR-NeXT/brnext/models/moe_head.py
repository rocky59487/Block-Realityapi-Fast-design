"""Mixture-of-Experts spectral head.

For JIT / ONNX compatibility, all experts are computed and weighted-summed.
Because the head is small (few channels, shallow), the overhead is negligible.
"""
from __future__ import annotations

import jax
import jax.numpy as jnp
import flax.linen as nn


class ExpertMLP(nn.Module):
    """Single MoE expert."""
    hidden: int
    out_channels: int

    @nn.compact
    def __call__(self, x: jnp.ndarray) -> jnp.ndarray:
        h = nn.Dense(self.hidden)(x)
        h = nn.gelu(h)
        return nn.Dense(self.out_channels)(h)


class MoESpectralHead(nn.Module):
    """MoE head with shared + routed experts.

    Args:
        out_channels: output dimension per task head
        hidden: expert hidden width
        n_shared: number of shared experts (always active)
        n_routed: number of routed experts
        top_k: how many routed experts to gate
        dropout_rate: dropout applied inside each expert
    """

    out_channels: int
    hidden: int = 32
    n_shared: int = 2
    n_routed: int = 8
    top_k: int = 2
    dropout_rate: float = 0.0

    @nn.compact
    def __call__(self, x: jnp.ndarray, train: bool = False) -> jnp.ndarray:
        """
        Args:
            x: [B, L, L, L, H]
            train: whether dropout is active
        Returns:
            [B, L, L, L, out_channels]
        """
        # Router: simple MLP on features
        router_feat = nn.Dense(16)(x)
        router_feat = nn.relu(router_feat)
        logits = nn.Dense(self.n_routed)(router_feat)  # [B, L, L, L, n_routed]

        # Soft gating with adaptive top-k mask (JIT-friendly)
        gate = jax.nn.softmax(logits, axis=-1)
        threshold = 1.0 / self.n_routed
        mask = (gate > threshold).astype(gate.dtype)
        masked_gate = gate * mask
        masked_gate = masked_gate / (jnp.sum(masked_gate, axis=-1, keepdims=True) + 1e-8)

        # Expert forward with optional dropout
        def _expert(x_in):
            h = nn.Dense(self.hidden)(x_in)
            h = nn.gelu(h)
            if self.dropout_rate > 0.0:
                h = nn.Dropout(rate=self.dropout_rate, deterministic=not train)(h)
            return nn.Dense(self.out_channels)(h)

        # Compute all routed experts and aggregate
        routed_out = jnp.stack([
            _expert(x)
            for _ in range(self.n_routed)
        ], axis=-1)  # [B, L, L, L, out_channels, n_routed]
        routed_agg = jnp.einsum("...co,...o->...c", routed_out, masked_gate)

        # Shared experts
        shared_agg = sum(
            _expert(x)
            for _ in range(self.n_shared)
        )

        return routed_agg + shared_agg
