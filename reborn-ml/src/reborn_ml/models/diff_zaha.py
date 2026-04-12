"""
diff_zaha.py — JAX 可微分札哈風格模組（Flax nn.Module）

將 zaha_style.py 的半拉格朗日平流改為 JAX 可微分版本，
配合 Flax 可學習參數（flow_speed, ribbon_amp, smooth_sigma, blend_weight），
支援端到端反向傳播訓練。

參考：
  Stam (1999), "Stable Fluids" — 半拉格朗日平流
  Sethian (1999), "Level Set Methods and Fast Marching Methods"
"""
from __future__ import annotations

import jax
import jax.numpy as jnp
import flax.linen as nn

from .diff_sdf_ops import density_to_sdf_diff, smooth_union


def _semi_lagrangian_advect_step(
    phi: jnp.ndarray,
    velocity: jnp.ndarray,
    dt: float = 0.4,
) -> jnp.ndarray:
    """可微分的單步半拉格朗日平流。"""
    Lx, Ly, Lz = phi.shape
    xs = jnp.arange(Lx, dtype=jnp.float32)
    ys = jnp.arange(Ly, dtype=jnp.float32)
    zs = jnp.arange(Lz, dtype=jnp.float32)
    X, Y, Z = jnp.meshgrid(xs, ys, zs, indexing="ij")

    X_back = jnp.clip(X - dt * velocity[..., 0], 0.0, Lx - 1.0)
    Y_back = jnp.clip(Y - dt * velocity[..., 1], 0.0, Ly - 1.0)
    Z_back = jnp.clip(Z - dt * velocity[..., 2], 0.0, Lz - 1.0)

    coords = jnp.stack([X_back, Y_back, Z_back], axis=0)
    advected = jax.scipy.ndimage.map_coordinates(phi, coords, order=1, mode="nearest")
    return advected


def _stress_eigenvector_field(stress_voigt: jnp.ndarray) -> jnp.ndarray:
    """從 Voigt 應力張量提取最小主應力方向場（可微分近似）。"""
    sigma_yy = stress_voigt[..., 1]
    gx = jnp.gradient(sigma_yy, axis=0)
    gy = jnp.gradient(sigma_yy, axis=1)
    gz = jnp.gradient(sigma_yy, axis=2)
    direction = jnp.stack([gx, gy, gz], axis=-1)
    norm = jnp.linalg.norm(direction, axis=-1, keepdims=True) + 1e-8
    return direction / norm


class DiffZahaStyle(nn.Module):
    """JAX 可微分札哈·哈蒂風格模組（Flax nn.Module）。

    可學習參數（全部經 softplus 確保正值）：
      flow_speed:    平流速度係數（初始 0.25）
      ribbon_amp:    帶狀面調製振幅（初始 0.4）
      smooth_sigma:  邊緣柔化程度（初始 1.0）
      blend_weight:  風格 SDF 混合權重（初始 0.3）
    """

    flow_speed_init: float = 0.25
    ribbon_amp_init: float = 0.4
    smooth_sigma_init: float = 1.0
    blend_weight_init: float = 0.3
    iso: float = 0.5
    n_advect_steps: int = 3

    @nn.compact
    def __call__(
        self,
        density: jnp.ndarray,
        stress_voigt: jnp.ndarray,
        style_sdf: jnp.ndarray,
    ) -> jnp.ndarray:
        """
        Args:
            density:      [B, Lx, Ly, Lz] 密度場
            stress_voigt: [B, Lx, Ly, Lz, 6] Voigt 應力場
            style_sdf:    [B, Lx, Ly, Lz, 1] 來自 StyleConditionedSSGO 的風格 SDF

        Returns:
            [B, Lx, Ly, Lz] 札哈風格化 SDF
        """
        flow_speed_raw = self.param("flow_speed", nn.initializers.constant(self.flow_speed_init), ())
        ribbon_amp_raw = self.param("ribbon_amp", nn.initializers.constant(self.ribbon_amp_init), ())
        smooth_sigma_raw = self.param("smooth_sigma", nn.initializers.constant(self.smooth_sigma_init), ())
        blend_weight_raw = self.param("blend_weight", nn.initializers.constant(self.blend_weight_init), ())

        speed = jax.nn.softplus(flow_speed_raw)
        amp = jax.nn.softplus(ribbon_amp_raw)
        sigma = jax.nn.softplus(smooth_sigma_raw)
        w = jax.nn.softplus(blend_weight_raw)

        base_sdf = jax.vmap(
            lambda d: density_to_sdf_diff(d, iso=self.iso, sigma=sigma)
        )(density)

        direction_field = jax.vmap(_stress_eigenvector_field)(stress_voigt)
        velocity = direction_field * density[..., None] * speed

        v_max = jnp.max(jnp.abs(velocity)) + 1e-8
        dt = 0.4 / v_max

        def advect_single(phi_single, vel_single):
            phi = phi_single
            for _ in range(self.n_advect_steps):
                phi = _semi_lagrangian_advect_step(phi, vel_single, dt=dt)
            return phi

        advected_sdf = jax.vmap(advect_single)(base_sdf, velocity)

        arc_proxy = jnp.cumsum(velocity[..., 1], axis=2)
        arc_proxy = arc_proxy / (jnp.max(jnp.abs(arc_proxy)) + 1e-8)
        ribbon_mod = amp * jnp.sin(2.0 * jnp.pi * arc_proxy * 3.0)
        near_surface = jax.nn.sigmoid(10.0 * (1.0 - jnp.abs(advected_sdf)))
        advected_sdf = advected_sdf + ribbon_mod * near_surface

        style_sdf_squeezed = style_sdf.squeeze(-1)
        weighted_style = w * style_sdf_squeezed
        combined = smooth_union(advected_sdf, weighted_style, k=0.3)
        return combined
