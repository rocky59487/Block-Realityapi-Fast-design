"""
exp_001 — SIMP 收斂性基準測試

目標：驗證 FNO-SIMP 在 MBB 懸臂樑問題上的收斂性，
      對比不同網格尺寸與體積分率的最終密度形態。

純 numpy/scipy，無需 GPU 或 ONNX 模型（使用 Mock FNO）。

執行方式：
    cd "Block Reality/experimental"
    python -m reborn.experiments.exp_001_simp_convergence

預期結果：
  - 合規性曲線單調下降（允許前 3 次有小幅震盪）
  - 最終體積分率誤差 < 1%
  - 密度場呈現清晰的主受力路徑（深色高密度帶）
"""
from __future__ import annotations
import sys
import time
from pathlib import Path

# 確保套件可 import
sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

import numpy as np
from reborn import RebornConfig, SimPConfig
from reborn.stages.voxel_massing import VoxelMassing
from reborn.stages.topo_optimizer import TopologyOptimizer
from reborn.models.fno_proxy import FNOProxy
from reborn.utils.visualization import (
    plot_density_slices, plot_convergence, save_density_npz
)

# -----------------------------------------------------------------------
# 實驗設定
# -----------------------------------------------------------------------
EXPERIMENTS = [
    # (名稱,   網格,        目標體積分率, 說明)
    ("cantilever_VF30", "cantilever", 16, 0.30, "懸臂樑 VF=30%"),
    ("cantilever_VF40", "cantilever", 16, 0.40, "懸臂樑 VF=40%"),
    ("beam_VF35",       "beam",       12, 0.35, "簡支樑 VF=35%"),
    ("tower_VF40",      "tower",       8, 0.40, "高塔 VF=40%"),
]

OUTPUT_DIR = Path(__file__).parent / "outputs" / "exp_001"
OUTPUT_DIR.mkdir(parents=True, exist_ok=True)


def run_single(
    name: str,
    structure: str,
    size: int,
    vol_frac: float,
    desc: str,
) -> dict:
    """執行單一 SIMP 實驗並回傳指標"""
    print(f"\n--- {name}: {desc} ---")

    config = RebornConfig(
        simp=SimPConfig(
            vol_frac=vol_frac,
            max_iter=50,
            tol=0.01,
        ),
        verbose=True,
    )

    # 使用 Mock FNO（無需 ONNX 模型）
    fno = FNOProxy(mode="mock")
    massing_stage = VoxelMassing(config)
    topo_stage    = TopologyOptimizer(config, fno)

    # 建立測試結構
    massing = massing_stage.from_test_structure(structure, size)
    print(f"  網格：{massing.shape}，固體體素：{massing.n_solid}")

    # 執行 SIMP
    t0 = time.time()
    result = topo_stage.optimize(massing)
    elapsed = time.time() - t0

    # 計算指標
    final_vf    = result.final_volfrac
    vf_error    = abs(final_vf - vol_frac)
    converged   = result.converged
    # 合規性是否單調下降（允許前 3 次震盪）
    compliance  = result.compliance_history
    monotone    = all(
        compliance[i] <= compliance[i - 1] * 1.05
        for i in range(min(3, len(compliance)), len(compliance))
    ) if len(compliance) > 3 else True

    metrics = {
        "name":          name,
        "structure":     structure,
        "size":          size,
        "vol_frac_target": vol_frac,
        "vol_frac_final":  round(final_vf, 4),
        "vf_error_pct":    round(vf_error * 100, 2),
        "compliance_final": round(compliance[-1] if compliance else 0, 6),
        "n_iter":          result.n_iterations,
        "converged":       converged,
        "compliance_monotone": monotone,
        "elapsed_s":       round(elapsed, 2),
        "PASS":            vf_error < 0.01 and monotone,
    }

    # 輸出視覺化
    if result.density is not None:
        exp_out = OUTPUT_DIR / name
        exp_out.mkdir(exist_ok=True)
        plot_density_slices(
            result.density, title=f"{name} 最終密度場",
            output_path=exp_out / "density.png",
            threshold=0.5,
        )
        plot_convergence(
            compliance, result.volfrac_history,
            title=f"{name} SIMP 收斂性",
            output_path=exp_out / "convergence.png",
        )
        save_density_npz(result.density, exp_out / "density.npz", metrics)

    status = "✓ PASS" if metrics["PASS"] else "✗ FAIL"
    print(f"  {status}: VF={final_vf:.3f} (目標={vol_frac})，"
          f"迭代={result.n_iterations}，耗時={elapsed:.1f}s")

    return metrics


def main():
    print("=" * 60)
    print("exp_001 — SIMP 收斂性基準測試")
    print("=" * 60)

    all_metrics = []
    pass_count  = 0

    for args in EXPERIMENTS:
        name, structure, size, vol_frac, desc = args
        m = run_single(name, structure, size, vol_frac, desc)
        all_metrics.append(m)
        if m["PASS"]:
            pass_count += 1

    # 摘要
    print("\n" + "=" * 60)
    print("實驗摘要：")
    print(f"  通過：{pass_count}/{len(EXPERIMENTS)}")
    for m in all_metrics:
        status = "✓" if m["PASS"] else "✗"
        print(f"  {status} {m['name']}: VF={m['vol_frac_final']} "
              f"(目標={m['vol_frac_target']}), "
              f"合規={m['compliance_final']:.3e}, "
              f"收斂={'是' if m['converged'] else '否'}")

    # 儲存摘要
    import json
    with open(OUTPUT_DIR / "results.json", "w", encoding="utf-8") as f:
        json.dump(all_metrics, f, ensure_ascii=False, indent=2)
    print(f"\n結果已儲存至：{OUTPUT_DIR}")

    return pass_count == len(EXPERIMENTS)


if __name__ == "__main__":
    success = main()
    sys.exit(0 if success else 1)
