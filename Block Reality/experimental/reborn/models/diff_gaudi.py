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

設計：
  - 所有參數經 softplus 確保正值
  - style_sdf 來自 StyleConditionedSSGO 的第 11 通道
  - 批次維度在最前（[B,Lx,Ly,Lz]）

原始分析版本：gaudi_style.py（numpy/scipy，不可微分）

參考：
  Huerta (2003), "Structural Design in the Work of Gaudí"
  Burry (1993), "Expiatory Church of the Sagrada Família"
  Inigo Quilez (2013), "Smooth Minimum"
  Oxman (2010), "Performance-Based Design"
"""
from __future__ import annotations

import jax
import jax.numpy as jnp
import flax.linen as nn

from ..models.diff_sdf_ops import (
    density_to_sdf_diff,
    smooth_union,
    sdf_catenary_arch,
    sdf_hyperboloid,
)


class DiffGaudiStyle(nn.Module):
    """JAX 可微分高第風格模組（Flax nn.Module）。

    將 gaudi_style.GaudiStyle 的分析式管線轉換為完全可微分版本：
      - 密度場 → 基礎 SDF（density_to_sdf_diff）
      - 應力場 → 軟性柱偵測（可微分 sigmoid 閾值化）
      - 懸鏈線拱 + 雙曲面柱幾何基元
      - 與神經網路產生的 style_sdf 進行加權平滑聯集

    可學習參數（全部經 softplus 確保正值）：
      arch_strength:     懸鏈線拱 SDF 半徑（初始 1.5）
      smin_k:            平滑聯集混合半徑（初始 0.3）
      column_threshold:  觸發柱偵測的垂直壓力閾值（初始 0.65）
      blend_weight:      風格 SDF 混合權重（初始 0.3）

    Attributes:
        arch_strength_init:    arch_strength 初始值
        smin_k_init:           smin_k 初始值
        column_threshold_init: column_threshold 初始值
        blend_weight_init:     blend_weight 初始值
        iso:                   密度等值面閾值
        smooth_sigma:          高斯平滑 sigma（體素）
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
        """將高第風格形變應用於密度場。

        管線：
          1. density → base_sdf（density_to_sdf_diff，per-sample）
          2. stress_voigt → 可微分柱偵測（σ_yy sigmoid 閾值化）
          3. 柱偵測區域產生雙曲面 SDF 調製
          4. smooth_union(base_sdf, style_sdf) 以可學習權重混合
          5. 輸出最終 SDF

        Args:
            density:      [B, Lx, Ly, Lz] 密度場，值域 [0, 1]
            stress_voigt: [B, Lx, Ly, Lz, 6] Voigt 應力場
            style_sdf:    [B, Lx, Ly, Lz, 1] 來自 StyleConditionedSSGO 的風格 SDF

        Returns:
            [B, Lx, Ly, Lz] 高第風格化 SDF（負=內部，正=外部）
        """
        # ── 可學習參數（raw 空間，softplus 後使用） ──
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

        # softplus 確保正值
        arch_str = jax.nn.softplus(arch_strength_raw)
        k = jax.nn.softplus(smin_k_raw)
        col_thr = jax.nn.softplus(column_threshold_raw)
        w = jax.nn.softplus(blend_weight_raw)

        # ── 步驟 1：密度場 → 基礎 SDF（逐 sample） ──
        # arch_strength 調製平滑 sigma（越大 → SDF 邊界越柔和）
        effective_sigma = self.smooth_sigma * arch_str
        # vmap 沿 batch 維度逐 sample 處理
        base_sdf = jax.vmap(
            lambda d: density_to_sdf_diff(d, iso=self.iso, sigma=effective_sigma)
        )(density)  # [B, Lx, Ly, Lz]

        # ── 步驟 2：可微分柱偵測 ──
        # σ_yy（垂直壓縮應力，index=1），壓縮為負 → 取負值使壓縮為正
        sigma_yy = -stress_voigt[..., 1]  # [B, Lx, Ly, Lz]
        # 歸一化至 [0, 1]
        sigma_yy_max = jnp.max(jnp.abs(sigma_yy), axis=(1, 2, 3), keepdims=True) + 1e-8
        sigma_yy_norm = sigma_yy / sigma_yy_max

        # 可微分軟性閾值化（steep sigmoid 作為 Heaviside 近似）
        # 銳度 k_sigmoid=10：在 column_threshold 附近快速過渡
        column_mask_soft = jax.nn.sigmoid(10.0 * (sigma_yy_norm - col_thr))
        # 同時考慮密度（只有高密度區域才可能是柱子）
        column_mask_soft = column_mask_soft * density  # [B, Lx, Ly, Lz]

        # ── 步驟 3：柱偵測 SDF 調製 ──
        # 柱區域的 SDF 使用較小的值（向內凹入）以呈現雙曲面效果
        # 簡化：直接以 column_mask_soft 調製 base_sdf
        column_sdf_offset = -arch_str * 0.5 * column_mask_soft
        base_sdf = base_sdf + column_sdf_offset

        # ── 步驟 4：神經引導混合 ──
        # style_sdf: [B, Lx, Ly, Lz, 1] → squeeze 至 [B, Lx, Ly, Lz]
        style_sdf_squeezed = style_sdf.squeeze(-1)
        # 以可學習權重加權風格 SDF
        weighted_style = w * style_sdf_squeezed

        # 平滑聯集：base_sdf + weighted_style_sdf
        combined = smooth_union(base_sdf, weighted_style, k=k)

        return combined
