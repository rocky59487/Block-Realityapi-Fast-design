"""
zaha_style.py — 札哈·哈蒂風格 SDF 變形模組

實現哈蒂建築的流線形式語言：
  1. 等值面沿最小主應力方向的水平集平流（Level-Set Advection）
  2. 參數化帶狀面調製（Ribbon Surface Modulation）
  3. 邊緣柔化（Edge Softening）— 消除 Minecraft 直角感

設計理念：
  結構流動性 — 形式服從力流（force flow），
  但以連續平滑曲面呈現，而非剛性幾何形狀。

所有操作均為純 SDF / scipy 運算，無需 GPU 或訓練。

參考：
  Leach (2009), "The Language of Space: Form and Parametrics in the Work of Zaha Hadid"
  Schumacher (2009), "Parametricism: A New Global Style for Architecture and Urban Design"
  Sethian (1999), "Level Set Methods and Fast Marching Methods" — 水平集方法理論
"""
from __future__ import annotations
import numpy as np
from numpy.typing import NDArray
from scipy.ndimage import map_coordinates, gaussian_filter

from ..utils.density_to_sdf import density_to_sdf_smooth
from ..utils.stress_tensor import min_principal
from .stress_path import extract_principal_stress_paths, filter_flow_paths


class ZahaStyle:
    """
    札哈·哈蒂風格 SDF 變形模組。

    處理管線：
      1. 密度場 → 基礎 SDF
      2. 沿最小主應力方向進行半拉格朗日水平集平流
      3. 在垂直於流場方向添加帶狀波浪調製
      4. 高斯平滑消除高頻雜訊（邊緣柔化）

    參數說明：
      flow_steps:   平流迭代步數（越多 = 越大的形變）
      flow_speed:   平流速度係數（控制每步的形變量）
      ribbon_period:帶狀面波長（體素）
      ribbon_amp:   帶狀面振幅（體素）
      smooth_sigma: 邊緣柔化高斯 sigma
    """

    def __init__(
        self,
        flow_steps:    int   = 8,
        flow_speed:    float = 0.25,
        ribbon_period: float = 6.0,
        ribbon_amp:    float = 0.4,
        smooth_sigma:  float = 1.0,
        verbose:       bool  = False,
    ):
        self.flow_steps    = flow_steps
        self.flow_speed    = flow_speed
        self.ribbon_period = ribbon_period
        self.ribbon_amp    = ribbon_amp
        self.smooth_sigma  = smooth_sigma
        self.verbose       = verbose

    def apply(
        self,
        density:     NDArray,
        stress_voigt: NDArray,
    ) -> NDArray:
        """
        將札哈風格變形應用於密度場，回傳風格化 SDF。

        Args:
            density:      float32[Lx,Ly,Lz] — SIMP 輸出密度場
            stress_voigt: float32[Lx,Ly,Lz,6] — Voigt 應力場

        Returns:
            float32[Lx,Ly,Lz] — 札哈風格化 SDF
        """
        # 步驟 0：基礎 SDF
        sdf = density_to_sdf_smooth(density, iso=0.5, smooth_sigma=1.0)

        # 步驟 1：計算最小主應力方向場（張拉流線方向）
        _, min_dirs = min_principal(stress_voigt)   # [Lx,Ly,Lz,3]
        # 以密度加權：只在結構材料內流動
        velocity = min_dirs * density[..., None] * self.flow_speed

        # 步驟 2：半拉格朗日水平集平流
        sdf = self._semi_lagrangian_advect(sdf, velocity, self.flow_steps)
        if self.verbose:
            print(f"[ZahaStyle] 平流完成，SDF 範圍：[{sdf.min():.2f}, {sdf.max():.2f}]")

        # 步驟 3：帶狀面調製（沿流線方向週期性波浪）
        flow_paths = extract_principal_stress_paths(
            stress_voigt, density, n_seeds=16, stress_type="min"
        )
        flow_paths = filter_flow_paths(flow_paths)
        if len(flow_paths) > 0:
            sdf = self._add_ribbon_modulation(sdf, flow_paths, min_dirs)
            if self.verbose:
                print(f"[ZahaStyle] 帶狀調製作用於 {len(flow_paths)} 條流線")

        # 步驟 4：邊緣柔化
        sdf = gaussian_filter(sdf, sigma=self.smooth_sigma)

        return sdf.astype(np.float32)

    # -----------------------------------------------------------------------
    # 半拉格朗日水平集平流
    # -----------------------------------------------------------------------

    def _semi_lagrangian_advect(
        self,
        phi:      NDArray,
        velocity: NDArray,
        n_steps:  int,
    ) -> NDArray:
        """
        半拉格朗日平流方案：∂φ/∂τ + v·∇φ = 0

        演算法（Stam 1999 穩定流體方法）：
          對每個格點 p，向後追蹤：p_back = p - dt·v(p)
          從 p_back 三線性插值 φ

        CFL 條件：dt 自動選取使最大位移 < 0.5 體素。
        """
        Sx, Sy, Sz = phi.shape

        # 建立格點座標矩陣
        xs = np.arange(Sx, dtype=np.float32)
        ys = np.arange(Sy, dtype=np.float32)
        zs = np.arange(Sz, dtype=np.float32)
        X, Y, Z = np.meshgrid(xs, ys, zs, indexing="ij")

        # 自適應 dt（CFL）
        v_max = float(np.abs(velocity).max()) + 1e-8
        dt = 0.4 / v_max   # 0.4 體素/步

        for step in range(n_steps):
            # 反向追蹤
            X_back = np.clip(X - dt * velocity[..., 0], 0, Sx - 1)
            Y_back = np.clip(Y - dt * velocity[..., 1], 0, Sy - 1)
            Z_back = np.clip(Z - dt * velocity[..., 2], 0, Sz - 1)

            coords = np.array([
                X_back.ravel(),
                Y_back.ravel(),
                Z_back.ravel(),
            ])
            phi = map_coordinates(phi, coords, order=1, mode="nearest").reshape(phi.shape)

        return phi

    # -----------------------------------------------------------------------
    # 帶狀面調製
    # -----------------------------------------------------------------------

    def _add_ribbon_modulation(
        self,
        sdf:       NDArray,
        paths:     list[NDArray],
        min_dirs:  NDArray,
    ) -> NDArray:
        """
        在 SDF 等值面附近添加流線平行的週期性帶狀調製。

        原理：計算每個體素在流線上的弧長投影，
              根據弧長添加正弦偏移到 SDF。
        效果：平滑流線面上出現哈蒂特徵的縱向帶狀紋理。
        """
        Lx, Ly, Lz = sdf.shape
        ribbon_offset = np.zeros_like(sdf)

        xx = np.arange(Lx, dtype=np.float32)
        yy = np.arange(Ly, dtype=np.float32)
        zz = np.arange(Lz, dtype=np.float32)
        X, Y, Z = np.meshgrid(xx, yy, zz, indexing="ij")
        pts = np.stack([X, Y, Z], axis=-1)  # [Lx,Ly,Lz,3]

        for path in paths[:min(len(paths), 8)]:  # 最多 8 條
            if len(path) < 4:
                continue
            # 計算每個體素到路徑的最近點距離
            # 使用簡化的批次距離計算（路徑下採樣）
            path_ds = path[::max(1, len(path) // 32)]  # 下採樣
            diffs = pts[..., None, :] - path_ds[None, None, None, :, :]  # [Lx,Ly,Lz,N,3]
            dists = np.linalg.norm(diffs, axis=-1)  # [Lx,Ly,Lz,N]
            nearest_idx = np.argmin(dists, axis=-1)  # [Lx,Ly,Lz]
            min_dist = dists.min(axis=-1)            # [Lx,Ly,Lz]

            # 弧長（沿路徑）
            arc_params = np.linspace(0, 1, len(path_ds))
            arc_at_nearest = arc_params[nearest_idx]  # [Lx,Ly,Lz]

            # 正弦調製（只在靠近路徑 ± ribbon_period 的地方）
            influence = np.exp(-0.5 * (min_dist / (self.ribbon_period * 0.5)) ** 2)
            mod = self.ribbon_amp * np.sin(2 * np.pi * arc_at_nearest * 3) * influence
            ribbon_offset += mod

        # 只在等值面附近 (|SDF| < 2*ribbon_amp) 添加調製
        near_surface = np.abs(sdf) < 2.0 * self.ribbon_amp * 3
        sdf_modulated = np.where(near_surface, sdf + ribbon_offset, sdf)
        return sdf_modulated.astype(np.float32)


# -----------------------------------------------------------------------
# 組合風格：混合高第與札哈
# -----------------------------------------------------------------------

def blend_styles(
    sdf_a:  NDArray,
    sdf_b:  NDArray,
    alpha:  float = 0.5,
    smooth: float = 0.5,
) -> NDArray:
    """
    線性混合兩種風格的 SDF。

    alpha=0.0 → 純 sdf_a（高第）
    alpha=1.0 → 純 sdf_b（札哈）

    Args:
        smooth: 混合前的高斯平滑 sigma
    """
    if smooth > 0:
        a = gaussian_filter(sdf_a.astype(np.float64), sigma=smooth)
        b = gaussian_filter(sdf_b.astype(np.float64), sigma=smooth)
    else:
        a, b = sdf_a.astype(np.float64), sdf_b.astype(np.float64)
    return ((1 - alpha) * a + alpha * b).astype(np.float32)
