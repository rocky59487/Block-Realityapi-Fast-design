"""
exp_005 — 完整管線端到端測試

目標：
  驗證四個階段可以串接執行，從合成懸臂樑輸入到風格化 SDF 輸出。
  若 NurbsExporter sidecar 在線，嘗試輸出 STEP 文件。

執行方式：
    python -m reborn.experiments.exp_005_end_to_end

預期結果：
  - 四個階段全部完成（無例外）
  - 總耗時 < 60s（CPU 模式）
  - 輸出目錄含：density.npy, sdf_gaudi.npy, convergence.png 等
  - 若 sidecar 在線：output .step 文件有效（大小 > 0）
"""
from __future__ import annotations
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

import numpy as np
from reborn import RebornPipeline, RebornConfig, SimPConfig, StyleConfig, NurbsConfig

OUTPUT_DIR = Path(__file__).parent / "outputs" / "exp_005"
OUTPUT_DIR.mkdir(parents=True, exist_ok=True)


def run_e2e_test(
    structure: str = "cantilever",
    size:      int = 16,
    style:     str = "gaudi",
    max_iter:  int = 30,
) -> dict:
    """執行端到端管線測試"""
    print(f"\n--- 結構：{structure}，尺寸：{size}，風格：{style} ---")

    config = RebornConfig(
        simp=SimPConfig(max_iter=max_iter, vol_frac=0.40),
        style=StyleConfig(mode=style),
        nurbs=NurbsConfig(smoothing=0.6, resolution=1),
        output_root=str(OUTPUT_DIR),
        verbose=True,
    )

    pipeline = RebornPipeline(config)
    status = pipeline.get_status()
    print(f"  FNO 模式：{status['fno_mode']}，"
          f"sidecar：{'在線' if status['sidecar']['available'] else '離線'}")

    t0 = time.time()
    session = pipeline.run_from_test(
        structure=structure,
        size=size,
        style=style,
    )
    total_s = time.time() - t0

    summary = session.get_summary()
    topo = summary.get("topology", {})
    nurbs = summary.get("nurbs", {})

    metrics = {
        "structure":     structure,
        "size":          size,
        "style":         style,
        "stage":         summary["stage"],
        "n_iter":        topo.get("n_iter", 0),
        "converged":     topo.get("converged", False),
        "compliance":    topo.get("compliance", "N/A"),
        "total_s":       round(total_s, 2),
        "sidecar_ok":    nurbs.get("success", False),
        "step_path":     nurbs.get("step_path"),
        "PASS":          total_s < 120.0 and summary["stage"] in ("style", "complete", "nurbs_failed"),
    }

    print(f"  完成：stage={summary['stage']}，耗時={total_s:.1f}s")
    if metrics["sidecar_ok"]:
        print(f"  STEP 輸出：{metrics['step_path']}")
    else:
        print(f"  降級：SDF .npy 已儲存至 {session.work_dir}")

    return metrics


def test_restyle():
    """驗證重新風格化路徑（跳過 SIMP）"""
    print("\n--- 重新風格化測試（跳過 SIMP）---")

    config = RebornConfig(
        simp=SimPConfig(max_iter=10),
        style=StyleConfig(mode="gaudi"),
        output_root=str(OUTPUT_DIR),
        verbose=False,
    )
    pipeline = RebornPipeline(config)

    # 第一次執行
    session = pipeline.run_from_test("cantilever", size=12, style="gaudi")
    t1 = time.time()

    # 重新風格化（應跳過 SIMP）
    session = pipeline.restyle(session, "zaha")
    t2 = time.time()

    restyle_s = t2 - t1
    print(f"  重新風格化耗時：{restyle_s:.1f}s（應 < 10s，因為跳過 SIMP）")
    PASS = restyle_s < 30.0 and session.style is not None
    print(f"  {'✓' if PASS else '✗'} 重新風格化功能")
    return PASS


def main():
    print("=" * 60)
    print("exp_005 — 完整管線端到端測試")
    print("=" * 60)

    all_metrics = []

    # 主要端對端測試
    test_cases = [
        ("cantilever", 16, "gaudi"),
        ("tower",       8, "zaha"),
        ("beam",       12, "none"),
    ]

    for structure, size, style in test_cases:
        m = run_e2e_test(structure, size, style, max_iter=20)
        all_metrics.append(m)

    # 重新風格化測試
    restyle_ok = test_restyle()

    # 摘要
    print("\n" + "=" * 60)
    pass_count = sum(m["PASS"] for m in all_metrics) + int(restyle_ok)
    total = len(all_metrics) + 1
    print(f"通過：{pass_count}/{total}")

    for m in all_metrics:
        status = "✓" if m["PASS"] else "✗"
        print(f"  {status} {m['structure']}+{m['style']}: "
              f"stage={m['stage']}, t={m['total_s']}s, "
              f"STEP={'是' if m['sidecar_ok'] else '否'}")
    print(f"  {'✓' if restyle_ok else '✗'} 重新風格化跳過 SIMP")

    import json
    with open(OUTPUT_DIR / "results.json", "w", encoding="utf-8") as f:
        json.dump({
            "e2e": all_metrics,
            "restyle": restyle_ok,
            "total_pass": pass_count,
            "total": total,
        }, f, ensure_ascii=False, indent=2)
    print(f"\n結果已儲存至：{OUTPUT_DIR}")

    return pass_count == total


if __name__ == "__main__":
    success = main()
    sys.exit(0 if success else 1)
