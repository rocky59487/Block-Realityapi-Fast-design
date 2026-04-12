"""
exp_002 — FNO 應力代理模型精度驗證

目標：比較 FNO 代理模型與解析懸臂樑理論解的應力誤差。
      驗證 Castigliano 近似敏感度的精度。

理論基準：
  均布自重懸臂樑（固定端 x=0，自由端 x=L）
  應力分布：σ_xx(x,y) = -E·y·d²w/dx²（Euler-Bernoulli 樑理論）
  最大應力位於固定端頂底纖維：σ_max = ±M·c/I = ±(w·L²/2)·(h/2)/(bh³/12)

執行方式：
    python -m reborn.experiments.exp_002_fno_stress_accuracy

預期結果：
  - FNO Mock 模式：解析近似誤差（自重應力）< 20%（粗略基準）
  - ONNX 模式（若可用）：σ_VM 誤差 < 5% vs 樑理論解
"""
from __future__ import annotations
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

import numpy as np
from reborn.models.fno_proxy import FNOProxy
from reborn.utils.stress_tensor import von_mises, max_principal
from reborn.utils.blueprint_io import make_cantilever
from reborn.utils.visualization import plot_stress_heatmap, plot_convergence

OUTPUT_DIR = Path(__file__).parent / "outputs" / "exp_002"
OUTPUT_DIR.mkdir(parents=True, exist_ok=True)


def euler_bernoulli_cantilever_stress(
    Lx: int, Ly: int,
    E: float,
    rho: float,
    g: float = 9.81,
) -> np.ndarray:
    """
    計算均布自重懸臂樑的解析 Euler-Bernoulli 應力場。

    座標系：x = 沿樑長，y = 截面高度（0 = 底部，Ly = 頂部）

    公式：
      w = ρ·g (N/m per unit width)
      M(x) = w*(L-x)²/2  （從固定端量起）
      σ_xx = -M(x)·(y - h/2) / I
      I = b·h³/12 = 1·Ly³/12
    """
    L = float(Lx)
    h = float(Ly)
    I = h ** 3 / 12.0
    w = rho * g   # 每單位面積均布載重（簡化）

    stress = np.zeros((Lx, Ly, 1, 6), dtype=np.float32)

    for ix in range(Lx):
        x  = float(ix)
        M  = w * (L - x) ** 2 / 2.0   # 彎矩
        for iy in range(Ly):
            y  = float(iy) - h / 2.0   # 中性軸距離
            sigma_xx = -M * y / (I + 1e-8)
            stress[ix, iy, 0, 0] = sigma_xx   # Voigt σ_xx

    return stress


def run():
    print("=" * 60)
    print("exp_002 — FNO 應力精度驗證")
    print("=" * 60)

    results = []

    for mode in ["mock", "onnx"]:
        print(f"\n--- 模式：{mode} ---")
        fno = FNOProxy(mode=mode, verbose=True)

        if fno.mode == "onnx" and mode == "onnx":
            print("  ONNX 模型可用")
        elif mode == "onnx":
            print("  ONNX 不可用，跳過")
            continue

        grids = make_cantilever(Lx=16, Ly=8, Lz=1)
        occ   = grids["occupancy"]
        E     = grids["E_field"]
        nu    = grids["nu_field"]
        rho   = grids["density_field"]
        rc    = grids["rcomp_field"]

        # FNO 推論
        t0 = time.time()
        stress, disp, phi = fno.predict(occ, E, nu, rho, rc)
        elapsed_ms = (time.time() - t0) * 1000

        # 解析解
        theory_stress = euler_bernoulli_cantilever_stress(
            Lx=16, Ly=8, E=30e9, rho=2400.0,
        )

        # 計算誤差（在有材料的體素上）
        solid_mask = occ[:, :, 0]
        fno_vm  = von_mises(stress[:, :, 0, :])      # [16, 8]
        theo_vm = von_mises(theory_stress[:, :, 0, :])   # [16, 8]

        # 避免零除
        theo_max = theo_vm[solid_mask].max()
        if theo_max > 0:
            rel_error = np.abs(fno_vm[solid_mask] - theo_vm[solid_mask]) / (theo_max + 1e-8)
            mean_err  = float(rel_error.mean()) * 100
            max_err   = float(rel_error.max()) * 100
        else:
            mean_err, max_err = float("nan"), float("nan")

        metrics = {
            "mode":          mode,
            "Lx":            16, "Ly": 8,
            "mean_err_pct":  round(mean_err, 2),
            "max_err_pct":   round(max_err, 2),
            "elapsed_ms":    round(elapsed_ms, 2),
            "PASS":          mean_err < 20.0 if mode == "mock" else mean_err < 5.0,
        }
        results.append(metrics)

        print(f"  平均誤差：{mean_err:.1f}%，最大誤差：{max_err:.1f}%，耗時：{elapsed_ms:.1f}ms")

        # 視覺化
        plot_stress_heatmap(
            fno_vm[:, :, np.newaxis] if fno_vm.ndim == 2 else fno_vm,
            density=solid_mask[:, :, np.newaxis].astype(float) if solid_mask.ndim == 2 else None,
            title=f"FNO 馮米塞斯應力（{mode}）",
            output_path=OUTPUT_DIR / f"stress_fno_{mode}.png",
        )
        plot_stress_heatmap(
            theo_vm[:, :, np.newaxis] if theo_vm.ndim == 2 else theo_vm,
            title="理論 Euler-Bernoulli 應力",
            output_path=OUTPUT_DIR / "stress_theory.png",
        )

    # 摘要
    print("\n" + "=" * 60)
    print("驗證摘要：")
    for m in results:
        status = "✓" if m.get("PASS") else "✗"
        print(f"  {status} {m['mode']}: 平均誤差={m['mean_err_pct']}%, "
              f"最大誤差={m['max_err_pct']}%")

    import json
    with open(OUTPUT_DIR / "results.json", "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    print(f"\n結果已儲存至：{OUTPUT_DIR}")

    return all(m.get("PASS", False) for m in results)


if __name__ == "__main__":
    success = run()
    sys.exit(0 if success else 1)
