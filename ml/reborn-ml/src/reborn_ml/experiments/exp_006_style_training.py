"""
exp_006 — StyleConditionedSSGO 四階段訓練

執行方式：
    # 快速煙霧測試（CPU，~2 分鐘）
    python -m reborn_ml.experiments.exp_006_style_training --steps 10 --grid 8

    # A100 完整訓練（~3 小時）
    python -m reborn_ml.experiments.exp_006_style_training --grid 16 --steps 13000

    # 或使用 CLI 入口點
    reborn-train --grid 16 --steps 13000
"""
from __future__ import annotations
import argparse
import json
import time
from pathlib import Path

import numpy as np

OUTPUT_DIR = Path(__file__).parent.parent.parent.parent / "outputs" / "exp_006"
OUTPUT_DIR.mkdir(parents=True, exist_ok=True)


def main():
    parser = argparse.ArgumentParser(description="Reborn StyleConditionedSSGO 訓練")
    parser.add_argument("--grid",    type=int, default=8,    help="網格尺寸")
    parser.add_argument("--steps",   type=int, default=40,   help="總步數（按 3:3:5:2 分配）")
    parser.add_argument("--batch",   type=int, default=1,    help="批次大小")
    parser.add_argument("--output",  type=str, default=None, help="輸出目錄")
    parser.add_argument("--no-adv",  action="store_true",    help="停用對抗階段")
    args = parser.parse_args()

    total = args.steps
    s1 = max(1, int(total * 3 / 13))
    s2 = max(1, int(total * 3 / 13))
    s3 = max(1, int(total * 5 / 13))
    s4 = max(1, total - s1 - s2 - s3) if not args.no_adv else 0

    out_dir = Path(args.output) if args.output else OUTPUT_DIR
    out_dir.mkdir(parents=True, exist_ok=True)

    print("=" * 60)
    print("exp_006 — StyleConditionedSSGO 訓練")
    print("=" * 60)
    print(f"  網格：{args.grid}³  步數：S1={s1}, S2={s2}, S3={s3}, S4={s4}")

    from reborn_ml.config import TrainingConfig
    from reborn_ml.training.style_trainer import RebornStyleTrainer

    config = TrainingConfig(
        grid_size=args.grid,
        stage1_steps=s1,
        stage2_steps=s2,
        stage3_steps=s3,
        stage4_steps=s4,
        enable_adversarial=(s4 > 0),
        batch_size=args.batch,
        train_samples=min(50, args.steps),
        checkpoint_dir=str(out_dir / "checkpoints"),
        verbose=True,
    )

    t0 = time.time()
    trainer = RebornStyleTrainer(config)
    params, model, history = trainer.run()
    total_time = time.time() - t0

    print(f"\n  總耗時：{total_time:.1f}s")
    for stage, losses in history.items():
        if losses:
            print(f"  {stage}: {len(losses)} 步, 最終={losses[-1]:.6f}, 最小={min(losses):.6f}")

    with open(out_dir / "loss_history.json", "w") as f:
        json.dump({k: [float(v) for v in vs] for k, vs in history.items()}, f, indent=2)

    print(f"\n結果已儲存至：{out_dir}")
    return True


if __name__ == "__main__":
    import sys
    sys.exit(0 if main() else 1)
