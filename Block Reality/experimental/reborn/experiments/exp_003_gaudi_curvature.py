"""
exp_003 — 高第風格 SDF 變形驗證

目標：
  1. 驗證懸鏈線擬合（catenary fitting）的幾何精度
  2. 驗證雙曲面柱 SDF 的數值正確性
  3. 視覺檢查 GaudiStyle 應用後的密度場形態

純 numpy/scipy，無需 FNO 或 ONNX 模型。

執行方式：
    python -m reborn.experiments.exp_003_gaudi_curvature

預期結果：
  - 懸鏈線擬合殘差 < 5% 樑高
  - 雙曲面 SDF 等值面為封閉曲面（負值體積 > 0）
  - 高第風格密度場顯示弧形高密度帶
"""
from __future__ import annotations
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

import numpy as np
from reborn.models.gaudi_style import GaudiStyle
from reborn.models.stress_path import extract_principal_stress_paths, filter_arch_paths
from reborn.utils.density_to_sdf import sdf_catenary_arch, sdf_hyperboloid
from reborn.utils.visualization import (
    plot_density_slices, plot_sdf_contours, plot_stress_paths
)

OUTPUT_DIR = Path(__file__).parent / "outputs" / "exp_003"
OUTPUT_DIR.mkdir(parents=True, exist_ok=True)


def make_arch_stress_field(Lx: int = 24, Ly: int = 16, Lz: int = 1) -> tuple:
    """
    建立模擬拱形結構的合成應力場。

    模擬：兩端固定拱，中央點載下的主壓縮應力為拋物線形。
    """
    shape = (Lx, Ly, max(Lz, 1))

    # 密度場：均勻填充（全部固體）
    density = np.ones(shape, dtype=np.float32)
    density[:, :2, :] = 0.0    # 底部空氣間隙（讓錨點在體素 y=2）

    # 合成應力場（模擬拱的主壓縮方向）
    stress = np.zeros(shape + (6,), dtype=np.float32)
    Lx_f, Ly_f = float(Lx), float(Ly)

    for ix in range(Lx):
        for iy in range(Ly):
            x = ix / Lx_f - 0.5          # [-0.5, 0.5]
            y = iy / Ly_f                  # [0, 1]
            # 主壓縮：沿拱面切向
            parabola_slope = -4 * x       # 拋物線 y = -2x² 的切線斜率
            angle = np.arctan(parabola_slope)
            # σ_xx（沿水平方向壓縮）+ σ_yy（垂直分量）
            compression = -1.0 * (1.0 - y)  # 底部壓縮最大
            stress[ix, iy, :, 0] = compression * np.cos(angle) ** 2    # σ_xx
            stress[ix, iy, :, 1] = compression * np.sin(angle) ** 2    # σ_yy
            stress[ix, iy, :, 5] = compression * np.sin(angle) * np.cos(angle)  # σ_xy

    return density, stress


def test_catenary_fitting():
    """測試懸鏈線擬合精度"""
    print("\n--- 測試懸鏈線擬合 ---")
    from reborn.models.gaudi_style import GaudiStyle
    from scipy.optimize import minimize_scalar

    gaudi = GaudiStyle(arch_strength=1.5, verbose=True)

    # 建立已知懸鏈線路徑
    a_true = 8.0
    xs = np.linspace(-10, 10, 40)
    ys = a_true * (np.cosh(xs / a_true) - 1)
    path = np.column_stack([xs + 12, ys + 4, np.zeros(len(xs))])  # 偏移至網格中央

    params = gaudi._fit_catenary(path)
    if params is None:
        print("  ✗ 擬合失敗（路徑太短或不符合拱形）")
        return False

    a_fitted = params["a"]
    a_err_pct = abs(a_fitted - a_true) / a_true * 100
    print(f"  真實 a = {a_true:.1f}，擬合 a = {a_fitted:.2f}，誤差 = {a_err_pct:.1f}%")

    PASS = a_err_pct < 15.0
    print(f"  {'✓' if PASS else '✗'} 懸鏈線擬合精度：{a_err_pct:.1f}% < 15%")
    return PASS


def test_hyperboloid_sdf():
    """測試雙曲面柱 SDF 數值正確性"""
    print("\n--- 測試雙曲面 SDF ---")
    shape = (20, 20, 20)
    center = np.array([10.0, 10.0, 10.0])
    a, c = 3.0, 5.0

    hyp_sdf = sdf_hyperboloid(shape, center, a, c)

    # 驗證：中心腰部（y = 0）處，SDF = x² + z² - a²
    # 在 (a+center.x, center.y, center.z) 點，SDF ≈ 0
    ix = int(center[0] + a)
    iy = int(center[1])
    iz = int(center[2])
    sdf_at_waist = float(hyp_sdf[min(ix, 19), iy, iz])
    print(f"  腰部點 SDF = {sdf_at_waist:.3f}（期望 ≈ 0）")

    # 負值體積（內部體積）
    n_interior = int((hyp_sdf < 0).sum())
    total = int(np.prod(shape))
    interior_pct = n_interior / total * 100
    print(f"  內部體素：{n_interior}/{total}（{interior_pct:.1f}%）")

    PASS = abs(sdf_at_waist) < 1.0 and n_interior > 0
    print(f"  {'✓' if PASS else '✗'} 雙曲面 SDF 正確性")

    # 儲存切面圖
    plot_sdf_contours(hyp_sdf, title="雙曲面柱 SDF", output_path=OUTPUT_DIR / "hyperboloid_sdf.png")
    return PASS


def test_gaudi_full():
    """測試完整 GaudiStyle 應用"""
    print("\n--- 測試完整 GaudiStyle ---")
    density, stress = make_arch_stress_field(Lx=24, Ly=16, Lz=1)

    gaudi = GaudiStyle(arch_strength=1.5, smin_k=0.3, verbose=True)
    sdf = gaudi.apply(density, stress)

    # 驗證：SDF 應有有效的等值面
    n_neg = int((sdf < 0).sum())
    n_pos = int((sdf > 0).sum())
    print(f"  SDF 內部：{n_neg}，外部：{n_pos}")
    print(f"  SDF 範圍：[{sdf.min():.2f}, {sdf.max():.2f}]")

    PASS = n_neg > 0 and n_pos > 0
    print(f"  {'✓' if PASS else '✗'} SDF 有效（含正負兩側）")

    # 路徑視覺化
    paths = extract_principal_stress_paths(stress, density, n_seeds=16, stress_type="max")
    arch_paths = filter_arch_paths(paths)
    print(f"  找到 {len(paths)} 條路徑，{len(arch_paths)} 條弧形路徑")

    plot_density_slices(
        density, title="原始密度場",
        output_path=OUTPUT_DIR / "density_original.png",
    )
    plot_sdf_contours(
        sdf, title="GaudiStyle SDF",
        output_path=OUTPUT_DIR / "sdf_gaudi.png",
    )
    if len(paths) > 0:
        plot_stress_paths(
            density, paths,
            title="主應力路徑",
            output_path=OUTPUT_DIR / "stress_paths.png",
        )
    return PASS


def main():
    print("=" * 60)
    print("exp_003 — 高第風格 SDF 變形驗證")
    print("=" * 60)

    results = {
        "catenary_fitting": test_catenary_fitting(),
        "hyperboloid_sdf":  test_hyperboloid_sdf(),
        "gaudi_full":       test_gaudi_full(),
    }

    print("\n" + "=" * 60)
    print("測試摘要：")
    pass_count = 0
    for name, passed in results.items():
        status = "✓" if passed else "✗"
        print(f"  {status} {name}")
        if passed:
            pass_count += 1

    print(f"\n通過：{pass_count}/{len(results)}")

    import json
    with open(OUTPUT_DIR / "results.json", "w", encoding="utf-8") as f:
        json.dump({k: bool(v) for k, v in results.items()}, f, indent=2)

    return pass_count == len(results)


if __name__ == "__main__":
    success = main()
    sys.exit(0 if success else 1)
