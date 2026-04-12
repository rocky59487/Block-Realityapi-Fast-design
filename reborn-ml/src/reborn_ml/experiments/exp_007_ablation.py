"""
exp_007 — 消融研究

變體：A 完整四階段 / B 無對抗 / C 無風格嵌入（α=0） / D 無物理預訓練

執行：
    python -m reborn_ml.experiments.exp_007_ablation [--steps 100] [--grid 8]
    reborn-ablation
"""
from __future__ import annotations
import argparse
import json
import time
from pathlib import Path

OUTPUT_DIR = Path(__file__).parent.parent.parent.parent / "outputs" / "exp_007"
OUTPUT_DIR.mkdir(parents=True, exist_ok=True)


def run_variant(name: str, config, description: str) -> dict:
    from reborn_ml.training.style_trainer import RebornStyleTrainer
    from reborn_ml.training.evaluator import RebornEvaluator

    print(f"\n--- 變體 {name}: {description} ---")
    t0 = time.time()
    trainer = RebornStyleTrainer(config)
    params, model, history = trainer.run()
    train_time = time.time() - t0

    eval_data = trainer._make_mock_dataset()[:10]
    evaluator = RebornEvaluator(config)
    metrics = evaluator.evaluate(model, params, eval_data)
    metrics["train_time_s"] = train_time
    for stage, losses in history.items():
        if losses:
            metrics[f"{stage}_final_loss"] = losses[-1]

    print(f"  耗時：{train_time:.1f}s  Pareto：{metrics.get('pareto_score', 0):.4f}")
    return metrics


def main():
    parser = argparse.ArgumentParser(description="Reborn 消融研究")
    parser.add_argument("--steps", type=int, default=40)
    parser.add_argument("--grid",  type=int, default=8)
    args = parser.parse_args()

    from reborn_ml.config import TrainingConfig
    total = args.steps
    s1 = max(1, total * 3 // 13)
    s2 = max(1, total * 3 // 13)
    s3 = max(1, total * 5 // 13)
    s4 = max(1, total - s1 - s2 - s3)

    print("=" * 60)
    print("exp_007 — 消融研究")
    print("=" * 60)

    ckpt = lambda n: str(OUTPUT_DIR / f"ckpt_{n}")
    variants = {
        "A_full":       (TrainingConfig(grid_size=args.grid, stage1_steps=s1, stage2_steps=s2, stage3_steps=s3, stage4_steps=s4, enable_adversarial=True,  train_samples=30, checkpoint_dir=ckpt("A")), "完整四階段"),
        "B_no_adv":     (TrainingConfig(grid_size=args.grid, stage1_steps=s1, stage2_steps=s2, stage3_steps=s3, stage4_steps=0,  enable_adversarial=False, train_samples=30, checkpoint_dir=ckpt("B")), "無對抗精修"),
        "C_no_style":   (TrainingConfig(grid_size=args.grid, stage1_steps=s1, stage2_steps=s2, stage3_steps=s3, stage4_steps=0,  enable_adversarial=False, style_alpha_init=0.0, train_samples=30, checkpoint_dir=ckpt("C")), "無風格嵌入"),
        "D_no_physics": (TrainingConfig(grid_size=args.grid, stage1_steps=0,  stage2_steps=s2, stage3_steps=s3, stage4_steps=0,  enable_adversarial=False, train_samples=30, checkpoint_dir=ckpt("D")), "無物理預訓練"),
    }

    all_results = {}
    for name, (config, desc) in variants.items():
        try:
            all_results[name] = run_variant(name, config, desc)
        except Exception as e:
            print(f"  ✗ {name} 失敗：{e}")
            all_results[name] = {"error": str(e)}

    print("\n" + "=" * 60)
    for name, m in all_results.items():
        if "error" in m:
            print(f"  ✗ {name}: {m['error']}")
        else:
            print(f"  ✓ {name}: pareto={m.get('pareto_score', 0):.4f}, time={m.get('train_time_s', 0):.1f}s")

    with open(OUTPUT_DIR / "ablation_results.json", "w", encoding="utf-8") as f:
        json.dump(all_results, f, ensure_ascii=False, indent=2, default=float)

    try:
        from reborn_ml.training.evaluator import RebornEvaluator
        valid = {k: v for k, v in all_results.items() if "error" not in v}
        if valid:
            RebornEvaluator().export_latex_table(valid, str(OUTPUT_DIR / "ablation_table.tex"))
            print(f"\nLaTeX 表格：{OUTPUT_DIR / 'ablation_table.tex'}")
    except Exception as e:
        print(f"  LaTeX 匯出跳過：{e}")

    print(f"\n結果已儲存至：{OUTPUT_DIR}")
    return len([v for v in all_results.values() if "error" not in v]) > 0


if __name__ == "__main__":
    import sys
    sys.exit(0 if main() else 1)
