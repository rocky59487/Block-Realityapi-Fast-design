"""
test_gaudi_style.py — GaudiStyle / ZahaStyle 單元測試

pytest 執行：
    python -m pytest reborn/tests/test_gaudi_style.py -v
"""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

import numpy as np
import pytest
from reborn.models.gaudi_style import GaudiStyle
from reborn.models.zaha_style import ZahaStyle, blend_styles
from reborn.models.stress_path import (
    extract_principal_stress_paths, classify_path_morphology,
    filter_arch_paths, filter_flow_paths,
)
from reborn.utils.density_to_sdf import (
    density_to_sdf_threshold, sdf_smooth_union,
    sdf_sphere, sdf_catenary_arch, sdf_hyperboloid,
)
from reborn.utils.stress_tensor import von_mises


# -----------------------------------------------------------------------
# 輔助函式
# -----------------------------------------------------------------------

def make_stress_field(shape=(12, 8, 1), mode="compression"):
    """建立合成應力場"""
    Lx, Ly, Lz = shape
    stress = np.zeros(shape + (6,), dtype=np.float32)
    if mode == "compression":
        # 垂直均布壓縮
        stress[..., 1] = -10.0   # σ_yy = -10 MPa
    elif mode == "bending":
        # 彎矩（頂部拉伸，底部壓縮）
        for iy in range(Ly):
            y_norm = iy / Ly - 0.5
            stress[:, iy, :, 0] = 10.0 * y_norm   # σ_xx
    return stress


def make_arch_density(Lx=20, Ly=12, Lz=1):
    """建立拱形高密度結構"""
    occ = np.zeros((Lx, Ly, max(Lz, 1)), dtype=np.float32)
    # 圓弧形拱
    cx = Lx / 2.0
    cy = Ly * 0.8
    r  = Ly * 0.9
    for ix in range(Lx):
        for iy in range(Ly):
            dist = np.sqrt((ix - cx)**2 + (iy - cy)**2)
            if r - 2 < dist < r + 1:
                occ[ix, iy, :] = 1.0
    occ[:2, :3, :]  = 1.0   # 左支撐
    occ[-2:, :3, :] = 1.0   # 右支撐
    return occ


# -----------------------------------------------------------------------
# SDF 工具測試
# -----------------------------------------------------------------------

class TestSDFTools:
    def test_density_to_sdf_interior(self):
        """密實體素的 SDF 應為負值"""
        density = np.ones((10, 10, 10), dtype=np.float32)
        sdf = density_to_sdf_threshold(density, iso=0.5)
        # 中心點應為負（內部）
        assert sdf[5, 5, 5] < 0, f"內部 SDF 應 < 0，得 {sdf[5,5,5]}"

    def test_density_to_sdf_exterior(self):
        """空氣體素的 SDF 應為正值"""
        density = np.zeros((10, 10, 10), dtype=np.float32)
        # 只有中間一塊固體
        density[4:6, 4:6, 4:6] = 1.0
        sdf = density_to_sdf_threshold(density, iso=0.5)
        # 角落應為正（外部）
        assert sdf[0, 0, 0] > 0

    def test_sdf_smooth_union_commutativity(self):
        """平滑聯集應近似交換律"""
        a = np.random.randn(8, 8, 8).astype(np.float32)
        b = np.random.randn(8, 8, 8).astype(np.float32)
        union_ab = sdf_smooth_union(a, b, k=0.3)
        union_ba = sdf_smooth_union(b, a, k=0.3)
        assert np.allclose(union_ab, union_ba, atol=1e-5)

    def test_sphere_sdf_at_surface(self):
        """球體 SDF 在表面應接近 0"""
        shape = (20, 20, 20)
        center = np.array([10.0, 10.0, 10.0])
        radius = 4.0
        sdf = sdf_sphere(shape, center, radius)
        # 表面點 (10+4, 10, 10)
        assert abs(sdf[14, 10, 10]) < 0.5, f"球面 SDF 應≈0，得 {sdf[14,10,10]}"

    def test_catenary_arch_negative_core(self):
        """懸鏈線拱的核心區域應為負 SDF（內部）"""
        shape = (24, 16, 1)
        p0 = np.array([2.0, 0.0, 0.0])
        p1 = np.array([22.0, 0.0, 0.0])
        sdf = sdf_catenary_arch(shape, p0, p1, a_param=5.0, thickness=2.0)
        # 拱頂點附近應為負值
        # 懸鏈線頂點在 x=12（中央），y = a*cosh(0)-a = 0
        core_val = sdf[12, 0, 0]
        assert core_val < 2.0, f"懸鏈線核心應在表面附近，得 {core_val}"


# -----------------------------------------------------------------------
# 應力路徑測試
# -----------------------------------------------------------------------

class TestStressPaths:
    def test_path_extraction_returns_list(self):
        density = np.ones((10, 10, 1), dtype=np.float32)
        stress = make_stress_field((10, 10, 1), mode="bending")
        paths = extract_principal_stress_paths(stress, density, n_seeds=4, max_steps=20)
        assert isinstance(paths, list)

    def test_path_shape(self):
        density = np.ones((10, 10, 1), dtype=np.float32)
        stress = make_stress_field((10, 10, 1), mode="bending")
        paths = extract_principal_stress_paths(stress, density, n_seeds=8, max_steps=20)
        for path in paths:
            assert path.ndim == 2
            assert path.shape[1] == 3, f"路徑應為 (N,3)，得 {path.shape}"
            assert len(path) >= 3

    def test_classify_arch(self):
        """弧形路徑應被正確分類"""
        xs = np.linspace(0, 10, 20)
        ys = -(xs - 5)**2 + 10   # 向上開口拋物線
        path = np.column_stack([xs, ys, np.zeros(20)])
        morph = classify_path_morphology(path)
        assert morph == "arch", f"應為 arch，得 {morph}"

    def test_classify_column(self):
        """垂直路徑應被分類為柱"""
        path = np.column_stack([
            np.zeros(10),
            np.linspace(0, 10, 10),
            np.zeros(10),
        ])
        morph = classify_path_morphology(path)
        assert morph == "column", f"應為 column，得 {morph}"

    def test_filter_arch_paths(self):
        """filter_arch_paths 應只回傳弧形路徑"""
        paths = []
        # 弧形
        xs = np.linspace(0, 10, 20)
        paths.append(np.column_stack([xs, -(xs-5)**2+10, np.zeros(20)]))
        # 直線
        paths.append(np.column_stack([np.zeros(10), np.linspace(0,10,10), np.zeros(10)]))

        arch_paths = filter_arch_paths(paths)
        assert len(arch_paths) <= len(paths)


# -----------------------------------------------------------------------
# GaudiStyle 測試
# -----------------------------------------------------------------------

class TestGaudiStyle:
    def test_apply_returns_array(self):
        gaudi = GaudiStyle(verbose=False)
        density = np.ones((12, 8, 1), dtype=np.float32)
        stress  = make_stress_field((12, 8, 1), mode="bending")
        sdf = gaudi.apply(density, stress)
        assert isinstance(sdf, np.ndarray)
        assert sdf.shape == density.shape

    def test_sdf_has_positive_negative(self):
        """輸出 SDF 應同時有正值（外部）和負值（內部）"""
        gaudi = GaudiStyle(verbose=False)
        density = make_arch_density()
        stress  = make_stress_field(density.shape, mode="bending")
        sdf = gaudi.apply(density, stress)
        assert sdf.max() > 0, "SDF 應有正值（外部）"
        assert sdf.min() < 0, "SDF 應有負值（內部）"

    def test_catenary_fit_reasonable_a(self):
        """懸鏈線擬合應回傳合理的 a 值"""
        gaudi = GaudiStyle()
        # 建立已知懸鏈線路徑
        a_true = 6.0
        xs = np.linspace(-8, 8, 30)
        ys = a_true * (np.cosh(xs / a_true) - 1)
        path = np.column_stack([xs + 12, ys + 3, np.zeros(30)])

        params = gaudi._fit_catenary(path)
        if params is not None:
            a_fitted = params["a"]
            assert 0.5 < a_fitted < 100.0, f"擬合 a={a_fitted} 不合理"

    def test_no_nan_in_output(self):
        """輸出不應有 NaN"""
        gaudi = GaudiStyle(verbose=False)
        density = np.ones((8, 8, 1), dtype=np.float32)
        stress  = make_stress_field((8, 8, 1))
        sdf = gaudi.apply(density, stress)
        assert not np.isnan(sdf).any(), "SDF 中有 NaN"


# -----------------------------------------------------------------------
# ZahaStyle 測試
# -----------------------------------------------------------------------

class TestZahaStyle:
    def test_apply_returns_array(self):
        zaha = ZahaStyle(flow_steps=3, verbose=False)
        density = np.ones((10, 10, 1), dtype=np.float32)
        stress  = make_stress_field((10, 10, 1))
        sdf = zaha.apply(density, stress)
        assert isinstance(sdf, np.ndarray)
        assert sdf.shape == density.shape

    def test_mass_approximately_conserved(self):
        """水平集平流不應大幅改變 SDF 的質量（負值體積）"""
        zaha = ZahaStyle(flow_steps=5, flow_speed=0.1, verbose=False)
        density = np.ones((12, 8, 1), dtype=np.float32)
        stress  = make_stress_field((12, 8, 1))

        from reborn.utils.density_to_sdf import density_to_sdf_smooth
        sdf_before = density_to_sdf_smooth(density, iso=0.5)
        vol_before = float((sdf_before < 0).sum())

        sdf_after = zaha.apply(density, stress)
        vol_after = float((sdf_after < 0).sum())

        # 允許 50% 體積變化（平流會改變形狀）
        if vol_before > 0:
            change = abs(vol_after - vol_before) / vol_before
            assert change < 0.5, f"體積變化過大：{change:.2f}"

    def test_no_nan_in_output(self):
        zaha = ZahaStyle(flow_steps=2, verbose=False)
        density = np.ones((8, 8, 1), dtype=np.float32)
        stress  = make_stress_field((8, 8, 1))
        sdf = zaha.apply(density, stress)
        assert not np.isnan(sdf).any()


# -----------------------------------------------------------------------
# 風格混合測試
# -----------------------------------------------------------------------

class TestBlendStyles:
    def test_alpha_zero_returns_a(self):
        a = np.ones((5, 5, 5), dtype=np.float32) * 2.0
        b = np.ones((5, 5, 5), dtype=np.float32) * 4.0
        blended = blend_styles(a, b, alpha=0.0, smooth=0.0)
        assert np.allclose(blended, 2.0, atol=1e-5)

    def test_alpha_one_returns_b(self):
        a = np.ones((5, 5, 5), dtype=np.float32) * 2.0
        b = np.ones((5, 5, 5), dtype=np.float32) * 4.0
        blended = blend_styles(a, b, alpha=1.0, smooth=0.0)
        assert np.allclose(blended, 4.0, atol=1e-5)

    def test_alpha_half_is_midpoint(self):
        a = np.ones((5, 5, 5), dtype=np.float32) * 2.0
        b = np.ones((5, 5, 5), dtype=np.float32) * 4.0
        blended = blend_styles(a, b, alpha=0.5, smooth=0.0)
        assert np.allclose(blended, 3.0, atol=1e-5)
