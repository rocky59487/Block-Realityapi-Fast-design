"""
diff_zaha.py — JAX 可微分札哈風格模組（Flax nn.Module）

將 zaha_style.py 的半拉格朗日平流改為 JAX 可微分版本，
配合 Flax 可學習參數（flow_speed, ribbon_amp, smooth_sigma, blend_weight），
支援端到端反向傳播訓練。

管線：
  density → base_sdf (density_to_sdf_diff)
  → 應力特徵向量方向場 → 可微分半拉格朗日平流（固定 3 步）
  → 帶狀面調製（ribbon_amp 振幅控制）
  → 與 StyleConditionedSSGO 的 style_sdf 混合
  → 輸出風格化 SDF [B,Lx,Ly,Lz]

設計：
  - 所有參數經 softplus 確保正值
  - 平流步數固定為 3（簡化版，適合可微分管線）
  - 速度場由 Voigt 應力的特徵向量近似

原始分析版本：zaha_style.py（numpy/scipy，不可微分）

參考：
  Sethian (1999), "Level Set Methods and Fast Marching Methods"
  Stam (1999), "Stable Fluids" — 半拉格朗日平流
  Schumacher (2009), "Parametricism"
  Leach (2009), "The Language of Space: Form and Parametrics in the Work of Zaha Hadid"
"""
from __future__ import annotations

import jax
import jax.numpy as jnp
import flax.linen as nn

from ..models.diff_sdf_ops import density_to_sdf_diff, smooth_union


# ---------------------------------------------------------------------------
# 可微分半拉格朗日平流（單步）
# ---------------------------------------------------------------------------

def _semi_lagrangian_advect_step(
    phi: jnp.ndarray,
    velocity: jnp.ndarray,
    dt: float = 0.4,
) -> jnp.ndarray:
    """可微分的單步半拉格朗日平流。

    演算法（Stam 1999）：
      對每個格點 p，向後追蹤：p_back = p - dt * v(p)
      從 p_back 三線性插值 phi

    使用 jax.scipy.ndimage.map_coordinates 取代 scipy 版本，
    支援 jax.grad 反向傳播。

    Args:
        phi:      [Lx, Ly, Lz] 水平集場
        velocity: [Lx, Ly, Lz, 3] 速度場
        dt:       時間步長（體素/步）

    Returns:
        [Lx, Ly, Lz] 平流後的水平集場
    """
    Lx, Ly, Lz = phi.shape

    # 建立格點座標
    xs = jnp.arange(Lx, dtype=jnp.float32)
    ys = jnp.arange(Ly, dtype=jnp.float32)
    zs = jnp.arange(Lz, dtype=jnp.float32)
    X, Y, Z = jnp.meshgrid(xs, ys, zs, indexing="ij")

    # 反向追蹤
    X_back = jnp.clip(X - dt * velocity[..., 0], 0.0, Lx - 1.0)
    Y_back = jnp.clip(Y - dt * velocity[..., 1], 0.0, Ly - 1.0)
    Z_back = jnp.clip(Z - dt * velocity[..., 2], 0.0, Lz - 1.0)

    # 三線性插值（jax.scipy 版本，完全可微分）
    coords = jnp.stack([X_back, Y_back, Z_back], axis=0)  # [3, Lx, Ly, Lz]
    advected = jax.scipy.ndimage.map_coordinates(phi, coords, order=1, mode="nearest")

    return advected


# ---------------------------------------------------------------------------
# 應力特徵向量方向場提取（可微分近似）
# ---------------------------------------------------------------------------

def _stress_eigenvector_field(stress_voigt: jnp.ndarray) -> jnp.ndarray:
    """從 Voigt 應力張量提取最小主應力方向場（可微分近似）。

    完整特徵分解在 JAX 中可微分但計算量大，
    此處使用 σ_yy 的空間梯度作為流動方向的近似。
    梯度方向大致平行於最小主應力方向（張拉流線）。

    Args:
        stress_voigt: [Lx, Ly, Lz, 6] Voigt 應力場
                      索引順序：[σ_xx, σ_yy, σ_zz, τ_yz, τ_xz, τ_xy]

    Returns:
        [Lx, Ly, Lz, 3] 歸一化方向場
    """
    # 使用 σ_yy 梯度近似最小主應力方向
    sigma_yy = stress_voigt[..., 1]
    gx = jnp.gradient(sigma_yy, axis=0)
    gy = jnp.gradient(sigma_yy, axis=1)
    gz = jnp.gradient(sigma_yy, axis=2)
    direction = jnp.stack([gx, gy, gz], axis=-1)  # [Lx, Ly, Lz, 3]

    # 歸一化（避免零向量除零）
    norm = jnp.linalg.norm(direction, axis=-1, keepdims=True) + 1e-8
    return direction / norm


class DiffZahaStyle(nn.Module):
    """JAX 可微分札哈·哈蒂風格模組（Flax nn.Module）。

    將 zaha_style.ZahaStyle 的半拉格朗日水平集平流轉換為可微分版本：
      - 密度場 → 基礎 SDF
      - 從 Voigt 應力場提取特徵向量方向場作為平流速度
      - 固定 3 步半拉格朗日平流（JAX 原生，可微分）
      - 帶狀面調製（ribbon_amp 控制振幅）
      - 與神經風格 SDF 進行平滑聯集

    可學習參數（全部經 softplus 確保正值）：
      flow_speed:    平流速度係數（初始 0.25）
      ribbon_amp:    帶狀面調製振幅（初始 0.4）
      smooth_sigma:  邊緣柔化程度（初始 1.0）
      blend_weight:  風格 SDF 混合權重（初始 0.3）

    Attributes:
        flow_speed_init:    flow_speed 初始值
        ribbon_amp_init:    ribbon_amp 初始值
        smooth_sigma_init:  smooth_sigma 初始值
        blend_weight_init:  blend_weight 初始值
        iso:                密度等值面閾值
        n_advect_steps:     半拉格朗日平流步數（固定 3）
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
        """將札哈風格形變應用於密度場。

        步驟：
          1. density → base_sdf（density_to_sdf_diff，逐 sample）
          2. stress_voigt → 特徵向量方向場 → 3 步半拉格朗日平流
          3. 帶狀面調製（ribbon_amp 控制的正弦波疊加）
          4. smooth_union(advected_sdf, weighted_style_sdf)

        Args:
            density:      [B, Lx, Ly, Lz] 密度場，值域 [0, 1]
            stress_voigt: [B, Lx, Ly, Lz, 6] Voigt 應力場
            style_sdf:    [B, Lx, Ly, Lz, 1] 來自 StyleConditionedSSGO 的風格 SDF

        Returns:
            [B, Lx, Ly, Lz] 札哈風格化 SDF（負=內部，正=外部）
        """
        # ── 可學習參數（raw 空間） ──
        flow_speed_raw = self.param(
            "flow_speed",
            nn.initializers.constant(self.flow_speed_init),
            (),
        )
        ribbon_amp_raw = self.param(
            "ribbon_amp",
            nn.initializers.constant(self.ribbon_amp_init),
            (),
        )
        smooth_sigma_raw = self.param(
            "smooth_sigma",
            nn.initializers.constant(self.smooth_sigma_init),
            (),
        )
        blend_weight_raw = self.param(
            "blend_weight",
            nn.initializers.constant(self.blend_weight_init),
            (),
        )

        # softplus 確保正值
        speed = jax.nn.softplus(flow_speed_raw)
        amp = jax.nn.softplus(ribbon_amp_raw)
        sigma = jax.nn.softplus(smooth_sigma_raw)
        w = jax.nn.softplus(blend_weight_raw)

        # ── 步驟 1：密度場 → 基礎 SDF（逐 sample） ──
        base_sdf = jax.vmap(
            lambda d: density_to_sdf_diff(d, iso=self.iso, sigma=sigma)
        )(density)  # [B, Lx, Ly, Lz]

        # ── 步驟 2：可微分半拉格朗日平流 ──
        # 從應力場提取特徵向量方向場作為速度
        direction_field = jax.vmap(_stress_eigenvector_field)(stress_voigt)
        # [B, Lx, Ly, Lz, 3]

        # 以密度加權（只在結構材料內流動）+ 可學習速度係數
        velocity = direction_field * density[..., None] * speed

        # 自適應 dt（CFL 條件近似，逐 batch 取最大速度）
        v_max = jnp.max(jnp.abs(velocity)) + 1e-8
        dt = 0.4 / v_max

        # 固定 3 步半拉格朗日平流（逐 sample vmap）
        def advect_single(phi_single, vel_single):
            """對單一 sample 執行多步平流。"""
            phi = phi_single
            for _ in range(self.n_advect_steps):
                phi = _semi_lagrangian_advect_step(phi, vel_single, dt=dt)
            return phi

        advected_sdf = jax.vmap(advect_single)(base_sdf, velocity)
        # [B, Lx, Ly, Lz]

        # ── 步驟 3：帶狀面調製 ──
        # 沿特徵向量方向的正弦波疊加，產生哈蒂特徵的縱向帶狀紋理
        # 使用速度場的累積投影（模擬弧長參數化）
        arc_proxy = jnp.cumsum(velocity[..., 1], axis=2)  # 沿 z 軸累積 vy
        arc_proxy = arc_proxy / (jnp.max(jnp.abs(arc_proxy)) + 1e-8)
        # 正弦調製（近等值面區域才有效）
        ribbon_mod = amp * jnp.sin(2.0 * jnp.pi * arc_proxy * 3.0)
        near_surface = jax.nn.sigmoid(10.0 * (1.0 - jnp.abs(advected_sdf)))
        advected_sdf = advected_sdf + ribbon_mod * near_surface

        # ── 步驟 4：與神經風格 SDF 混合 ──
        # style_sdf: [B, Lx, Ly, Lz, 1] → squeeze 至 [B, Lx, Ly, Lz]
        style_sdf_squeezed = style_sdf.squeeze(-1)
        weighted_style = w * style_sdf_squeezed

        # 平滑聯集
        combined = smooth_union(advected_sdf, weighted_style, k=0.3)

        return combined
