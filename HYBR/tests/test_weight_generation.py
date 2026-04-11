"""Test weight bank: shape correctness and delta/base ratio."""
from __future__ import annotations

import sys
from pathlib import Path

BRNEXT_ROOT = Path(__file__).resolve().parent.parent / "BR-NeXT"
if str(BRNEXT_ROOT) not in sys.path:
    sys.path.insert(0, str(BRNEXT_ROOT))

import jax
import jax.numpy as jnp

from hybr.core.weight_bank import SpectralWeightBank
from hybr.models.low_rank_factors import cp_reconstruct_5d


def test_cp_reconstruct_shape():
    R, C_in, C_out, mx, my, mz = 2, 8, 8, 4, 4, 3
    lam = jnp.ones((R,))
    a = jnp.ones((R, C_in))
    b = jnp.ones((R, C_out))
    u = jnp.ones((R, mx))
    v = jnp.ones((R, my))
    w = jnp.ones((R, mz))
    W = cp_reconstruct_5d(lam, a, b, u, v, w)
    assert W.shape == (C_in, C_out, mx, my, mz), f"Expected (8,8,4,4,3), got {W.shape}"
    print("[OK] test_cp_reconstruct_shape passed")


def test_weight_bank_additive():
    """SpectralWeightBank should produce W = W_base + alpha*DeltaW."""
    B = 2
    C_in, C_out, mx, my, mz = 8, 8, 4, 4, 3
    rank = 2

    bank = SpectralWeightBank(
        in_channels=C_in, out_channels=C_out,
        mx=mx, my=my, mz=mz, alpha_init=0.1
    )

    factors = {
        "lam": jax.nn.softplus(jnp.ones((B, rank))) * 0.1,
        "a": jnp.ones((B, rank, C_in)) * 0.01,
        "b": jnp.ones((B, rank, C_out)) * 0.01,
        "u": jnp.ones((B, rank, mx)) * 0.01,
        "v": jnp.ones((B, rank, my)) * 0.01,
        "w": jnp.ones((B, rank, mz)) * 0.01,
    }

    variables = bank.init(
        jax.random.PRNGKey(0),
        factors, factors, None,
    )
    params = variables["params"]
    weights, mode_w = bank.apply(variables, factors, factors, None)

    # shapes
    assert weights.shape == (B, C_in, C_out, mx, my, mz)
    assert mode_w.shape == (B, mx, my, mz)

    # check dtype
    assert jnp.iscomplexobj(weights)

    # Check that delta is small relative to base
    W_base = params["weights_r_base"] + 1j * params["weights_i_base"]
    delta = weights[0] - W_base
    ratio = jnp.linalg.norm(delta) / (jnp.linalg.norm(W_base) + 1e-8)
    assert ratio < 0.5, f"Delta/base ratio too large: {ratio}"
    print(f"[OK] test_weight_bank_additive passed (delta/base ratio={ratio:.4f})")


if __name__ == "__main__":
    test_cp_reconstruct_shape()
    test_weight_bank_additive()
