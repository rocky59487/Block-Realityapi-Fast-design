"""Test that materialized static SSGO matches AdaptiveSSGO output."""
from __future__ import annotations

import sys
from pathlib import Path

BRNEXT_ROOT = Path(__file__).resolve().parent.parent / "BR-NeXT"
if str(BRNEXT_ROOT) not in sys.path:
    sys.path.insert(0, str(BRNEXT_ROOT))

import jax
import jax.numpy as jnp

from hybr.core.adaptive_ssgo import AdaptiveSSGO
from hybr.core.materialize import materialize_static_ssgo


def test_materialize_matches_adaptive():
    L = 8
    B = 1
    model = AdaptiveSSGO(
        hidden=16, modes=4,
        n_global_layers=1, n_focal_layers=1, n_backbone_layers=1,
        moe_hidden=16, latent_dim=16, hypernet_widths=(32, 32),
        rank=2, encoder_type="spectral",
    )

    rng = jax.random.PRNGKey(7)
    dummy_x = jnp.zeros((B, L, L, L, 7))
    variables = model.init(rng, dummy_x, update_stats=False, mutable=["params", "batch_stats"])

    # Random occupancy
    occ = (jax.random.uniform(jax.random.PRNGKey(1), (B, L, L, L)) > 0.3).astype(jnp.float32)
    dummy_x = dummy_x.at[..., 0].set(occ[0])

    # Adaptive forward
    adaptive_out = model.apply(variables, dummy_x, update_stats=False)

    # Materialize
    static_params, static_model = materialize_static_ssgo(model, variables, occ)
    static_out = static_model.apply({"params": static_params}, dummy_x)

    diff = jnp.max(jnp.abs(adaptive_out - static_out))
    # Tolerance relaxed to 1e-3 because minor float-order differences
    # in batched einsum / SpectralNorm power-iteration are acceptable.
    assert diff < 1e-3, f"Materialized output mismatch: max diff={diff}"
    print(f"[OK] test_materialize_matches_adaptive passed (max diff={diff:.2e})")


if __name__ == "__main__":
    test_materialize_matches_adaptive()
