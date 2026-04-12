"""Low-rank tensor decomposition utilities for compact weight generation."""
from __future__ import annotations

import jax.numpy as jnp


def cp_reconstruct_5d(
    lam: jnp.ndarray,
    a: jnp.ndarray,
    b: jnp.ndarray,
    u: jnp.ndarray,
    v: jnp.ndarray,
    w: jnp.ndarray,
) -> jnp.ndarray:
    """Reconstruct a 5D tensor from CP-decomposition factors.

    Args:
        lam: [R] global scaling per component
        a:   [R, C_in]  channel-in factors
        b:   [R, C_out] channel-out factors
        u:   [R, mx]    x-mode factors
        v:   [R, my]    y-mode factors
        w:   [R, mz]    z-mode factors
    Returns:
        W: [C_in, C_out, mx, my, mz]
    """
    # Contract all rank dimensions into one
    # einsum: r, ri, ro, rx, ry, rz -> i o x y z
    return jnp.einsum("r,ri,ro,rx,ry,rz->ioxyz", lam, a, b, u, v, w)


def tucker_reconstruct_5d(
    core: jnp.ndarray,
    a: jnp.ndarray,
    b: jnp.ndarray,
    u: jnp.ndarray,
    v: jnp.ndarray,
    w: jnp.ndarray,
) -> jnp.ndarray:
    """Reconstruct a 5D tensor from Tucker decomposition.

    Args:
        core: [R1, R2, R3, R4, R5]
        a:    [C_in,  R1]
        b:    [C_out, R2]
        u:    [mx,    R3]
        v:    [my,    R4]
        w:    [mz,    R5]
    Returns:
        W: [C_in, C_out, mx, my, mz]
    """
    return jnp.einsum("ijklm,ic,oj,xk,yl,zm->coxyz", core, a, b, u, v, w)
