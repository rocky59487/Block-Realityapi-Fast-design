"""Training stability utilities for HYBR.

Includes:
- Gradient clipping with global norm
- Learning-rate warmup + cosine decay
- HyperNet output norm monitoring
- Spectral norm regularization
"""
from __future__ import annotations

import jax
import jax.numpy as jnp
import optax


def make_optimizer_with_warmup(
    peak_lr: float = 1e-3,
    warmup_steps: int = 500,
    total_steps: int = 10000,
    weight_decay: float = 1e-4,
    clip_global_norm: float = 1.0,
):
    """AdamW with linear warmup and cosine decay."""
    warmup_steps = min(warmup_steps, max(1, total_steps - 1))
    schedule = optax.warmup_cosine_decay_schedule(
        init_value=0.0,
        peak_value=peak_lr,
        warmup_steps=warmup_steps,
        decay_steps=total_steps,
        end_value=peak_lr * 0.1,
    )
    return optax.chain(
        optax.clip_by_global_norm(clip_global_norm),
        optax.adamw(schedule, weight_decay=weight_decay),
    )


def hypernet_regularizer(
    delta_weights: jnp.ndarray,
    base_weights: jnp.ndarray,
    alpha_sparsity: float = 1e-4,
) -> dict:
    """Regularization losses to stabilize HyperNet training.

    Args:
        delta_weights: generated weight deltas [B, ...]
        base_weights:  base weights [...]
        alpha_sparsity: L1 penalty on deltas
    Returns:
        dict of scalar regularization terms
    """
    # Ratio of delta norm to base norm (should stay < 0.3 ideally)
    delta_norm = jnp.linalg.norm(delta_weights)
    base_norm = jnp.linalg.norm(base_weights) + 1e-8
    ratio = delta_norm / base_norm

    # L1 sparsity on deltas
    sparsity = alpha_sparsity * jnp.mean(jnp.abs(delta_weights))

    return {
        "delta_base_ratio": ratio,
        "delta_sparsity": sparsity,
    }


def spectral_norm_penalty(params: dict, coeff: float = 1e-5) -> jnp.ndarray:
    """Optional L2 penalty on all parameters to keep HyperNet Lipschitz low."""
    leaves = jax.tree_util.tree_leaves(params)
    return coeff * sum(jnp.sum(x ** 2) for x in leaves)
