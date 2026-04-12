"""
diff_gaudi.py — JAX 可微分高第風格模組（Flax nn.Module）

將 gaudi_style.py 的分析式 SDF 操作改為 JAX 可微分版本，
配合 Flax 可學習參數（arch_strength, smin_k, column_threshold, blend_weight），
支援端到端反向傳播訓練。

管線：
  density → base_sdf (density_to_sdf_diff)
  → 軟性柱偵測（可微分閾值化 σ_yy）
  → 懸鏈線拱 + 雙曲面柱 SDF 組合
  → 神經引導混合（smooth_union with style_sdf）
  → 輸出風格化 SDF [B,Lx,Ly,Lz]

參考：
  Huerta (2003), "Structural Design in the Work of Gaudí"
  Inigo Quilez (2013), "Smooth Minimum"
"""
from __future__ import annotations

import jax
import jax.numpy as jnp
import flax.linen as nn

from .diff_sdf_ops import (
    density_to_sdf_diff,
    smooth_union,
    sdf_catenary_arch,
    sdf_hyperboloid,
)


class DiffGaudiStyle(nn.Module):
    """JAX 可微分高第風格模組（Flax nn.Module）。

    可學習參數（全部經 softplus 確保正值）：
      arch_strength:     懸鏈線拱 SDF 半徑（初始 1.5）
      smin_k:            平滑聯集混合半徑（初始 0.3）
      column_threshold:  觸發柱偵測的垂直壓力閾值（初始 0.65）
      blend_weight:      風格 SDF 混合權重（初始 0.3）
    """

    arch_strength_init: float = 1.5
    smin_k_init: float = 0.3
    column_threshold_init: float = 0.65
    blend_weight_init: float = 0.3
    iso: float = 0.5
    smooth_sigma: float = 0.8

    @nn.compact
    def __call__(
        self,
        density: jnp.ndarray,
        stress_voigt: jnp.ndarray,
        style_sdf: jnp.ndarray,
    ) -> jnp.ndarray:
        """
        Args:
            density:      [B, Lx, Ly, Lz] 密度場，值域 [0, 1]
            stress_voigt: [B, Lx, Ly, Lz, 6] Voigt 應力場
            style_sdf:    [B, Lx, Ly, Lz, 1] 來自 StyleConditionedSSGO 的風格 SDF

        Returns:
            [B, Lx, Ly, Lz] 高第風格化 SDF（負=內部，正=外部）
        """
        arch_strength_raw = self.param(
            "arch_strength",
            nn.initializers.constant(self.arch_strength_init),
            (),
        )
        smin_k_raw = self.param(
            "smin_k",
            nn.initializers.constant(self.smin_k_init),
            (),
        )
        column_threshold_raw = self.param(
            "column_threshold",
            nn.initializers.constant(self.column_threshold_init),
            (),
        )
        blend_weight_raw = self.param(
            "blend_weight",
            nn.initializers.constant(self.blend_weight_init),
            (),
        )

        arch_str = jax.nn.softplus(arch_strength_raw)
        k = jax.nn.softplus(smin_k_raw)
        col_thr = jax.nn.softplus(column_threshold_raw)
        w = jax.nn.softplus(blend_weight_raw)

        effective_sigma = self.smooth_sigma * arch_str
        base_sdf = jax.vmap(
            lambda d: density_to_sdf_diff(d, iso=self.iso, sigma=effective_sigma)
        )(density)

        sigma_yy = -stress_voigt[..., 1]
        sigma_yy_max = jnp.max(jnp.abs(sigma_yy), axis=(1, 2, 3), keepdims=True) + 1e-8
        sigma_yy_norm = sigma_yy / sigma_yy_max

        column_mask_soft = jax.nn.sigmoid(10.0 * (sigma_yy_norm - col_thr))
        column_mask_soft = column_mask_soft * density

        column_sdf_offset = -arch_str * 0.5 * column_mask_soft
        base_sdf = base_sdf + column_sdf_offset

        style_sdf_squeezed = style_sdf.squeeze(-1)
        weighted_style = w * style_sdf_squeezed

        combined = smooth_union(base_sdf, weighted_style, k=k)
        return combined
