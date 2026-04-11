"""Test AdaptiveSSGO forward pass and variable initialization."""
from __future__ import annotations

import sys
from pathlib import Path

# Add BR-NeXT to path so we can import brnext models
BRNEXT_ROOT = Path(__file__).resolve().parent.parent / "BR-NeXT"
if str(BRNEXT_ROOT) not in sys.path:
    sys.path.insert(0, str(BRNEXT_ROOT))

import jax
import jax.numpy as jnp

from hybr.core.adaptive_ssgo import AdaptiveSSGO


def test_adaptive_ssgo_init_and_forward():
    """AdaptiveSSGO should initialize with batch_stats (spectral norm) and produce correct output shape."""
    L = 8
    B = 2
    model = AdaptiveSSGO(
        hidden=16,
        modes=4,
        n_global_layers=1,
        n_focal_layers=1,
        n_backbone_layers=1,
        moe_hidden=16,
        latent_dim=16,
        hypernet_widths=(32, 32),
        rank=2,
        encoder_type="spectral",
    )

    rng = jax.random.PRNGKey(0)
    dummy_x = jnp.zeros((B, L, L, L, 6))

    # Init requires mutable=['params', 'batch_stats'] because of SpectralNorm
    variables = model.init(rng, dummy_x, update_stats=True, mutable=["params", "batch_stats"])
    params = variables["params"]
    batch_stats = variables["batch_stats"]

    # Apply
    out, updates = model.apply(
        {"params": params, "batch_stats": batch_stats},
        dummy_x,
        update_stats=False,
        mutable=["batch_stats"],
    )

    assert out.shape == (B, L, L, L, 10), f"Expected (2,8,8,8,10), got {out.shape}"
    print("[OK] test_adaptive_ssgo_init_and_forward passed")


if __name__ == "__main__":
    test_adaptive_ssgo_init_and_forward()
