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

依賴（由 reborn_ml 包的 __init__.py 自動發現）：
  hybr.core.geometry_encoder, hybr.core.hypernet, hybr.core.weight_bank
  hybr.core.adaptive_ssgo, brnext.models.voxel_gat, brnext.models.moe_head
"""
from __future__ import annotations

from typing import Sequence

import jax
import jax.numpy as jnp
import flax.linen as nn

# 匯入 HYBR 核心元件（由 reborn_ml.__init__ 設好 sys.path）
from hybr.core.geometry_encoder import SpectralGeometryEncoder
from hybr.core.hypernet import HyperMLP, SpectralWeightHead
from hybr.core.weight_bank import SpectralWeightBank
from hybr.core.adaptive_ssgo import AdaptiveWeightedSpectralConv3D, AdaptiveFNOBlock

# 匯入 BR-NeXT 元件
from brnext.models.voxel_gat import SparseVoxelGraphConv
from brnext.models.moe_head import MoESpectralHead


class StyleEmbedding(nn.Module):
    """可訓練風格嵌入表。"""
    n_styles: int = 4
    latent_dim: int = 32

    @nn.compact
    def __call__(self, style_id: jnp.ndarray) -> jnp.ndarray:
        table = self.param(
            "style_table",
            nn.initializers.normal(stddev=0.02),
            (self.n_styles, self.latent_dim),
        )
        return table[style_id]


class StyleConditionedSSGO(nn.Module):
    """風格條件化頻譜幾何神經算子。

    輸出 11 通道：stress(6) + disp(3) + phi(1) + style_sdf(1)
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
            [B, L, L, L, 11]
        """
        occ = x[..., 0:1]
        occ_squeezed = occ.squeeze(-1)

        if self.encoder_type == "spectral":
            z_geom = SpectralGeometryEncoder(self.latent_dim)(occ_squeezed)
        else:
            from hybr.core.geometry_encoder import LightweightCNNEncoder
            z_geom = LightweightCNNEncoder(self.latent_dim)(occ_squeezed)

        z_style = StyleEmbedding(self.n_styles, self.latent_dim)(style_id)

        style_alpha = self.param(
            "style_alpha",
            nn.initializers.constant(self.style_alpha_init),
            (),
        )
        alpha = jax.nn.softplus(style_alpha)
        z = z_geom + alpha * z_style

        hyper_features = HyperMLP(
            hidden_widths=self.hypernet_widths,
            latent_dim=self.latent_dim,
        )(z, update_stats=update_stats)

        L = x.shape[1]
        mx = min(self.modes, L)
        my = min(self.modes, L)
        mz = min(self.modes, L // 2 + 1)

        def make_spectral_weights() -> tuple:
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

        # Global FNO
        g = nn.Dense(self.hidden)(x)
        for _ in range(self.n_global_layers):
            w, mw = make_spectral_weights()
            g = AdaptiveFNOBlock(self.hidden, self.modes)(g, w, mw)

        # Focal VAG
        f = nn.Dense(self.hidden)(x)
        for _ in range(self.n_focal_layers):
            f = SparseVoxelGraphConv(self.hidden)(f, occ_squeezed)

        # Gated fusion
        concat = jnp.concatenate([g, f], axis=-1)
        gate = jax.nn.sigmoid(nn.Dense(1)(concat))
        fused = gate * g + (1.0 - gate) * f

        # Backbone FNO
        h = fused
        for _ in range(self.n_backbone_layers):
            w, mw = make_spectral_weights()
            h = AdaptiveFNOBlock(self.hidden, self.modes)(h, w, mw)

        # MoE head
        moe_out = MoESpectralHead(out_channels=self.hidden, hidden=self.moe_hidden)(h)

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

        # Style SDF head
        sdf_h = nn.Dense(head_w)(moe_out)
        sdf_h = nn.gelu(sdf_h)
        z_style_broadcast = jnp.broadcast_to(
            z_style[:, None, None, None, :],
            (*sdf_h.shape[:-1], z_style.shape[-1]),
        )
        sdf_h = jnp.concatenate([sdf_h, z_style_broadcast], axis=-1)
        sdf_h = nn.Dense(head_w)(sdf_h)
        sdf_h = nn.gelu(sdf_h)
        style_sdf = jnp.tanh(nn.Dense(1)(sdf_h))

        out = jnp.concatenate([stress, disp, phi, style_sdf], axis=-1)
        return out * occ


class StyleDiscriminator(nn.Module):
    """基於 3D 卷積的風格判別器（~50k 參數）。"""
    hidden: int = 32
    n_styles: int = 4
    latent_dim: int = 16

    @nn.compact
    def __call__(self, sdf: jnp.ndarray, style_id: jnp.ndarray) -> jnp.ndarray:
        """
        Args:
            sdf:      [B, L, L, L, 1]
            style_id: [B]
        Returns:
            logit: [B, 1]
        """
        style_table = self.param(
            "disc_style_table",
            nn.initializers.normal(stddev=0.02),
            (self.n_styles, self.latent_dim),
        )
        z_style = style_table[style_id]

        h = sdf
        h = nn.Conv(self.hidden, kernel_size=(3, 3, 3))(h)
        h = nn.leaky_relu(h, negative_slope=0.2)
        h = nn.Conv(self.hidden * 2, kernel_size=(3, 3, 3), strides=(2, 2, 2))(h)
        h = nn.leaky_relu(h, negative_slope=0.2)
        h = nn.Conv(self.hidden * 4, kernel_size=(3, 3, 3), strides=(2, 2, 2))(h)
        h = nn.leaky_relu(h, negative_slope=0.2)

        h = h.mean(axis=(1, 2, 3))
        h = jnp.concatenate([h, z_style], axis=-1)
        h = nn.Dense(self.hidden * 2)(h)
        h = nn.leaky_relu(h, negative_slope=0.2)
        return nn.Dense(1)(h)
