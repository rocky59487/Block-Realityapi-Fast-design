"""
style_skin.py — 第三階段：風格皮膚應用

協調 GaudiStyle / ZahaStyle SDF 變形，
以及可選的 HYBR 風格條件化精修。

核心創新（論文貢獻 1 + 3）：
  - 「理性骨架 + 感性皮膚」解耦：結構最佳化與美學風格化為獨立管線階段
  - 純 SDF 操作實現高第/札哈風格（零訓練，純解析幾何語法）
  - HYBR 超網路提供可選的頻譜域風格條件化（inference-only）
"""
from __future__ import annotations
from dataclasses import dataclass
from typing import Literal
import numpy as np
from numpy.typing import NDArray

from ..config import RebornConfig
from ..models.gaudi_style import GaudiStyle
from ..models.zaha_style import ZahaStyle, blend_styles
from ..models.hybr_proxy import HYBRProxy
from ..utils.density_to_sdf import density_to_sdf_smooth
from .topo_optimizer import TopologyResult


@dataclass
class StyleResult:
    """第三階段輸出：風格化 SDF 與相關元數據"""
    sdf:            NDArray   # float32[Lx,Ly,Lz]（負值=內部）
    density_styled: NDArray   # float32[Lx,Ly,Lz]（風格化後密度場）
    style_name:     str       # "gaudi" / "zaha" / "none" / "hybrid"
    style_latent:   NDArray | None   # HYBR 風格潛在向量（若可用）
    iso_threshold:  float = 0.5
    voxel_size_m:   float = 1.0
    topology:       TopologyResult | None = None

    @property
    def shape(self) -> tuple[int, int, int]:
        return tuple(self.sdf.shape)


class StyleSkin:
    """
    第三階段：風格皮膚協調器。

    處理流程：
      1. TopologyResult.density → 基礎 SDF（density_to_sdf_smooth）
      2. 根據 config.style.mode 選擇 GaudiStyle 或 ZahaStyle
      3. 可選：HYBR 精修路徑（config.hybr.enabled）
      4. 輸出 StyleResult
    """

    def __init__(self, config: RebornConfig | None = None):
        self.config = config or RebornConfig()
        sc = self.config.style

        # 初始化風格模組
        self._gaudi = GaudiStyle(
            arch_strength=sc.gaudi_arch_strength,
            smin_k=sc.gaudi_smin_k,
            column_stress_thr=sc.gaudi_column_stress_thresh,
            verbose=self.config.verbose,
        ) if sc.mode in ("gaudi", "hybrid") else None

        self._zaha = ZahaStyle(
            flow_speed=sc.zaha_flow_alpha,
            verbose=self.config.verbose,
        ) if sc.mode in ("zaha", "hybrid") else None

        # HYBR 代理（可選）
        self._hybr: HYBRProxy | None = None
        if self.config.hybr.enabled:
            self._hybr = HYBRProxy(
                weights_dir=self.config.hybr.weights_dir,
                alpha=0.3,
                verbose=self.config.verbose,
            )

    def apply(self, topo: TopologyResult) -> StyleResult:
        """
        應用風格皮膚至拓撲結果。

        Args:
            topo: 第二階段輸出

        Returns:
            StyleResult — 風格化 SDF 與元數據
        """
        mode = self.config.style.mode
        density  = topo.density
        stress   = topo.stress_voigt

        if self.config.verbose:
            print(f"[StyleSkin] 模式：{mode}，網格：{density.shape}")

        # ----------------------------------------------------------------
        # 路徑 A：無風格（直接 SDF）
        # ----------------------------------------------------------------
        if mode == "none":
            sdf = density_to_sdf_smooth(
                density,
                iso=self.config.nurbs.iso_threshold,
                smooth_sigma=0.8,
            )
            return StyleResult(
                sdf=sdf, density_styled=density,
                style_name="none", style_latent=None,
                iso_threshold=self.config.nurbs.iso_threshold,
                topology=topo,
            )

        # ----------------------------------------------------------------
        # 路徑 B：HYBR 精修（若啟用且可用）
        # ----------------------------------------------------------------
        hybr_density = None
        style_latent = None
        if self._hybr and self._hybr.available:
            hybr_out = self._hybr.forward(density > 0.5, style=mode)
            if hybr_out is not None:
                # φ 通道作為密度調製信號（索引 9）
                phi_style = hybr_out[..., 9]
                phi_norm  = (phi_style - phi_style.min()) / (phi_style.ptp() + 1e-8)
                # 混合：70% SIMP 密度 + 30% HYBR φ 信號
                hybr_density = 0.7 * density + 0.3 * phi_norm * (density > 0.1)
                style_latent = self._hybr.get_style_latent(mode)
                if self.config.verbose:
                    print("[StyleSkin] HYBR 精修已應用")

        working_density = hybr_density if hybr_density is not None else density

        # ----------------------------------------------------------------
        # 路徑 C：SDF 幾何風格化
        # ----------------------------------------------------------------
        if mode == "gaudi" and self._gaudi is not None:
            sdf = self._gaudi.apply(working_density, stress)

        elif mode == "zaha" and self._zaha is not None:
            sdf = self._zaha.apply(working_density, stress)

        elif mode == "hybrid" and self._gaudi is not None and self._zaha is not None:
            sdf_gaudi = self._gaudi.apply(working_density, stress)
            sdf_zaha  = self._zaha.apply(working_density, stress)
            sdf = blend_styles(sdf_gaudi, sdf_zaha, alpha=0.5, smooth=0.5)
            mode = "hybrid"

        else:
            # 退回：直接 SDF
            sdf = density_to_sdf_smooth(working_density, iso=0.5, smooth_sigma=0.8)

        return StyleResult(
            sdf=sdf,
            density_styled=working_density,
            style_name=mode,
            style_latent=style_latent,
            iso_threshold=self.config.nurbs.iso_threshold,
            voxel_size_m=1.0,
            topology=topo,
        )
