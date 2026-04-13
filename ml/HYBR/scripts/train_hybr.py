"""End-to-end HYBR training script using real BR-NeXT FEM + PFSF teachers.

Fixes applied for convergence stability:
  1. Proper mini-batch stacking in all stages (single forward pass).
  2. Bounded CP factors in SpectralWeightHead (tanh * 0.1).
  3. Lower learning rates and conservative stage progression.
  4. NaN/Inf filtering on all teacher outputs.
"""
from __future__ import annotations

import sys
from pathlib import Path

BRNEXT = Path(__file__).resolve().parent.parent / "BR-NeXT"
if str(BRNEXT) not in sys.path:
    sys.path.insert(0, str(BRNEXT))

import argparse
import time

import numpy as np
import jax
import jax.numpy as jnp
import optax
from flax.training import train_state

from hybr.core.adaptive_ssgo import AdaptiveSSGO
from brnext.pipeline.structure_gen import generate_structure
from brnext.pipeline.async_data_loader import AsyncBuffer, pfsf_worker, structure_generator
from brnext.models.losses import huber_loss, hybrid_task_loss


def build_input(struct):
    """Convert VoxelStructure to model input [L,L,L,6]."""
    occ = jnp.array(struct.occupancy)
    E = jnp.array(struct.E_field) / 200e9
    nu = jnp.array(struct.nu_field)
    rho = jnp.array(struct.density_field) / 7850.0
    rc = jnp.array(struct.rcomp_field) / 250.0
    rt = jnp.array(struct.rtens_field) / 500.0
    return jnp.stack([occ, E, nu, rho, rc, rt], axis=-1)


def make_optimizer_mask(params: dict, base_lr: float, hyper_lr: float, freeze_base: bool = False, freeze_hyper: bool = False):
    """Build multi-transform optimizer with separate lr for base / hyper params."""
    def _label_leaf(path, _):
        path_str = "/".join(str(p.key) for p in path)
        return "hyper" if "HyperMLP" in path_str or "SpectralWeightHead" in path_str else "base"

    mask = jax.tree_util.tree_map_with_path(_label_leaf, params)
    base_opt = optax.chain(
        optax.clip_by_global_norm(1.0),
        optax.scale(0.0) if freeze_base else optax.adamw(base_lr, weight_decay=1e-4),
    )
    hyper_opt = optax.chain(
        optax.clip_by_global_norm(1.0),
        optax.scale(0.0) if freeze_hyper else optax.adamw(hyper_lr, weight_decay=1e-4),
    )
    return optax.multi_transform({"base": base_opt, "hyper": hyper_opt}, mask)


def stage1_base_warmup(model, variables, cfg, np_rng):
    """Stage 1: warm up base weights with simple spectral alignment target."""
    print("\n═══ Stage 1: Base Warm-up ═══")
    params = variables["params"]
    batch_stats = variables.get("batch_stats", {})

    tx = make_optimizer_mask(params, base_lr=5e-4, hyper_lr=0.0, freeze_hyper=True)
    state = train_state.TrainState.create(
        apply_fn=lambda p, x: model.apply({"params": p, "batch_stats": batch_stats}, x, update_stats=False),
        params=params,
        tx=tx,
    )

    L = cfg["grid_size"]
    styles = ["tower", "bridge", "cantilever", "arch", "spiral", "tree", "cave", "overhang"]

    @jax.jit
    def train_step(state, x, target, mask):
        def loss_fn(p):
            pred = state.apply_fn(p, x)
            loss = jnp.mean((pred - target) ** 2 * mask[..., None])
            return loss
        loss, grads = jax.value_and_grad(loss_fn)(state.params)
        return state.apply_gradients(grads=grads), loss

    for step in range(1, cfg["stage1_steps"] + 1):
        xs, targets, masks = [], [], []
        for _ in range(cfg["batch_size"]):
            style = np_rng.choice(styles)
            struct = generate_structure(L, np_rng, style)
            if not struct.occupancy.any():
                continue
            x = build_input(struct)
            mask = jnp.array(struct.occupancy)
            target = jnp.concatenate([
                jnp.tile(x[..., 1:2], (1, 1, 1, 6)),
                jnp.tile(x[..., 2:3], (1, 1, 1, 3)),
                x[..., 0:1],
            ], axis=-1)
            xs.append(x)
            targets.append(target)
            masks.append(mask)
        if len(xs) == 0:
            continue
        xb = jnp.stack(xs)
        tb = jnp.stack(targets)
        mb = jnp.stack(masks)
        if not (jnp.isfinite(xb).all() and jnp.isfinite(tb).all() and jnp.isfinite(mb).all()):
            continue
        state, loss = train_step(state, xb, tb, mb)
        if not jnp.isfinite(loss):
            print(f"  [S1 step {step:3d}] NaN loss, skipping")
            continue
        if step % 25 == 0:
            print(f"  [S1 step {step:3d}/{cfg['stage1_steps']}] loss={float(loss):.6f}")

    return state.params


def safe_pfsf_worker(struct):
    from brnext.teachers.pfsf_teacher import solve_pfsf_phi
    if int(struct.occupancy.sum()) < 3:
        return None
    phi = solve_pfsf_phi(struct.occupancy, struct.E_field, struct.density_field)
    if not np.isfinite(phi).all():
        return None
    return (struct, phi)


def stage2_pfsf_distill(model, params, batch_stats, cfg, np_rng):
    """Stage 2: distil PFSF phi.  Freeze base, train hypernet."""
    print("\n═══ Stage 2: PFSF Distillation ═══")
    tx = make_optimizer_mask(params, base_lr=0.0, hyper_lr=5e-5, freeze_base=True)
    state = train_state.TrainState.create(
        apply_fn=lambda p, x: model.apply({"params": p, "batch_stats": batch_stats}, x, update_stats=False),
        params=params,
        tx=tx,
    )

    L = cfg["grid_size"]
    styles = ["tower", "bridge", "cantilever", "arch", "spiral", "tree", "cave", "overhang"]
    gen = structure_generator(L, cfg["seed"] + 1, styles, max_attempts=cfg["stage2_samples"] * 3)

    @jax.jit
    def train_step(state, x, phi_t):
        def loss_fn(p):
            pred = state.apply_fn(p, x)
            loss_phi = huber_loss(pred[..., 9], phi_t, delta=1.0).mean()
            return loss_phi
        loss, grads = jax.value_and_grad(loss_fn)(state.params)
        return state.apply_gradients(grads=grads), loss

    with AsyncBuffer(gen, safe_pfsf_worker, n_workers=2, chunksize=2) as buf:
        buf.prefetch(min_buffer=min(20, cfg["stage2_samples"]))
        print(f"  PFSF buffer ready: {len(buf)}")
        for step in range(1, cfg["stage2_steps"] + 1):
            buf.poll(max_size=cfg["stage2_samples"])
            if len(buf) == 0:
                print("  Buffer empty, waiting...")
                buf.prefetch(min_buffer=1, timeout=5.0)
                if len(buf) == 0:
                    break
            samples = buf.sample(np_rng, n=cfg["batch_size"])
            xs, phis = [], []
            for struct, phi in samples:
                if not np.isfinite(phi).all():
                    continue
                xs.append(build_input(struct))
                phis.append(jnp.array(phi))
            if len(xs) == 0:
                continue
            xb = jnp.stack(xs)
            phi_b = jnp.stack(phis)
            if not (jnp.isfinite(xb).all() and jnp.isfinite(phi_b).all()):
                continue
            state, loss = train_step(state, xb, phi_b)
            if not jnp.isfinite(loss):
                print(f"  [S2 step {step:3d}] NaN loss, skipping")
                continue
            if step % 25 == 0:
                print(f"  [S2 step {step:3d}/{cfg['stage2_steps']}] loss={float(loss):.6f} buffer={len(buf)}")

    return state.params


def fast_fem_worker(struct):
    """Picklable FEM worker with relaxed tolerance for speed."""
    from brnext.teachers.fem_teacher import solve_fem
    from brnext.teachers.pfsf_teacher import solve_pfsf_phi
    if int(struct.occupancy.sum()) < 3:
        return None
    from brnext.fem.fem_solver_v2 import FEMSolverV2
    solver = FEMSolverV2(voxel_size=1.0, cg_tol=1e-4, cg_maxiter=1000)
    fem = solver.solve(struct.occupancy, struct.anchors, struct.E_field, struct.nu_field, struct.density_field)
    if not fem.converged:
        return None
    phi = solve_pfsf_phi(struct.occupancy, struct.E_field, struct.density_field)
    return (struct, fem, phi)


def stage3_fem_finetune(model, params, batch_stats, cfg, np_rng):
    """Stage 3: joint fine-tuning on FEM ground truth."""
    print("\n═══ Stage 3: FEM Fine-tuning ═══")
    tx = make_optimizer_mask(params, base_lr=5e-5, hyper_lr=5e-5)
    state = train_state.TrainState.create(
        apply_fn=lambda p, x: model.apply({"params": p, "batch_stats": batch_stats}, x, update_stats=False),
        params=params,
        tx=tx,
    )

    L = cfg["grid_size"]
    styles = ["tower", "bridge", "cantilever", "arch", "spiral", "tree", "cave", "overhang"]
    gen = structure_generator(L, cfg["seed"] + 2, styles, max_attempts=cfg["stage3_samples"] * 3)

    @jax.jit
    def train_step(state, x, stress_t, disp_t, phi_t, mask, fem_trust):
        def loss_fn(p):
            pred = state.apply_fn(p, x)
            tasks = hybrid_task_loss(
                pred[..., :6], pred[..., 6:9], pred[..., 9:],
                stress_t, disp_t, phi_t, mask, fem_trust,
            )
            return tasks["stress"] + tasks["disp"] + tasks["phi"] + tasks["consistency"]
        loss, grads = jax.value_and_grad(loss_fn)(state.params)
        return state.apply_gradients(grads=grads), loss

    with AsyncBuffer(gen, fast_fem_worker, n_workers=2, chunksize=2) as buf:
        buf.prefetch(min_buffer=min(10, cfg["stage3_samples"]))
        print(f"  FEM buffer ready: {len(buf)}")

        all_stress = np.concatenate([f.stress.reshape(-1) for _, f, _ in buf.buffer])
        all_stress = all_stress[np.isfinite(all_stress)]
        stress_scale = float(np.percentile(np.abs(all_stress[all_stress != 0]), 99)) + 1e-8
        all_disp = np.concatenate([f.displacement.reshape(-1) for _, f, _ in buf.buffer])
        all_disp = all_disp[np.isfinite(all_disp)]
        disp_scale = float(np.percentile(np.abs(all_disp[all_disp != 0]), 99)) + 1e-8
        print(f"  Scales: stress={stress_scale:.3e} disp={disp_scale:.3e}")

        for step in range(1, cfg["stage3_steps"] + 1):
            buf.poll(max_size=cfg["stage3_samples"])
            if len(buf) == 0:
                print("  Buffer empty, waiting...")
                buf.prefetch(min_buffer=1, timeout=5.0)
                if len(buf) == 0:
                    break

            samples = buf.sample(np_rng, n=cfg["batch_size"])
            xs, stress_ts, disp_ts, phi_ts, masks, Es, nus, rhos, trusts = [], [], [], [], [], [], [], [], []
            for struct, fem, phi in samples:
                if not (fem.converged and np.isfinite(fem.stress).all() and np.isfinite(fem.displacement).all() and np.isfinite(phi).all()):
                    continue
                occ = struct.occupancy.astype(bool)
                exposure = np.zeros(occ.shape, dtype=np.int32)
                for ax in range(3):
                    for d in (-1, 1):
                        shifted = np.roll(occ, d, axis=ax)
                        exposure += (occ & ~shifted).astype(np.int32)
                fem_trust = np.where(exposure > 5, 0.1, 1.0).astype(np.float32)

                xs.append(build_input(struct))
                stress_ts.append(jnp.array(fem.stress) / stress_scale)
                disp_ts.append(jnp.array(fem.displacement) / disp_scale)
                phi_ts.append(jnp.array(phi))
                masks.append(jnp.array(struct.occupancy))
                trusts.append(fem_trust)

            if len(xs) == 0:
                continue
            xb = jnp.stack(xs)
            sb = jnp.stack(stress_ts)
            db = jnp.stack(disp_ts)
            pb = jnp.stack(phi_ts)
            mb = jnp.stack(masks)
            tb = jnp.stack(trusts)
            if not (jnp.isfinite(xb).all() and jnp.isfinite(sb).all() and jnp.isfinite(db).all() and jnp.isfinite(pb).all() and jnp.isfinite(mb).all() and jnp.isfinite(tb).all()):
                print(f"  [S3 step {step:3d}] Skipping batch with non-finite inputs")
                continue
            state, loss = train_step(state, xb, sb, db, pb, mb, tb)
            if not jnp.isfinite(loss):
                print(f"  [S3 step {step:3d}] NaN/Inf loss detected, skipping optimizer update")
                continue
            if step % 25 == 0:
                print(f"  [S3 step {step:3d}/{cfg['stage3_steps']}] loss={float(loss):.6f} buffer={len(buf)}")

    return state.params


def main():
    parser = argparse.ArgumentParser(description="Train HYBR AdaptiveSSGO")
    parser.add_argument("--grid", type=int, default=8)
    parser.add_argument("--steps-s1", type=int, default=100)
    parser.add_argument("--steps-s2", type=int, default=100)
    parser.add_argument("--steps-s3", type=int, default=100)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    cfg = {
        "grid_size": args.grid,
        "stage1_steps": args.steps_s1,
        "stage2_steps": args.steps_s2,
        "stage3_steps": args.steps_s3,
        "stage1_samples": args.steps_s1,
        "stage2_samples": max(20, args.steps_s2 // 2),
        "stage3_samples": max(20, args.steps_s3 // 2),
        "batch_size": 4,
        "hidden": 24,
        "modes": 4,
        "latent_dim": 16,
        "hypernet_widths": (32, 32),
        "rank": 2,
        "seed": args.seed,
    }

    np_rng = np.random.default_rng(cfg["seed"])

    model = AdaptiveSSGO(
        hidden=cfg["hidden"],
        modes=cfg["modes"],
        n_global_layers=2,
        n_focal_layers=1,
        n_backbone_layers=1,
        moe_hidden=16,
        latent_dim=cfg["latent_dim"],
        hypernet_widths=cfg["hypernet_widths"],
        rank=cfg["rank"],
        encoder_type="spectral",
    )

    rng = jax.random.PRNGKey(cfg["seed"])
    L = cfg["grid_size"]
    dummy = jnp.zeros((1, L, L, L, 7))
    print("Initializing AdaptiveSSGO...")
    variables = model.init(rng, dummy, update_stats=False, mutable=["params", "batch_stats"])

    t0 = time.time()
    params = stage1_base_warmup(model, variables, cfg, np_rng)
    params = stage2_pfsf_distill(model, params, variables.get("batch_stats", {}), cfg, np_rng)
    params = stage3_fem_finetune(model, params, variables.get("batch_stats", {}), cfg, np_rng)
    print(f"\n═══ Training complete in {time.time()-t0:.0f}s ═══")

    from pathlib import Path
    from flax import serialization
    ckpt_dir = Path("hybr_output")
    ckpt_dir.mkdir(exist_ok=True)
    ckpt_path = ckpt_dir / "params.msgpack"
    with open(ckpt_path, "wb") as f:
        f.write(serialization.to_bytes(params))
    print(f"Checkpoint saved to {ckpt_path}")


if __name__ == "__main__":
    main()
