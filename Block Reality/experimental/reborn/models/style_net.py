"""
style_net.py — StyleConditionedSSGO：風格條件化頻譜神經算子

論文核心貢獻：擴展 HYBR AdaptiveSSGO，新增可訓練風格嵌入層，
使頻譜權重同時受幾何結構和建築風格條件化。

架構：
  輸入 [B,L,L,L,6] + style_id [B]
       │
  SpectralGeometryEncoder(occ) → z_geom [B,d]
  StyleEmbedding(style_id)     → z_style [B,d]
       │
  z = z_geom + α·z_style        （α 為 softplus 門控可學習參數）
       │
  HyperMLP(z) → SpectralWeightHead → CP 因子
  SpectralWeightBank(W_base + α·ΔW)
       │
  Global FNO + Focal VAG + 門控融合 + Backbone FNO
       │
  ┌─ 應力頭(6) ── 位移頭(3) ── phi頭(1) ── 風格SDF頭(1)
  └─ 輸出 [B,L,L,L,11]

關鍵設計：
  - 風格嵌入僅 n_styles × latent_dim 個參數（預設 4×32=128）
  - 加法注入避免重訓 HyperMLP，CP 界定確保 ||ΔW|| / ||W_base|| < 0.3
  - 第 11 通道 style_sdf 為學習到的 SDF 形變場

參考：
  HYBR/hybr/core/adaptive_ssgo.py — 父架構
  HYBR/hybr/core/weight_bank.py   — CP 分解權重銀行
  Ortiz et al. — 潛在空間超網路最佳化
"""
from __future__ import annotations

import sys
from pathlib import Path
from typing import Sequence

# 確保 HYBR 與 BR-NeXT 可匯入
_REPO_ROOT = Path(__file__).resolve().parent.parent.parent.parent.parent
for _p in [str(_REPO_ROOT / "HYBR"), str(_REPO_ROOT / "BR-NeXT"), str(_REPO_ROOT / "brml")]:
    if _p not in sys.path:
        sys.path.insert(0, _p)

import jax
import jax.numpy as jnp
import flax.linen as nn

# 匯入 HYBR 核心元件（不重建）
from hybr.core.geometry_encoder import SpectralGeometryEncoder
from hybr.core.hypernet import HyperMLP, SpectralWeightHead
from hybr.core.weight_bank import SpectralWeightBank
from hybr.core.adaptive_ssgo import AdaptiveWeightedSpectralConv3D, AdaptiveFNOBlock

# 匯入 BR-NeXT 元件
from brnext.models.voxel_gat import SparseVoxelGraphConv
from brnext.models.moe_head import MoESpectralHead


# ---------------------------------------------------------------------------
# 風格嵌入
# ---------------------------------------------------------------------------

class StyleEmbedding(nn.Module):
    """可訓練風格嵌入表。

    將整數風格代碼映射至潛在向量。
    這些是實現零樣本新風格遷移時唯一需要更新的參數。

    Attributes:
        n_styles:   風格總數（預設 4：raw/gaudi/zaha/hybrid）
        latent_dim: 潛在向量維度
    """
    n_styles: int = 4
    latent_dim: int = 32

    @nn.compact
    def __call__(self, style_id: jnp.ndarray) -> jnp.ndarray:
        """
        Args:
            style_id: [B] 整數風格代碼
        Returns:
            z_style: [B, latent_dim]
        """
        table = self.param(
            "style_table",
            nn.initializers.normal(stddev=0.02),
            (self.n_styles, self.latent_dim),
        )
        return table[style_id]


# ---------------------------------------------------------------------------
# StyleConditionedSSGO — 論文核心貢獻
# ---------------------------------------------------------------------------

class StyleConditionedSSGO(nn.Module):
    """風格條件化頻譜幾何神經算子。

    擴展 AdaptiveSSGO：風格嵌入調製 HyperNet 的頻譜權重生成，
    使同一模型能根據風格代碼產生不同的幾何形變。

    輸出 11 通道：stress(6) + disp(3) + phi(1) + style_sdf(1)

    Attributes:
        hidden:            FNO 隱藏通道數
        modes:             傅立葉模式數
        n_global_layers:   全域 FNO 層數
        n_focal_layers:    局部 VAG 層數
        n_backbone_layers: 主幹 FNO 層數
        moe_hidden:        MoE 頭隱藏維度
        latent_dim:        幾何/風格潛在向量維度
        hypernet_widths:   HyperMLP 隱藏層寬度
        rank:              CP 分解秩
        n_styles:          風格總數
        encoder_type:      幾何編碼器類型（"spectral" 或 "cnn"）
        style_alpha_init:  風格擾動初始強度
    """
    hidden: int = 48
    modes: int = 6
    n_global_layers: int = 3
    n_focal_layers: int = 2
    n_backbone_layers: int = 2
    moe_hidden: int = 32
    latent_dim: int = 32
    hypernet_widths: Sequence[int] = (128, 128)
    rank: int = 2
    n_styles: int = 4
    encoder_type: str = "spectral"
    style_alpha_init: float = 0.3

    @nn.compact
    def __call__(
        self,
        x: jnp.ndarray,
        style_id: jnp.ndarray,
        update_stats: bool = True,
    ) -> jnp.ndarray:
        """
        Args:
            x:        [B, L, L, L, 6] 佔用 + 材料場
            style_id: [B] 整數風格代碼
            update_stats: 傳遞至 SpectralNorm 層（訓練時 True）

        Returns:
            [B, L, L, L, 11] = [stress(6), disp(3), phi(1), style_sdf(1)]
        """
        occ = x[..., 0:1]                    # [B, L, L, L, 1]
        occ_squeezed = occ.squeeze(-1)        # [B, L, L, L]

        # ── 幾何編碼 ──
        if self.encoder_type == "spectral":
            z_geom = SpectralGeometryEncoder(self.latent_dim)(occ_squeezed)
        else:
            from hybr.core.geometry_encoder import LightweightCNNEncoder
            z_geom = LightweightCNNEncoder(self.latent_dim)(occ_squeezed)

        # ── 風格編碼 ──
        z_style = StyleEmbedding(self.n_styles, self.latent_dim)(style_id)

        # ── 風格注入：加法融合 + 可學習門控 ──
        style_alpha = self.param(
            "style_alpha",
            nn.initializers.constant(self.style_alpha_init),
            (),
        )
        alpha = jax.nn.softplus(style_alpha)  # 確保正值
        z = z_geom + alpha * z_style          # [B, latent_dim]

        # ── HyperNet 主幹 ──
        hyper_features = HyperMLP(
            hidden_widths=self.hypernet_widths,
            latent_dim=self.latent_dim,
        )(z, update_stats=update_stats)

        # ── 頻譜權重生成輔助函式 ──
        L = x.shape[1]
        mx = min(self.modes, L)
        my = min(self.modes, L)
        mz = min(self.modes, L // 2 + 1)

        def make_spectral_weights() -> tuple:
            """生成一組自適應頻譜權重（實部 + 虛部）。"""
            head_r = SpectralWeightHead(
                rank=self.rank,
                in_channels=self.hidden,
                out_channels=self.hidden,
                mx=mx, my=my, mz=mz,
                generate_mode_w=True,
            )
            head_i = SpectralWeightHead(
                rank=self.rank,
                in_channels=self.hidden,
                out_channels=self.hidden,
                mx=mx, my=my, mz=mz,
                generate_mode_w=False,
            )
            cp_r = head_r(hyper_features)
            cp_i = head_i(hyper_features)
            mode_delta = cp_r.pop("mode_w_delta")
            weights, mode_w = SpectralWeightBank(
                in_channels=self.hidden,
                out_channels=self.hidden,
                mx=mx, my=my, mz=mz,
            )(cp_r, cp_i, mode_delta)
            return weights, mode_w

        # ── 全域 FNO 分支 ──
        g = nn.Dense(self.hidden)(x)
        for _ in range(self.n_global_layers):
            w, mw = make_spectral_weights()
            g = AdaptiveFNOBlock(self.hidden, self.modes)(g, w, mw)

        # ── 局部 VAG 分支 ──
        f = nn.Dense(self.hidden)(x)
        for _ in range(self.n_focal_layers):
            f = SparseVoxelGraphConv(self.hidden)(f, occ_squeezed)

        # ── 門控融合 ──
        concat = jnp.concatenate([g, f], axis=-1)
        gate = jax.nn.sigmoid(nn.Dense(1)(concat))
        fused = gate * g + (1.0 - gate) * f

        # ── 共用主幹 ──
        h = fused
        for _ in range(self.n_backbone_layers):
            w, mw = make_spectral_weights()
            h = AdaptiveFNOBlock(self.hidden, self.modes)(h, w, mw)

        # ── MoE 頻譜輸出頭 ──
        moe_out = MoESpectralHead(
            out_channels=self.hidden,
            hidden=self.moe_hidden,
        )(h)

        # ── 物理任務頭（與 AdaptiveSSGO 相同） ──
        head_w = max(self.hidden, 32)

        s = nn.Dense(head_w)(moe_out)
        s = nn.gelu(s)
        stress = nn.Dense(6)(s)

        d = nn.Dense(head_w)(moe_out)
        d = nn.gelu(d)
        disp = nn.Dense(3)(d)

        p = nn.Dense(head_w)(moe_out)
        p = nn.gelu(p)
        phi = nn.Dense(1)(p)

        # ── 風格 SDF 頭（新增） ──
        # 使用 tanh 界定輸出範圍 [-1, 1]，代表 SDF 形變幅度
        sdf_h = nn.Dense(head_w)(moe_out)
        sdf_h = nn.gelu(sdf_h)
        # 加入風格嵌入的殘差連接（讓 SDF 頭直接感知風格）
        z_style_broadcast = jnp.broadcast_to(
            z_style[:, None, None, None, :],
            (*sdf_h.shape[:-1], z_style.shape[-1]),
        )
        sdf_h = jnp.concatenate([sdf_h, z_style_broadcast], axis=-1)
        sdf_h = nn.Dense(head_w)(sdf_h)
        sdf_h = nn.gelu(sdf_h)
        style_sdf = jnp.tanh(nn.Dense(1)(sdf_h))

        # ── 組合輸出 ──
        out = jnp.concatenate([stress, disp, phi, style_sdf], axis=-1)
        return out * occ  # 遮罩空氣區域


# ---------------------------------------------------------------------------
# 風格判別器（對抗訓練用）
# ---------------------------------------------------------------------------

class StyleDiscriminator(nn.Module):
    """基於 3D 卷積的風格判別器。

    接受 SDF 場 + 風格代碼，輸出真/假邏輯值。
    輕量設計（~50k 參數），用於對抗精修階段。

    Attributes:
        hidden:    隱藏通道數
        n_styles:  風格總數
        latent_dim: 風格嵌入維度
    """
    hidden: int = 32
    n_styles: int = 4
    latent_dim: int = 16

    @nn.compact
    def __call__(
        self,
        sdf: jnp.ndarray,
        style_id: jnp.ndarray,
    ) -> jnp.ndarray:
        """
        Args:
            sdf:      [B, L, L, L, 1] SDF 場
            style_id: [B] 風格代碼

        Returns:
            logit: [B, 1] 真/假邏輯值
        """
        # 風格嵌入
        style_table = self.param(
            "disc_style_table",
            nn.initializers.normal(stddev=0.02),
            (self.n_styles, self.latent_dim),
        )
        z_style = style_table[style_id]  # [B, latent_dim]

        # 3D 卷積特徵提取
        h = sdf
        h = nn.Conv(self.hidden, kernel_size=(3, 3, 3))(h)
        h = nn.leaky_relu(h, negative_slope=0.2)

        h = nn.Conv(self.hidden * 2, kernel_size=(3, 3, 3), strides=(2, 2, 2))(h)
        h = nn.leaky_relu(h, negative_slope=0.2)

        h = nn.Conv(self.hidden * 4, kernel_size=(3, 3, 3), strides=(2, 2, 2))(h)
        h = nn.leaky_relu(h, negative_slope=0.2)

        # 全域平均池化
        h = h.mean(axis=(1, 2, 3))  # [B, hidden*4]

        # 與風格嵌入拼接
        h = jnp.concatenate([h, z_style], axis=-1)

        # 分類頭
        h = nn.Dense(self.hidden * 2)(h)
        h = nn.leaky_relu(h, negative_slope=0.2)
        logit = nn.Dense(1)(h)

        return logit
