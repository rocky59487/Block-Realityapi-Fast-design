"""
exp_008 — A100 訓練效能基準

測量項目：
  - Steps/second（各批次大小：1, 2, 4, 8）
  - GPU 記憶體用量
  - JIT 編譯時間 vs 穩態推論時間
  - 多 GPU 擴展效率（若可用）
  - 完整 13,000 步訓練預估時間

執行方式：
    python -m reborn.experiments.exp_008_a100_benchmark [--grid 16]
"""
from __future__ import annotations
import sys
import time
import json
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

import numpy as np

OUTPUT_DIR = Path(__file__).parent / "outputs" / "exp_008"
OUTPUT_DIR.mkdir(parents=True, exist_ok=True)


def benchmark_forward(grid_size: int = 16, batch_sizes: list = None) -> dict:
    """StyleConditionedSSGO 前向傳播效能基準。"""
    import jax
    import jax.numpy as jnp
    from reborn.models.style_net import StyleConditionedSSGO

    if batch_sizes is None:
        batch_sizes = [1, 2, 4]

    model = StyleConditionedSSGO(hidden=48, modes=6)
    rng = jax.random.PRNGKey(0)
    L = grid_size

    results = {}
    for B in batch_sizes:
        print(f"\n  批次大小 B={B}, 網格 {L}³ ...")
        dummy_x = jnp.zeros((B, L, L, L, 6))
        dummy_sid = jnp.array([0] * B)

        # 初始化
        variables = model.init(rng, dummy_x, dummy_sid, update_stats=False)

        # JIT 編譯（第一次呼叫）
        apply_fn = jax.jit(lambda p, x, s: model.apply({"params": p}, x, s, update_stats=False))

        t0 = time.time()
        _ = apply_fn(variables["params"], dummy_x, dummy_sid).block_until_ready()
        jit_time = time.time() - t0

        # 穩態推論（10 次平均）
        times = []
        for _ in range(10):
            t0 = time.time()
            _ = apply_fn(variables["params"], dummy_x, dummy_sid).block_until_ready()
            times.append(time.time() - t0)

        mean_ms = np.mean(times) * 1000
        std_ms = np.std(times) * 1000

        results[f"B{B}"] = {
            "jit_compile_s": round(jit_time, 2),
            "forward_mean_ms": round(mean_ms, 1),
            "forward_std_ms": round(std_ms, 1),
            "steps_per_second": round(1000.0 / mean_ms, 1) if mean_ms > 0 else 0,
        }
        print(f"    JIT 編譯：{jit_time:.2f}s")
        print(f"    前向傳播：{mean_ms:.1f} ± {std_ms:.1f} ms")
        print(f"    吞吐量：{results[f'B{B}']['steps_per_second']:.1f} steps/s")

    return results


def benchmark_training_step(grid_size: int = 8) -> dict:
    """單步訓練效能基準（含梯度計算）。"""
    import jax
    import jax.numpy as jnp
    import optax
    from flax.training import train_state
    from reborn.models.style_net import StyleConditionedSSGO

    print(f"\n  訓練步基準（grid={grid_size}³, B=1）...")
    model = StyleConditionedSSGO(hidden=48, modes=6)
    rng = jax.random.PRNGKey(0)
    L = grid_size

    dummy_x = jnp.zeros((1, L, L, L, 6))
    dummy_sid = jnp.array([0])
    variables = model.init(rng, dummy_x, dummy_sid, update_stats=False)

    tx = optax.adam(1e-3)
    state = train_state.TrainState.create(
        apply_fn=lambda p, x, s: model.apply({"params": p}, x, s, update_stats=False),
        params=variables["params"],
        tx=tx,
    )

    @jax.jit
    def train_step(state, x, sid):
        def loss_fn(p):
            pred = state.apply_fn(p, x, sid)
            return jnp.mean(pred ** 2)
        loss, grads = jax.value_and_grad(loss_fn)(state.params)
        return state.apply_gradients(grads=grads), loss

    # JIT 編譯
    t0 = time.time()
    state, _ = train_step(state, dummy_x, dummy_sid)
    jax.tree_util.tree_map(lambda x: x.block_until_ready(), state.params)
    jit_time = time.time() - t0

    # 穩態（10 步）
    times = []
    for _ in range(10):
        t0 = time.time()
        state, loss = train_step(state, dummy_x, dummy_sid)
        jax.tree_util.tree_map(lambda x: x.block_until_ready(), state.params)
        times.append(time.time() - t0)

    mean_ms = np.mean(times) * 1000
    print(f"    JIT 編譯：{jit_time:.2f}s")
    print(f"    訓練步：{mean_ms:.1f} ms/step")
    print(f"    預估 13000 步：{13000 * mean_ms / 1000 / 3600:.1f} 小時")

    return {
        "jit_compile_s": round(jit_time, 2),
        "train_step_ms": round(mean_ms, 1),
        "estimated_13k_hours": round(13000 * mean_ms / 1000 / 3600, 2),
    }


def check_devices() -> dict:
    """檢查可用計算設備。"""
    import jax
    devices = jax.devices()
    info = {
        "n_devices": len(devices),
        "platform": jax.default_backend(),
        "devices": [str(d) for d in devices],
    }
    print(f"\n  平台：{info['platform']}")
    print(f"  設備數：{info['n_devices']}")
    for d in info["devices"]:
        print(f"    {d}")
    return info


def main():
    import argparse
    parser = argparse.ArgumentParser(description="Reborn A100 效能基準")
    parser.add_argument("--grid", type=int, default=8, help="網格尺寸")
    args = parser.parse_args()

    print("=" * 60)
    print("exp_008 — A100 訓練效能基準")
    print("=" * 60)

    results = {}

    # 設備資訊
    results["devices"] = check_devices()

    # 前向傳播
    print("\n--- 前向傳播基準 ---")
    batch_sizes = [1, 2] if args.grid >= 16 else [1, 2, 4]
    results["forward"] = benchmark_forward(args.grid, batch_sizes)

    # 訓練步
    print("\n--- 訓練步基準 ---")
    results["training"] = benchmark_training_step(min(args.grid, 12))

    # 摘要
    print("\n" + "=" * 60)
    print("效能摘要：")
    train = results["training"]
    print(f"  訓練步耗時：{train['train_step_ms']:.1f} ms")
    print(f"  預估完整訓練：{train['estimated_13k_hours']:.1f} 小時")

    with open(OUTPUT_DIR / "benchmark_results.json", "w") as f:
        json.dump(results, f, indent=2, default=str)
    print(f"\n結果已儲存至：{OUTPUT_DIR}")

    return True


if __name__ == "__main__":
    success = main()
    sys.exit(0 if success else 1)
