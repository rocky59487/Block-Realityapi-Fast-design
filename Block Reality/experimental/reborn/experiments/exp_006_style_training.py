"""
exp_006 — StyleConditionedSSGO 訓練

四階段風格條件化訓練管線：
  階段 1：物理預訓練（LEA 頻譜對齊）
  階段 2：風格蒸餾（分析式教師）
  階段 3：聯合微調（7 任務不確定性加權）
  階段 4：對抗精修（可選）

執行方式：
    # 快速煙霧測試（CPU，~2 分鐘）
    python -m reborn.experiments.exp_006_style_training --steps 10 --grid 8

    # A100 完整訓練（~3 小時）
    python -m reborn.experiments.exp_006_style_training --grid 16 --steps 13000

預期結果：
  - 四階段全部完成，無例外
  - 損失曲線整體下降
  - 輸出目錄含：checkpoints, loss_history.json
"""
from __future__ import annotations
import sys
import argparse
import json
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

import numpy as np

OUTPUT_DIR = Path(__file__).parent / "outputs" / "exp_006"
OUTPUT_DIR.mkdir(parents=True, exist_ok=True)


def main():
    parser = argparse.ArgumentParser(description="Reborn StyleConditionedSSGO 訓練")
    parser.add_argument("--grid", type=int, default=8, help="網格尺寸")
    parser.add_argument("--steps", type=int, default=40, help="總步數（自動分配至 4 階段）")
    parser.add_argument("--batch", type=int, default=1, help="批次大小")
    parser.add_argument("--no-adv", action="store_true", help="停用���抗階段")
    args = parser.parse_args()

    # 步數分配：3:3:5:2 比例
    total = args.steps
    s1 = max(1, int(total * 3 / 13))
    s2 = max(1, int(total * 3 / 13))
    s3 = max(1, int(total * 5 / 13))
    s4 = max(1, total - s1 - s2 - s3) if not args.no_adv else 0

    print("=" * 60)
    print("exp_006 — StyleConditionedSSGO 訓練")
    print("=" * 60)
    print(f"  網格：{args.grid}³")
    print(f"  步數：S1={s1}, S2={s2}, S3={s3}, S4={s4}")
    print(f"  批次：{args.batch}")

    from reborn.config import TrainingConfig
    from reborn.training.style_trainer import RebornStyleTrainer

    config = TrainingConfig(
        grid_size=args.grid,
        stage1_steps=s1,
        stage2_steps=s2,
        stage3_steps=s3,
        stage4_steps=s4,
        enable_adversarial=(s4 > 0),
        batch_size=args.batch,
        train_samples=min(50, args.steps),
        checkpoint_dir=str(OUTPUT_DIR / "checkpoints"),
        verbose=True,
    )

    t0 = time.time()
    trainer = RebornStyleTrainer(config)
    params, model, history = trainer.run()
    total_time = time.time() - t0

    # 結��摘要
    print(f"\n  總耗時：{total_time:.1f}s")
    for stage, losses in history.items():
        if losses:
            print(f"  {stage}: {len(losses)} 步, "
                  f"最終損失={losses[-1]:.6f}, "
                  f"最小損失={min(losses):.6f}")

    # 存檔
    with open(OUTPUT_DIR / "loss_history.json", "w") as f:
        json.dump({k: [float(v) for v in vs] for k, vs in history.items()}, f, indent=2)

    print(f"\n結果已儲存至：{OUTPUT_DIR}")
    return True


if __name__ == "__main__":
    success = main()
    sys.exit(0 if success else 1)
