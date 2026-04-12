"""
exp_008 — A100 訓練效能基準

測量：steps/s、JIT 編譯時間、記憶體、預估完整訓練時間

執行：
    python -m reborn_ml.experiments.exp_008_a100_benchmark [--grid 16]
    reborn-benchmark
"""
from __future__ import annotations
import argparse
import json
import time
from pathlib import Path

import numpy as np

OUTPUT_DIR = Path(__file__).parent.parent.parent.parent / "outputs" / "exp_008"
OUTPUT_DIR.mkdir(parents=True, exist_ok=True)


def check_devices() -> dict:
    import jax
    devices = jax.devices()
    info = {"n_devices": len(devices), "platform": jax.default_backend(), "devices": [str(d) for d in devices]}
    print(f"\n  平台：{info['platform']}  設備數：{info['n_devices']}")
    for d in info["devices"]:
        print(f"    {d}")
    return info


def benchmark_forward(grid_size: int, batch_sizes: list) -> dict:
    import jax, jax.numpy as jnp
    from reborn_ml.models.style_net import StyleConditionedSSGO

    model = StyleConditionedSSGO(hidden=48, modes=6)
    rng = jax.random.PRNGKey(0)
    L = grid_size
    results = {}

    for B in batch_sizes:
        print(f"\n  B={B}, 網格 {L}³ ...")
        dummy_x = jnp.zeros((B, L, L, L, 6))
        dummy_sid = jnp.array([0] * B)
        variables = model.init(rng, dummy_x, dummy_sid, update_stats=False)

        apply_fn = jax.jit(lambda p, x, s: model.apply({"params": p}, x, s, update_stats=False))

        t0 = time.time()
        _ = apply_fn(variables["params"], dummy_x, dummy_sid).block_until_ready()
        jit_time = time.time() - t0

        times = []
        for _ in range(10):
            t0 = time.time()
            _ = apply_fn(variables["params"], dummy_x, dummy_sid).block_until_ready()
            times.append(time.time() - t0)

        mean_ms = np.mean(times) * 1000
        results[f"B{B}"] = {
            "jit_compile_s": round(jit_time, 2),
            "forward_mean_ms": round(mean_ms, 1),
            "steps_per_second": round(1000.0 / mean_ms, 1) if mean_ms > 0 else 0,
        }
        print(f"    JIT：{jit_time:.2f}s  前向：{mean_ms:.1f}ms  吞吐：{results[f'B{B}']['steps_per_second']:.1f} s/s")

    return results


def benchmark_training_step(grid_size: int) -> dict:
    import jax, jax.numpy as jnp, optax
    from flax.training import train_state
    from reborn_ml.models.style_net import StyleConditionedSSGO

    print(f"\n  訓練步基準（grid={grid_size}³, B=1）...")
    model = StyleConditionedSSGO(hidden=48, modes=6)
    L = grid_size
    dummy_x = jnp.zeros((1, L, L, L, 6))
    dummy_sid = jnp.array([0])
    variables = model.init(jax.random.PRNGKey(0), dummy_x, dummy_sid, update_stats=False)

    tx = optax.adam(1e-3)
    state = train_state.TrainState.create(
        apply_fn=lambda p, x, s: model.apply({"params": p}, x, s, update_stats=False),
        params=variables["params"], tx=tx,
    )

    @jax.jit
    def train_step(state, x, sid):
        def loss_fn(p):
            pred = state.apply_fn(p, x, sid)
            return jnp.mean(pred**2)
        loss, grads = jax.value_and_grad(loss_fn)(state.params)
        return state.apply_gradients(grads=grads), loss

    t0 = time.time()
    state, _ = train_step(state, dummy_x, dummy_sid)
    jax.tree_util.tree_map(lambda x: x.block_until_ready(), state.params)
    jit_time = time.time() - t0

    times = []
    for _ in range(10):
        t0 = time.time()
        state, _ = train_step(state, dummy_x, dummy_sid)
        jax.tree_util.tree_map(lambda x: x.block_until_ready(), state.params)
        times.append(time.time() - t0)

    mean_ms = np.mean(times) * 1000
    est_hours = 13000 * mean_ms / 1000 / 3600
    print(f"    JIT：{jit_time:.2f}s  訓練步：{mean_ms:.1f}ms  預估 13k 步：{est_hours:.1f} 小時")

    return {"jit_compile_s": round(jit_time, 2), "train_step_ms": round(mean_ms, 1), "estimated_13k_hours": round(est_hours, 2)}


def main():
    parser = argparse.ArgumentParser(description="Reborn A100 效能基準")
    parser.add_argument("--grid", type=int, default=8)
    args = parser.parse_args()

    print("=" * 60)
    print("exp_008 — A100 訓練效能基準")
    print("=" * 60)

    results = {}
    results["devices"] = check_devices()

    print("\n--- 前向傳播基準 ---")
    batch_sizes = [1, 2] if args.grid >= 16 else [1, 2, 4]
    results["forward"] = benchmark_forward(args.grid, batch_sizes)

    print("\n--- 訓練步基準 ---")
    results["training"] = benchmark_training_step(min(args.grid, 12))

    t = results["training"]
    print(f"\n  訓練步：{t['train_step_ms']:.1f}ms  預估：{t['estimated_13k_hours']:.1f} 小時")

    with open(OUTPUT_DIR / "benchmark_results.json", "w") as f:
        json.dump(results, f, indent=2, default=str)
    print(f"\n結果已儲存至：{OUTPUT_DIR}")
    return True


if __name__ == "__main__":
    import sys
    sys.exit(0 if main() else 1)
