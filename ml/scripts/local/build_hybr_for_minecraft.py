"""
BUILD HYBR FOR MINECRAFT — Windows-local production pipeline
============================================================

Generates a medium-sized HYBR surrogate model that can be directly loaded
by Block Reality's Java ONNX runtime (OnnxPFSFRuntime).

Output artifacts:
  - hybr_for_minecraft.onnx   -> place in minecraft/config/blockreality/surrogates/
  - normalization.yaml        -> place alongside the .onnx

This script avoids Windows multiprocessing issues by generating synthetic
FEM targets in the main thread. The neural architecture (AdaptiveSSGO) is
identical to the A100-scale version; only the training data is mocked for
speed on consumer CPUs.
"""
from __future__ import annotations

import os
import sys
import time
import json
import shutil
import pathlib
from dataclasses import dataclass

# ── Bootstrap monorepo packages ──
_ROOT = pathlib.Path(__file__).resolve().parent
for _pkg in ["reborn-ml/src", "HYBR", "BR-NeXT", "brml"]:
    _p = str(_ROOT / _pkg)
    if _p not in sys.path:
        sys.path.insert(0, _p)

os.environ.setdefault("JAX_PLATFORM_NAME", "cpu")

import numpy as np
import jax
import jax.numpy as jnp
import optax
from flax.training import train_state

# ── Imports from the stack ──
from hybr.core.adaptive_ssgo import AdaptiveSSGO
from hybr.training.stability_utils import make_optimizer_with_warmup, spectral_norm_penalty
from brnext.models.losses import hybrid_task_loss, physics_residual_loss
from brnext.pipeline.structure_gen import generate_structure
from brnext.export.onnx_export import export_ssgo_to_onnx
from brnext.config import _CONFIG_PATH
from hybr.core.materialize import materialize_static_ssgo


@dataclass
class LocalConfig:
    """Medium-sized config tuned for a local Windows workstation."""
    grid_size: int = 16
    train_samples: int = 32
    train_steps: int = 200
    batch_size: int = 4
    peak_lr: float = 8e-4
    warmup_steps: int = 30
    weight_decay: float = 1e-4
    hidden: int = 32
    modes: int = 4
    n_global_layers: int = 2
    n_focal_layers: int = 1
    n_backbone_layers: int = 2
    moe_hidden: int = 24
    latent_dim: int = 24
    hypernet_widths: tuple = (64, 64)
    rank: int = 2
    encoder_type: str = "spectral"
    seed: int = 42


def build_input_from_struct(struct):
    occ = jnp.array(struct.occupancy)
    E = jnp.array(struct.E_field) / 200e9
    nu = jnp.array(struct.nu_field)
    rho = jnp.array(struct.density_field) / 7850.0
    rc = jnp.array(struct.rcomp_field) / 250.0
    rt = jnp.array(struct.rtens_field) / 500.0
    return jnp.stack([occ, E, nu, rho, rc, rt], axis=-1)


def generate_mock_dataset(cfg: LocalConfig):
    """
    Generate a small in-memory dataset without spawning Windows workers.
    Targets are synthetic but geometry inputs are real.
    """
    rng = np.random.default_rng(cfg.seed)
    dataset = []
    styles = ["tower", "bridge", "cantilever", "arch", "spiral"]
    print(f"[BUILD] Generating {cfg.train_samples} synthetic samples (grid={cfg.grid_size})...")
    attempts = 0
    while len(dataset) < cfg.train_samples and attempts < cfg.train_samples * 10:
        attempts += 1
        style = rng.choice(styles)
        struct = generate_structure(cfg.grid_size, rng, style)
        occ = jnp.array(struct.occupancy)
        if not occ.any():
            continue
        x = build_input_from_struct(struct)
        # Synthetic target: smooth function of occupancy so the net can learn it
        target_stress = jnp.tile(x[..., 1:2], (1, 1, 1, 6)) * occ[..., None]
        target_disp = jnp.tile(x[..., 2:3], (1, 1, 1, 3)) * occ[..., None]
        target_phi = occ[..., None]
        target = jnp.concatenate([target_stress, target_disp, target_phi], axis=-1)
        dataset.append((x, target, occ,
                        jnp.array(struct.E_field),
                        jnp.array(struct.nu_field),
                        jnp.array(struct.density_field)))
    print(f"[BUILD] Dataset ready: {len(dataset)} samples ({attempts} attempts)")
    return dataset


def train_hybr(cfg: LocalConfig, dataset):
    print("[BUILD] Initializing AdaptiveSSGO...")
    model = AdaptiveSSGO(
        hidden=cfg.hidden,
        modes=cfg.modes,
        n_global_layers=cfg.n_global_layers,
        n_focal_layers=cfg.n_focal_layers,
        n_backbone_layers=cfg.n_backbone_layers,
        moe_hidden=cfg.moe_hidden,
        latent_dim=cfg.latent_dim,
        hypernet_widths=cfg.hypernet_widths,
        rank=cfg.rank,
        encoder_type=cfg.encoder_type,
    )
    rng = jax.random.PRNGKey(cfg.seed)
    L = cfg.grid_size
    dummy = jnp.zeros((1, L, L, L, 6))
    variables = model.init(rng, dummy, update_stats=False, mutable=["params", "batch_stats"])
    params = variables["params"]

    def _label_leaf(path, _):
        path_str = "/".join(str(p.key) for p in path)
        return "hyper" if "HyperMLP" in path_str or "SpectralWeightHead" in path_str else "base"

    mask = jax.tree_util.tree_map_with_path(_label_leaf, params)
    tx = optax.multi_transform({
        "base": make_optimizer_with_warmup(
            peak_lr=cfg.peak_lr,
            warmup_steps=cfg.warmup_steps,
            total_steps=cfg.train_steps,
            weight_decay=cfg.weight_decay,
        ),
        "hyper": make_optimizer_with_warmup(
            peak_lr=cfg.peak_lr,
            warmup_steps=cfg.warmup_steps,
            total_steps=cfg.train_steps,
            weight_decay=cfg.weight_decay,
        ),
    }, mask)

    state = train_state.TrainState.create(
        apply_fn=lambda p, x: model.apply(
            {"params": p, "batch_stats": {}}, x, update_stats=False
        ),
        params=params,
        tx=tx,
    )

    # Stack dataset into JAX arrays
    xs = jnp.stack([d[0] for d in dataset])
    ts = jnp.stack([d[1] for d in dataset])
    ms = jnp.stack([d[2] for d in dataset])
    Es = jnp.stack([d[3] for d in dataset])
    nus = jnp.stack([d[4] for d in dataset])
    rhos = jnp.stack([d[5] for d in dataset])

    @jax.jit
    def train_step(state, xb, tb, mb, Eb, nub, rhob):
        def loss_fn(p):
            pred = state.apply_fn(p, xb)
            tasks = hybrid_task_loss(
                pred[..., :6], pred[..., 6:9], pred[..., 9:],
                tb[..., :6], tb[..., 6:9], tb[..., 9],
                mb, jnp.ones_like(mb, dtype=jnp.float32),
            )
            phys = physics_residual_loss(
                pred[..., :6], pred[..., 6:9], Eb, nub, mb, rhob,
            )
            loss = (
                tasks["stress"] + tasks["disp"] + tasks["phi"]
                + tasks["consistency"] + phys
            )
            reg = spectral_norm_penalty(p, coeff=1e-6)
            return loss + reg
        loss, grads = jax.value_and_grad(loss_fn)(state.params)
        return state.apply_gradients(grads=grads), loss

    n_samples = len(dataset)
    history = []
    t0 = time.time()
    print(f"[BUILD] Training {cfg.train_steps} steps (batch_size={cfg.batch_size})...")
    for step in range(1, cfg.train_steps + 1):
        perm = jax.random.permutation(jax.random.PRNGKey(step), n_samples)
        batch_losses = []
        for i in range(0, n_samples, cfg.batch_size):
            idx = perm[i:i + cfg.batch_size]
            xb = xs[idx]
            tb = ts[idx]
            mb = ms[idx]
            Eb = Es[idx]
            nub = nus[idx]
            rhob = rhos[idx]
            state, loss = train_step(state, xb, tb, mb, Eb, nub, rhob)
            batch_losses.append(float(loss))
        avg_loss = float(jnp.mean(jnp.array(batch_losses)))
        history.append(avg_loss)
        if step % 20 == 0 or step == 1:
            print(f"  [step {step:03d}/{cfg.train_steps}] loss={avg_loss:.6f}")
    print(f"[BUILD] Training complete in {time.time()-t0:.1f}s")
    return state.params, model, history


def export_for_minecraft(params, model, cfg: LocalConfig, output_dir: pathlib.Path):
    output_dir.mkdir(parents=True, exist_ok=True)
    L = cfg.grid_size
    occ = jnp.ones((1, L, L, L))
    variables = {"params": params, "batch_stats": {}}
    print("[BUILD] Materializing static SSGO from adaptive weights...")
    static_params, static_model = materialize_static_ssgo(model, variables, occ)

    dummy = (jnp.zeros((1, L, L, L, 6)),)
    onnx_path = output_dir / "hybr_for_minecraft.onnx"
    print(f"[BUILD] Exporting ONNX -> {onnx_path}")
    export_ssgo_to_onnx(
        static_model,
        static_params,
        dummy,
        str(onnx_path),
        opset_version=17,
        dynamic_batch=False,
    )

    # Copy normalization config
    norm_path = output_dir / "normalization.yaml"
    if _CONFIG_PATH.exists():
        shutil.copy(_CONFIG_PATH, norm_path)
        print(f"[BUILD] Copied normalization config -> {norm_path}")
    else:
        print(f"[WARN]  normalization.yaml not found at {_CONFIG_PATH}")

    return onnx_path, norm_path


def validate_onnx(onnx_path: pathlib.Path, cfg: LocalConfig):
    try:
        import onnxruntime as ort
    except ImportError:
        print("[WARN]  onnxruntime not installed; skipping runtime validation.")
        return

    sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    input_name = sess.get_inputs()[0].name
    L = cfg.grid_size
    dummy = np.zeros((1, L, L, L, 6), dtype=np.float32)
    out = sess.run(None, {input_name: dummy})[0]
    expected = (1, L, L, L, 10)
    assert out.shape == expected, f"Shape mismatch: {out.shape} vs {expected}"
    print(f"[BUILD] ONNX runtime validation OK — output shape {out.shape}")


def main():
    print("=" * 70)
    print(" BLOCK REALITY — HYBR Minecraft Surrogate Builder")
    print("=" * 70)

    cfg = LocalConfig()
    dataset = generate_mock_dataset(cfg)
    if len(dataset) == 0:
        raise RuntimeError("Failed to generate any valid training samples.")

    params, model, history = train_hybr(cfg, dataset)

    output_dir = _ROOT / "experiments" / "outputs" / "hybr_minecraft"
    onnx_path, norm_path = export_for_minecraft(params, model, cfg, output_dir)
    validate_onnx(onnx_path, cfg)

    # Write metadata
    meta = {
        "grid_size": cfg.grid_size,
        "hidden": cfg.hidden,
        "modes": cfg.modes,
        "train_steps": cfg.train_steps,
        "init_loss": float(history[0]),
        "final_loss": float(history[-1]),
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%S"),
    }
    with open(output_dir / "build_meta.json", "w", encoding="utf-8") as f:
        json.dump(meta, f, indent=2)

    print("\n" + "=" * 70)
    print(" BUILD COMPLETE")
    print("=" * 70)
    print(f"\n  ONNX model    : {onnx_path}")
    print(f"  Normalization : {norm_path}")
    print(f"\n  -> Copy both files into your Minecraft instance:")
    print(f"     config/blockreality/surrogates/hybr_for_minecraft.onnx")
    print(f"     config/blockreality/surrogates/normalization.yaml")
    print(f"\n  Model spec:")
    print(f"     grid={cfg.grid_size}, hidden={cfg.hidden}, modes={cfg.modes}")
    print(f"     input=[B,{cfg.grid_size},{cfg.grid_size},{cfg.grid_size},6]")
    print(f"     output=[B,{cfg.grid_size},{cfg.grid_size},{cfg.grid_size},10]")
    print(f"     training loss: {meta['init_loss']:.6f} -> {meta['final_loss']:.6f}")
    print("=" * 70)


if __name__ == "__main__":
    main()
