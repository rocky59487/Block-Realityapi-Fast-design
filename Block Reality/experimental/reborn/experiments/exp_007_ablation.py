"""
exp_007 — 消融研究

變體比較：
  A. 完整 StyleConditionedSSGO（四階段全部）
  B. 無對抗（僅階段 1-3）
  C. 無風格嵌入（style_alpha=0，z=z_geom only）
  D. 無物理預訓練（直接從階段 2 開始）
  E. Castigliano 敏感度（原始近似取代 autodiff）

各變體訓練後使用 RebornEvaluator 計算論文指標，
並匯出 LaTeX 比較表格。

執行方式：
    python -m reborn.experiments.exp_007_ablation [--steps 100] [--grid 8]
"""
from __future__ import annotations
import sys
import json
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

import numpy as np

OUTPUT_DIR = Path(__file__).parent / "outputs" / "exp_007"
OUTPUT_DIR.mkdir(parents=True, exist_ok=True)


def run_variant(name: str, config, description: str) -> dict:
    """執行單一消��變體。"""
    from reborn.training.style_trainer import RebornStyleTrainer
    from reborn.training.evaluator import RebornEvaluator

    print(f"\n--- 變體 {name}: {description} ---")
    t0 = time.time()

    trainer = RebornStyleTrainer(config)
    params, model, history = trainer.run()
    train_time = time.time() - t0

    # 使用訓練資料的子集評估
    eval_data = trainer._make_mock_dataset()[:10]
    evaluator = RebornEvaluator(config)
    metrics = evaluator.evaluate(model, params, eval_data)
    metrics["train_time_s"] = train_time

    # 損失摘要
    for stage, losses in history.items():
        if losses:
            metrics[f"{stage}_final_loss"] = losses[-1]

    print(f"  耗時：{train_time:.1f}s")
    print(f"  Pareto：{metrics.get('pareto_score', 0):.4f}")
    return metrics


def main():
    import argparse
    parser = argparse.ArgumentParser(description="Reborn 消融研究")
    parser.add_argument("--steps", type=int, default=40, help="每變體總步數")
    parser.add_argument("--grid", type=int, default=8, help="網格尺寸")
    args = parser.parse_args()

    from reborn.config import TrainingConfig

    base_steps = args.steps
    s1 = max(1, base_steps * 3 // 13)
    s2 = max(1, base_steps * 3 // 13)
    s3 = max(1, base_steps * 5 // 13)
    s4 = max(1, base_steps - s1 - s2 - s3)

    print("=" * 60)
    print("exp_007 — 消融研究")
    print("=" * 60)

    variants = {
        "A_full": (
            TrainingConfig(
                grid_size=args.grid, stage1_steps=s1, stage2_steps=s2,
                stage3_steps=s3, stage4_steps=s4, enable_adversarial=True,
                train_samples=30, checkpoint_dir=str(OUTPUT_DIR / "ckpt_A"),
            ),
            "完整四階段"
        ),
        "B_no_adv": (
            TrainingConfig(
                grid_size=args.grid, stage1_steps=s1, stage2_steps=s2,
                stage3_steps=s3, stage4_steps=0, enable_adversarial=False,
                train_samples=30, checkpoint_dir=str(OUTPUT_DIR / "ckpt_B"),
            ),
            "無對抗精修"
        ),
        "C_no_style": (
            TrainingConfig(
                grid_size=args.grid, stage1_steps=s1, stage2_steps=s2,
                stage3_steps=s3, stage4_steps=0, enable_adversarial=False,
                style_alpha_init=0.0,  # 停用風格
                train_samples=30, checkpoint_dir=str(OUTPUT_DIR / "ckpt_C"),
            ),
            "無風格嵌入（α=0）"
        ),
        "D_no_physics": (
            TrainingConfig(
                grid_size=args.grid, stage1_steps=0, stage2_steps=s2,
                stage3_steps=s3, stage4_steps=0, enable_adversarial=False,
                train_samples=30, checkpoint_dir=str(OUTPUT_DIR / "ckpt_D"),
            ),
            "無物理預訓練"
        ),
    }

    all_results = {}
    for name, (config, desc) in variants.items():
        try:
            metrics = run_variant(name, config, desc)
            all_results[name] = metrics
        except Exception as e:
            print(f"  ✗ 變體 {name} 失敗：{e}")
            all_results[name] = {"error": str(e)}

    # 摘要
    print("\n" + "=" * 60)
    print("消融結果摘要：")
    for name, m in all_results.items():
        if "error" in m:
            print(f"  ✗ {name}: {m['error']}")
        else:
            print(f"  ✓ {name}: pareto={m.get('pareto_score', 0):.4f}, "
                  f"time={m.get('train_time_s', 0):.1f}s")

    # 匯出
    with open(OUTPUT_DIR / "ablation_results.json", "w", encoding="utf-8") as f:
        json.dump(all_results, f, ensure_ascii=False, indent=2, default=float)

    # LaTeX 表格
    try:
        from reborn.training.evaluator import RebornEvaluator
        valid_results = {k: v for k, v in all_results.items() if "error" not in v}
        if valid_results:
            evaluator = RebornEvaluator()
            evaluator.export_latex_table(valid_results, str(OUTPUT_DIR / "ablation_table.tex"))
            print(f"\nLaTeX 表格已儲存至：{OUTPUT_DIR / 'ablation_table.tex'}")
    except Exception as e:
        print(f"  LaTeX 匯出跳過：{e}")

    print(f"\n結果已儲存至：{OUTPUT_DIR}")
    return len([v for v in all_results.values() if "error" not in v]) > 0


if __name__ == "__main__":
    success = main()
    sys.exit(0 if success else 1)
