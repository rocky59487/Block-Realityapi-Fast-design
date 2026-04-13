"""Smoke tests for SSGO forward pass."""
import jax
import jax.numpy as jnp

from brnext.models.ssgo import SSGO


def test_ssgo_output_shape():
    model = SSGO(hidden=16, modes=4, n_global_layers=2,
                 n_focal_layers=1, n_backbone_layers=1, moe_hidden=16)
    rng = jax.random.PRNGKey(0)
    B, L = 2, 8
    x = jnp.ones((B, L, L, L, 7))
    variables = model.init(rng, x)
    out = model.apply(variables, x)
    assert out.shape == (B, L, L, L, 10)


def test_ssgo_zero_in_air():
    model = SSGO(hidden=16, modes=4, n_global_layers=1,
                 n_focal_layers=1, n_backbone_layers=1, moe_hidden=16)
    rng = jax.random.PRNGKey(1)
    B, L = 1, 6
    x = jnp.zeros((B, L, L, L, 7))
    x = x.at[..., 0].set(1.0)  # occupancy=1 everywhere for this test
    variables = model.init(rng, x)
    out = model.apply(variables, x)
    # All channels should be zero where occupancy is zero
    x_air = jnp.zeros((B, L, L, L, 7))
    out_air = model.apply(variables, x_air)
    assert jnp.allclose(out_air, 0.0)
