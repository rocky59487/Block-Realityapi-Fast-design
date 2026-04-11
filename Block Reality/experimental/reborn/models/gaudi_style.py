"""
gaudi_style.py — 高第風格 SDF 變形模組

實現高第建築的三大幾何語言：
  1. 懸鏈線拱（Catenary arch）— 純重力載荷下的自然拱形
  2. 單葉雙曲面柱（Hyperboloid of one sheet）— 高第聖家堂柱子形式
  3. 仿生肋骨紋理（Biomimetic ribbing）— 沿應力路徑的波浪狀加肋

所有操作均為純 SDF 代數運算（numpy/scipy），無需 GPU 或訓練。

參考：
  Huerta (2003), "Structural Design in the Work of Gaudí" — 懸鏈線設計方法
  Burry (1993), "Expiatory Church of the Sagrada Família" — 雙曲面柱幾何
  Oxman (2010), "Performance-Based Design: Current Practices and Research Issues"
"""
from __future__ import annotations
import numpy as np
from numpy.typing import NDArray
from scipy.optimize import minimize_scalar
from scipy.ndimage import gaussian_filter

from ..utils.density_to_sdf import (
    sdf_smooth_union, sdf_hyperboloid, sdf_catenary_arch, density_to_sdf_smooth
)
from .stress_path import (
    extract_principal_stress_paths, filter_arch_paths, classify_path_morphology
)


class GaudiStyle:
    """
    高第風格 SDF 變形模組。

    設計哲學：
      - 懸鏈線拱沿主壓縮應力路徑生成（力學與形式的直接對應）
      - 雙曲面柱在高垂直載荷的節點處生成
      - 仿生肋骨在拱的側面添加波浪狀紋理

    參數說明：
      arch_strength:    懸鏈線拱的 SDF 半徑（體素）
      smin_k:           平滑聯集混合半徑（越大 = 更平滑的接合）
      column_stress_thr: 觸發雙曲面柱的垂直壓力閾值（歸一化）
      rib_amplitude:    肋骨波浪振幅（體素）
      rib_wavelength:   肋骨波長（體素）
    """

    def __init__(
        self,
        arch_strength:       float = 1.5,
        smin_k:              float = 0.3,
        column_stress_thr:   float = 0.65,
        rib_amplitude:       float = 0.35,
        rib_wavelength:      float = 4.0,
        column_radius:       float = 1.2,
        verbose:             bool  = False,
    ):
        self.arch_strength     = arch_strength
        self.smin_k            = smin_k
        self.column_stress_thr = column_stress_thr
        self.rib_amplitude     = rib_amplitude
        self.rib_wavelength    = rib_wavelength
        self.column_radius     = column_radius
        self.verbose           = verbose

    def apply(
        self,
        density:     NDArray,
        stress_voigt: NDArray,
    ) -> NDArray:
        """
        將高第風格變形應用於密度場，回傳風格化 SDF。

        Args:
            density:      float32[Lx,Ly,Lz] — SIMP 輸出密度場
            stress_voigt: float32[Lx,Ly,Lz,6] — Voigt 應力場

        Returns:
            float32[Lx,Ly,Lz] — 高第風格化 SDF
        """
        # 步驟 0：密度場 → 基礎 SDF
        sdf = density_to_sdf_smooth(density, iso=0.5, smooth_sigma=0.8)

        # 步驟 1：提取主壓縮應力路徑
        paths = extract_principal_stress_paths(
            stress_voigt, density,
            n_seeds=32,
            stress_type="max",   # 主壓縮方向 → 懸鏈拱
        )
        arch_paths = filter_arch_paths(paths)
        if self.verbose:
            print(f"[GaudiStyle] 找到 {len(paths)} 條路徑，{len(arch_paths)} 條弧形")

        # 步驟 2：懸鏈線拱 SDF
        for path in arch_paths:
            catenary_params = self._fit_catenary(path)
            if catenary_params is not None:
                arch_sdf = self._make_catenary_sdf(sdf.shape, path, catenary_params)
                # 平滑聯集：保留原始骨架 + 添加懸鏈線
                sdf = sdf_smooth_union(sdf, arch_sdf, k=self.smin_k)

        # 步驟 3：雙曲面柱
        col_centers = self._find_column_centers(density, stress_voigt)
        for cx, cy, cz, height in col_centers:
            hyp_sdf = sdf_hyperboloid(
                sdf.shape,
                center=np.array([cx, cy, cz]),
                a=self.column_radius,
                c=height * 0.5,
            )
            sdf = sdf_smooth_union(sdf, hyp_sdf, k=self.smin_k * 0.5)

        # 步驟 4：仿生肋骨紋理（輕量版：沿路徑添加波浪）
        if len(arch_paths) > 0:
            sdf = self._add_ribbing(sdf, arch_paths[:min(len(arch_paths), 6)])

        # 步驟 5：輕微高斯平滑（消除殘餘鋸齒）
        sdf = gaussian_filter(sdf, sigma=0.3)

        return sdf.astype(np.float32)

    # -----------------------------------------------------------------------
    # 懸鏈線擬合
    # -----------------------------------------------------------------------

    def _fit_catenary(self, path: NDArray) -> dict | None:
        """
        對路徑擬合懸鏈線 y = a·cosh((x-x0)/a) + y_offset。

        投影至路徑端點所定義的垂直平面進行 2D 擬合。
        回傳 None 若路徑無法有效擬合。
        """
        if len(path) < 4:
            return None

        p0, p1 = path[0], path[-1]
        # 水平跨距（XZ 平面）
        horiz_vec = p1 - p0
        horiz_vec[1] = 0
        span = float(np.linalg.norm(horiz_vec))
        if span < 1.0:
            return None

        # 局部座標：u = 沿水平方向投影，v = 高度
        horiz_unit = horiz_vec / (span + 1e-8)
        u_local = np.array([np.dot(pt - p0, horiz_unit) for pt in path])
        v_local = path[:, 1] - p0[1]  # 高度差

        # 懸鏈線擬合（最小化殘差）
        def residual(a: float) -> float:
            u_norm = u_local - span / 2
            v_pred = a * (np.cosh(u_norm / (a + 1e-6)) - 1)
            return float(np.mean((v_pred - v_local) ** 2))

        result = minimize_scalar(residual, bounds=(0.5, max(span * 2, 5.0)), method="bounded")
        if result.fun > (np.std(v_local) ** 2) * 2:
            return None   # 擬合品質差

        return {
            "a": result.x,
            "p0": p0,
            "p1": p1,
            "span": span,
            "horiz_unit": horiz_unit,
        }

    def _make_catenary_sdf(
        self,
        shape: tuple[int, int, int],
        path: NDArray,
        params: dict,
    ) -> NDArray:
        """使用 sdf_catenary_arch 建立懸鏈線 SDF"""
        return sdf_catenary_arch(
            shape,
            p0=params["p0"],
            p1=params["p1"],
            a_param=params["a"],
            thickness=self.arch_strength,
        )

    # -----------------------------------------------------------------------
    # 雙曲面柱
    # -----------------------------------------------------------------------

    def _find_column_centers(
        self,
        density: NDArray,
        stress_voigt: NDArray,
    ) -> list[tuple[float, float, float, float]]:
        """
        找出需要生成雙曲面柱的位置。

        條件：高密度 + 高垂直壓縮應力 + 位於底部附近
        回傳：[(cx, cy, cz, height), ...]
        """
        Lx, Ly, Lz = density.shape

        # 垂直壓縮應力（σ_yy，壓縮為負）
        sigma_yy = stress_voigt[..., 1]
        sigma_yy_norm = -sigma_yy / (np.abs(sigma_yy).max() + 1e-8)   # 壓縮為正

        # 只考慮底部 1/3 高度，高密度、高垂直壓縮的體素
        bottom_slice = slice(0, Ly // 3)
        cand_mask = (
            (density[:, bottom_slice, :] > 0.6) &
            (sigma_yy_norm[:, bottom_slice, :] > self.column_stress_thr)
        )
        cand_pts = np.argwhere(cand_mask)
        if len(cand_pts) == 0:
            return []

        # 使用簡單聚類（基於距離閾值）
        centers = []
        used = np.zeros(len(cand_pts), dtype=bool)
        cluster_r = max(Lx, Lz) * 0.15   # 聚類半徑

        for i, pt in enumerate(cand_pts):
            if used[i]:
                continue
            # 找鄰近點
            dists = np.linalg.norm(cand_pts[:, [0, 2]] - pt[[0, 2]], axis=1)
            members = dists < cluster_r
            cluster = cand_pts[members]
            cx = float(cluster[:, 0].mean())
            cz = float(cluster[:, 2].mean())
            # 柱子高度：從底部到密度 < 0.3 的第一層
            col_density = density[int(cx), :, int(cz)]
            col_top = np.argmax(col_density < 0.3)
            height = float(col_top if col_top > 0 else Ly // 2)
            centers.append((cx, 0.0, cz, height))
            used[members] = True

        return centers[:8]  # 最多 8 根柱子

    # -----------------------------------------------------------------------
    # 仿生肋骨
    # -----------------------------------------------------------------------

    def _add_ribbing(self, sdf: NDArray, arch_paths: list[NDArray]) -> NDArray:
        """
        沿拱形路徑添加波浪狀肋骨紋理。

        方法：在每個路徑點的切向垂直平面上添加餘弦調製 SDF 偏移。
        結果：類似龍骨的連續肋骨狀凸起。
        """
        Lx, Ly, Lz = sdf.shape
        rib_sdf = np.zeros_like(sdf) + 1e6   # 初始化為「遠」

        xx = np.arange(Lx, dtype=np.float32)
        yy = np.arange(Ly, dtype=np.float32)
        zz = np.arange(Lz, dtype=np.float32)
        X, Y, Z = np.meshgrid(xx, yy, zz, indexing="ij")
        pts_xyz = np.stack([X, Y, Z], axis=-1)  # [Lx,Ly,Lz,3]

        for path in arch_paths:
            if len(path) < 2:
                continue
            # 路徑弧長參數化
            diffs = np.diff(path, axis=0)
            lengths = np.linalg.norm(diffs, axis=1)
            arc_len = np.concatenate([[0], np.cumsum(lengths)])
            total_len = arc_len[-1]
            if total_len < 1e-6:
                continue

            # 對每個路徑點計算到最近路徑點的距離，並添加正弦調製
            for i in range(0, len(path) - 1, max(1, len(path) // 16)):
                pt = path[i]
                t = arc_len[i] / total_len
                # 距離場
                dist_to_pt = np.linalg.norm(pts_xyz - pt, axis=-1)  # [Lx,Ly,Lz]
                # 餘弦調製：沿弧長方向週期性
                phase = 2 * np.pi * t * (total_len / self.rib_wavelength)
                modulation = self.rib_amplitude * (1 + np.cos(phase))
                # 肋骨貢獻：管狀 SDF 加上調製
                rib_tube = dist_to_pt - (self.arch_strength * 0.6 + modulation)
                rib_sdf = np.minimum(rib_sdf, rib_tube)

        # 只在接近主 SDF 等值面的地方添加肋骨
        near_surface_mask = np.abs(sdf) < self.arch_strength * 3
        sdf_with_ribs = np.where(near_surface_mask,
                                  np.minimum(sdf, rib_sdf),
                                  sdf)
        return sdf_with_ribs.astype(np.float32)
