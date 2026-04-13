"""Robust SSGO trainer with data augmentation, curriculum learning, and batched training.

This trainer replaces the original CMFDTrainer with:
  - Batched training across all stages
  - On-the-fly data augmentation (rotation, erosion)
  - Curriculum sampling (easy -> hard geometries)
  - Dropout for regularization
  - OOD benchmark evaluation after training
"""
from __future__ import annotations

import argparse
import json
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

import numpy as np
import jax
import jax.numpy as jnp
import optax
import flax.linen as nn
from flax.training import train_state

from brnext.models.ssgo import SSGO
from brnext.models.losses import freq_align_loss, huber_loss, hybrid_task_loss, physics_residual_loss
from brnext.pipeline.structure_gen import generate_structure, augment_structure, compute_complexity
from brnext.pipeline.async_data_loader import (
    AsyncBuffer, pfsf_worker,
    CurriculumSampler,
)


def fast_fem_worker(struct):
    """Picklable FEM worker with two-tier tolerance and outlier rejection."""
    from brnext.teachers.fem_teacher import solve_fem
    from brnext.teachers.pfsf_teacher import solve_pfsf_phi
    if int(struct.occupancy.sum()) < 3:
        return None
    # Tier 1: tight tolerance for accuracy
    fem = solve_fem(struct.occupancy, struct.anchors, struct.E_field, struct.nu_field, struct.density_field, cg_tol=1e-5, cg_maxiter=1000)
    # Tier 2: relaxed tolerance for difficult geometries (e.g. tower)
    if not fem.converged:
        fem = solve_fem(struct.occupancy, struct.anchors, struct.E_field, struct.nu_field, struct.density_field, cg_tol=1e-3, cg_maxiter=500)
    if not fem.converged:
        return None
    # Reject physically implausible outliers that slip through CG convergence
    mask = struct.occupancy.astype(bool)
    grid_size = struct.occupancy.shape[0]
    max_disp = grid_size * 0.5
    if (np.abs(fem.displacement[mask]) > max_disp).any() or (np.abs(fem.stress[mask]) > 1e9).any():
        return None
    phi = solve_pfsf_phi(struct.occupancy, struct.E_field, struct.density_field)
    return (struct, fem, phi)
from brnext.evaluation.ood_benchmark import run_ood_benchmark


@dataclass
class RobustConfig:
    grid_size: int = 8
    stage1_steps: int = 2000
    stage2_steps: int = 2000
    stage3_steps: int = 3000
    stage1_samples: int = 5000
    stage2_samples: int = 2000
    stage3_samples: int = 500
    batch_size: int = 4
    lr: float = 5e-4
    hidden: int = 48
    modes: int = 8
    dropout_rate: float = 0.05
    adv_epsilon: float = 0.01
    use_curriculum: bool = True
    use_augmentation: bool = True
    use_mixup: bool = False
    use_adversarial: bool = False
    use_physics_residual: bool = False
    physics_residual_weight: float = 0.01
    seed: int = 42
    output_dir: str = "brnext_output"


def build_input(struct) -> jnp.ndarray:
    occ = jnp.array(struct.occupancy, dtype=jnp.float32)
    E = jnp.array(struct.E_field, dtype=jnp.float32) / 200e9
    nu = jnp.array(struct.nu_field, dtype=jnp.float32)
    rho = jnp.array(struct.density_field, dtype=jnp.float32) / 7850.0
    rc = jnp.array(struct.rcomp_field, dtype=jnp.float32) / 250.0
    rt = jnp.array(struct.rtens_field, dtype=jnp.float32) / 500.0
    anchor = jnp.array(struct.anchors, dtype=jnp.float32)
    return jnp.stack([occ, E, nu, rho, rc, rt, anchor], axis=-1)


# Fixed physical reference for log-displacement (1 micrometer for 8m structure)
DISP_REF = 1e-6


def disp_to_log(disp: jnp.ndarray, ref: float = DISP_REF) -> jnp.ndarray:
    """Signed log transform for displacement. Maps [1e-6, 1e-1] -> [0.7, 11.5]."""
    return jnp.sign(disp) * jnp.log1p(jnp.abs(disp) / ref)


def log_to_disp(disp_log: jnp.ndarray, ref: float = DISP_REF) -> jnp.ndarray:
    """Inverse of signed log transform."""
    return jnp.sign(disp_log) * (jnp.expm1(jnp.abs(disp_log)) * ref)


def model_forward(model, p, x, train=False, dropout_rng=None):
    """Forward pass with optional dropout RNG."""
    if train and model.dropout_rate > 0.0:
        return model.apply({"params": p}, x, train=train, rngs={"dropout": dropout_rng})
    return model.apply({"params": p}, x, train=train)


class RobustTrainer:
    """End-to-end robust trainer for SSGO."""

    def __init__(self, cfg: RobustConfig,
                 on_log: Callable[[str], None] | None = None):
        self.cfg = cfg
        self.on_log = on_log or print
        self.output_dir = Path(cfg.output_dir)
        self.output_dir.mkdir(parents=True, exist_ok=True)
        self._stop = False
        self.np_rng = np.random.default_rng(cfg.seed)
        self.history = {"s1": [], "s2": [], "s3": []}

    def _log(self, msg: str):
        self.on_log(msg)

    def stop(self):
        self._stop = True

    def run(self):
        t0 = time.time()
        self._log("═══ BR-NeXT Robust SSGO Trainer ═══")
        self._log(f"Config: {self.cfg}")

        model = SSGO(
            hidden=self.cfg.hidden,
            modes=self.cfg.modes,
            n_global_layers=3,
            n_focal_layers=2,
            n_backbone_layers=2,
            moe_hidden=32,
            dropout_rate=self.cfg.dropout_rate,
        )
        rng = jax.random.PRNGKey(self.cfg.seed)
        L = self.cfg.grid_size
        dummy = jnp.zeros((1, L, L, L, 7))
        variables = model.init(rng, dummy, train=False)
        params = variables["params"]

        # Stage 1
        params = self._stage1_lea(model, params)
        if self._stop:
            return None

        # Stage 2
        params = self._stage2_pfsf(model, params)
        if self._stop:
            return None

        # Stage 3
        params, scales = self._stage3_fem(model, params)
        if self._stop:
            return None

        # Export
        self._export(model, params)

        # OOD Benchmark
        self._run_benchmark(model, params, scales)

        # Save history
        with open(self.output_dir / "history.json", "w") as f:
            json.dump(self.history, f)

        self._log(f"═══ Training complete in {time.time()-t0:.0f}s ═══")
        return params, model, self.history

    # ───────────────────────────── Stage 1 ─────────────────────────────

    def _stage1_lea(self, model, params):
        self._log("\n═══ Stage 1: Base Warm-up ═══")
        styles = ["tower", "bridge", "cantilever", "arch", "spiral", "tree", "cave", "overhang"]
        curriculum = CurriculumSampler(styles, self.cfg.grid_size, self.np_rng)

        opt = optax.chain(
            optax.clip_by_global_norm(0.5),
            optax.adamw(self.cfg.lr, weight_decay=1e-4),
        )
        state = train_state.TrainState.create(
            apply_fn=lambda p, x: model_forward(model, p, x, train=False),
            params=params, tx=opt,
        )
        rng = jax.random.PRNGKey(self.cfg.seed)

        @jax.jit
        def train_step(state, x, target, dropout_rng):
            def loss_fn(p):
                pred = model_forward(model, p, x, train=True, dropout_rng=dropout_rng)
                # Mask out displacement in Stage 1 to avoid teaching wrong physics
                mask = x[..., 0:1]
                loss_stress = jnp.mean((pred[..., :6] - target[..., :6]) ** 2 * mask)
                loss_phi = jnp.mean((pred[..., 9:] - target[..., 9:]) ** 2 * mask)
                return loss_stress + loss_phi
            loss, grads = jax.value_and_grad(loss_fn)(state.params)
            return state.apply_gradients(grads=grads), loss

        for step in range(1, self.cfg.stage1_steps + 1):
            if self._stop:
                break
            progress = step / self.cfg.stage1_steps
            batch_styles = curriculum.sample_styles(self.cfg.batch_size, progress) if self.cfg.use_curriculum else [styles[int(self.np_rng.integers(len(styles)))] for _ in range(self.cfg.batch_size)]

            xs, targets = [], []
            for style in batch_styles:
                struct = generate_structure(self.cfg.grid_size, self.np_rng, style)
                if self.cfg.use_augmentation and self.np_rng.random() < 0.5:
                    struct = augment_structure(struct, self.np_rng)
                if not struct.occupancy.any():
                    continue
                x = build_input(struct)
                # Stage 1 target: align with normalized occupancy & material properties
                # Use E-field for stress-like channels, occupancy for displacement & phi
                # to reduce scale-shift shock when transitioning to FEM fine-tuning
                target = jnp.concatenate([
                    jnp.tile(x[..., 1:2], (1, 1, 1, 6)),  # stress ~ E
                    jnp.tile(x[..., 0:1], (1, 1, 1, 3)),  # disp ~ occupancy
                    x[..., 0:1],                           # phi ~ occupancy
                ], axis=-1)
                xs.append(x)
                targets.append(target)

            if len(xs) == 0:
                continue
            xb = jnp.stack(xs)
            tb = jnp.stack(targets)
            if not (jnp.isfinite(xb).all() and jnp.isfinite(tb).all()):
                continue
            rng, dropout_rng = jax.random.split(rng)
            state, loss = train_step(state, xb, tb, dropout_rng)
            if jnp.isfinite(loss):
                self.history["s1"].append(float(loss))
            if step % max(1, self.cfg.stage1_steps // 6) == 0:
                self._log(f"  [S1 step {step}/{self.cfg.stage1_steps}] loss={float(loss):.6f}")

        self._log("  Stage 1 complete.")
        return state.params

    # ───────────────────────────── Stage 2 ─────────────────────────────

    def _stage2_pfsf(self, model, params):
        self._log("\n═══ Stage 2: PFSF Distillation ═══")
        styles = ["bridge", "cantilever", "arch", "spiral", "tree", "cave", "overhang", "tower"]
        curriculum = CurriculumSampler(styles, self.cfg.grid_size, self.np_rng)
        opt = optax.chain(
            optax.clip_by_global_norm(0.5),
            optax.adamw(self.cfg.lr * 0.5, weight_decay=1e-4),
        )
        state = train_state.TrainState.create(
            apply_fn=lambda p, x: model_forward(model, p, x, train=False),
            params=params, tx=opt,
        )
        rng = jax.random.PRNGKey(self.cfg.seed + 1)

        gen = self._pfsf_generator(styles)

        self._log("  Launching async PFSF workers...")
        with AsyncBuffer(gen, pfsf_worker, n_workers=2, chunksize=2) as buf:
            buf.prefetch(min_buffer=min(20, self.cfg.stage2_samples))
            self._log(f"  PFSF buffer ready: {len(buf)}")

            # Compute phi scale safely from buffer
            all_phi = np.concatenate([p.flatten() for _, p in buf.buffer])
            all_phi = all_phi[np.isfinite(all_phi)]
            phi_scale = float(np.percentile(np.abs(all_phi[all_phi != 0]), 99)) + 1e-8
            self._log(f"  phi_scale={phi_scale:.3e}")

            @jax.jit
            def train_step(state, x, phi_t, dropout_rng):
                def loss_fn(p):
                    pred = model_forward(model, p, x, train=True, dropout_rng=dropout_rng)
                    loss_phi = huber_loss(pred[..., 9], phi_t, delta=1.0).mean()
                    return loss_phi
                loss, grads = jax.value_and_grad(loss_fn)(state.params)
                return state.apply_gradients(grads=grads), loss

            for step in range(1, self.cfg.stage2_steps + 1):
                if self._stop:
                    break
                buf.poll(max_size=self.cfg.stage2_samples)
                if len(buf) == 0:
                    buf.prefetch(min_buffer=1, timeout=5.0)
                    if len(buf) == 0:
                        break

                samples = buf.sample(self.np_rng, n=self.cfg.batch_size)
                xs, phis = [], []
                for struct, phi in samples:
                    if not np.isfinite(phi).all():
                        continue
                    xs.append(build_input(struct))
                    phis.append(jnp.array(phi) / phi_scale)
                if len(xs) == 0:
                    continue
                xb = jnp.stack(xs)
                pb = jnp.stack(phis)
                if not (jnp.isfinite(xb).all() and jnp.isfinite(pb).all()):
                    continue
                rng, dropout_rng = jax.random.split(rng)
                state, loss = train_step(state, xb, pb, dropout_rng)
                if jnp.isfinite(loss) and loss < 1e6:
                    self.history["s2"].append(float(loss))
                if step % max(1, self.cfg.stage2_steps // 6) == 0:
                    self._log(f"  [S2 step {step}/{self.cfg.stage2_steps}] loss={float(loss):.6f} buffer={len(buf)}")

        self._log("  Stage 2 complete.")
        return state.params

    def _pfsf_generator(self, styles):
        """Yield structures for PFSF buffer."""
        curriculum = CurriculumSampler(styles, self.cfg.grid_size, self.np_rng)
        for i in range(self.cfg.stage2_samples * 3):
            progress = i / (self.cfg.stage2_samples * 3)
            style = curriculum.sample_styles(1, progress)[0] if self.cfg.use_curriculum else styles[int(self.np_rng.integers(len(styles)))]
            struct = generate_structure(self.cfg.grid_size, self.np_rng, style)
            if self.cfg.use_augmentation and self.np_rng.random() < 0.5:
                struct = augment_structure(struct, self.np_rng)
            yield struct

    # ───────────────────────────── Stage 3 ─────────────────────────────

    def _stage3_fem(self, model, params):
        self._log("\n═══ Stage 3: FEM Fine-tuning (simplified) ═══")
        styles = ["bridge", "cantilever", "arch", "spiral", "tree", "cave", "overhang", "tower"]

        opt = optax.chain(
            optax.clip_by_global_norm(0.5),
            optax.adamw(self.cfg.lr * 0.3, weight_decay=1e-4),
        )
        state = train_state.TrainState.create(
            apply_fn=lambda p, x: model_forward(model, p, x, train=False),
            params=params, tx=opt,
        )
        rng = jax.random.PRNGKey(self.cfg.seed + 2)

        gen = self._fem_generator(styles)

        self._log("  Launching async FEM workers...")
        with AsyncBuffer(gen, fast_fem_worker, n_workers=2, chunksize=2) as buf:
            buf.prefetch(min_buffer=min(10, self.cfg.stage3_samples))
            self._log(f"  FEM buffer ready: {len(buf)}")

            # Compute scales safely with outlier guard
            all_stress = np.concatenate([f.stress.reshape(-1) for _, f, _ in buf.buffer])
            all_stress = all_stress[np.isfinite(all_stress)]
            stress_scale = float(np.percentile(np.abs(all_stress[all_stress != 0]), 99)) + 1e-8
            all_phi = np.concatenate([p.flatten() for _, _, p in buf.buffer])
            all_phi = all_phi[np.isfinite(all_phi)]
            phi_scale = float(np.percentile(np.abs(all_phi[all_phi != 0]), 99)) + 1e-8
            self._log(f"  Scales: stress={stress_scale:.3e} phi={phi_scale:.3e}")

            # Progressive consistency schedule: warm up for 1/3, ramp for 1/3, hold for 1/3
            warmup_steps = max(1, self.cfg.stage3_steps // 3)
            ramp_steps = max(1, self.cfg.stage3_steps // 3)
            target_cons_weight = 0.1

            @jax.jit
            def train_step(state, x, stress_t, disp_log_t, phi_t, mask, fem_trust, cons_weight, dropout_rng):
                def loss_fn(p):
                    pred = model_forward(model, p, x, train=True, dropout_rng=dropout_rng)
                    tasks = hybrid_task_loss(
                        pred[..., :6], pred[..., 6:9], pred[..., 9:],
                        stress_t, disp_log_t, phi_t, mask, fem_trust,
                    )
                    total = tasks["stress"] + tasks["disp"] + tasks["phi"] + cons_weight * tasks["consistency"]
                    if self.cfg.use_physics_residual:
                        pred_disp_phy = log_to_disp(pred[..., 6:9])
                        E_field = x[..., 1] * 200e9
                        nu_field = x[..., 2]
                        pr_loss = physics_residual_loss(
                            pred[..., :6] * stress_scale,
                            pred_disp_phy,
                            E_field,
                            nu_field,
                            mask,
                        )
                        total += self.cfg.physics_residual_weight * pr_loss
                    return total

                total, grads = jax.value_and_grad(loss_fn)(state.params)
                return grads, total

            for step in range(1, self.cfg.stage3_steps + 1):
                if self._stop:
                    break
                # Compute progressive consistency weight
                if step <= warmup_steps:
                    cons_w = 0.0
                elif step <= warmup_steps + ramp_steps:
                    cons_w = target_cons_weight * (step - warmup_steps) / ramp_steps
                else:
                    cons_w = target_cons_weight

                buf.poll(max_size=self.cfg.stage3_samples)
                if len(buf) == 0:
                    buf.prefetch(min_buffer=1, timeout=5.0)
                    if len(buf) == 0:
                        break

                samples = buf.sample(self.np_rng, n=self.cfg.batch_size)
                xs, stress_ts, disp_log_ts, phi_ts, masks, trusts = [], [], [], [], [], []
                for struct, fem, phi in samples:
                    if not (fem.converged and np.isfinite(fem.stress).all() and np.isfinite(fem.displacement).all() and np.isfinite(phi).all()):
                        continue
                    occ = struct.occupancy.astype(bool)
                    max_disp = self.cfg.grid_size * 0.5
                    if (np.abs(fem.displacement[occ]) > max_disp).any() or (np.abs(fem.stress[occ]) > 1e9).any():
                        continue
                    exposure = np.zeros(occ.shape, dtype=np.int32)
                    for ax in range(3):
                        for d in (-1, 1):
                            shifted = np.roll(occ, d, axis=ax)
                            exposure += (occ & ~shifted).astype(np.int32)
                    fem_trust = np.where(exposure > 5, 0.1, 1.0).astype(np.float32)

                    xs.append(build_input(struct))
                    stress_ts.append(jnp.array(fem.stress) / stress_scale)
                    disp_log_ts.append(disp_to_log(jnp.array(fem.displacement)))
                    phi_ts.append(jnp.array(phi) / phi_scale)
                    masks.append(jnp.array(struct.occupancy))
                    trusts.append(fem_trust)

                if len(xs) == 0:
                    continue
                xb = jnp.stack(xs)
                sb = jnp.stack(stress_ts)
                db = jnp.stack(disp_log_ts)
                pb = jnp.stack(phi_ts)
                mb = jnp.stack(masks)
                tb = jnp.stack(trusts)
                if not all(jnp.isfinite(t).all() for t in [xb, sb, db, pb, mb, tb]):
                    continue
                rng, dropout_rng = jax.random.split(rng)
                grads, loss = train_step(state, xb, sb, db, pb, mb, tb, cons_w, dropout_rng)
                if jnp.isfinite(loss) and loss < 1e6:
                    state = state.apply_gradients(grads=grads)
                    self.history["s3"].append(float(loss))
                if step % max(1, self.cfg.stage3_steps // 6) == 0:
                    self._log(f"  [S3 step {step}/{self.cfg.stage3_steps}] loss={float(loss):.6f} cons_w={cons_w:.3f} buffer={len(buf)}")

        self._log("  Stage 3 complete.")
        return state.params, {"stress_scale": stress_scale, "phi_scale": phi_scale}

    def _fem_generator(self, styles):
        """Yield structures for FEM buffer."""
        curriculum = CurriculumSampler(styles, self.cfg.grid_size, self.np_rng)
        for i in range(self.cfg.stage3_samples * 3):
            progress = i / (self.cfg.stage3_samples * 3)
            style = curriculum.sample_styles(1, progress)[0] if self.cfg.use_curriculum else styles[int(self.np_rng.integers(len(styles)))]
            struct = generate_structure(self.cfg.grid_size, self.np_rng, style)
            if self.cfg.use_augmentation and self.np_rng.random() < 0.5:
                struct = augment_structure(struct, self.np_rng)
            yield struct

    # ───────────────────────────── Export & Eval ─────────────────────────────

    def _export(self, model, params):
        self._log("\n═══ Exporting ONNX ═══")
        from brnext.export.onnx_export import export_ssgo_to_onnx
        L = self.cfg.grid_size
        dummy = (jnp.zeros((1, L, L, L, 7)),)  # tuple of arrays, matching export_ssgo_to_onnx signature
        onnx_path = self.output_dir / "ssgo_robust_medium.onnx"
        try:
            export_ssgo_to_onnx(model, params, dummy, str(onnx_path))
            self._log(f"  Exported: {onnx_path}")
        except Exception as e:
            self._log(f"  ONNX export failed: {e}")

        # Also save msgpack
        from flax import serialization
        msgpack_path = self.output_dir / "ssgo_robust_medium.msgpack"
        with open(msgpack_path, "wb") as f:
            f.write(serialization.to_bytes(params))
        self._log(f"  Saved params: {msgpack_path}")

    def _run_benchmark(self, model, params, scales):
        self._log("\n═══ OOD Benchmark ═══")
        s_scale = scales.get("stress_scale", 1.0)
        p_scale = scales.get("phi_scale", 1.0)

        def apply_fn(p, x):
            pred = model_forward(model, p, x, train=False)
            # Un-normalize stress & phi back to physical units.
            # Convert log-displacement back to meters.
            pred = pred.at[..., :6].multiply(s_scale)
            pred = pred.at[..., 6:9].set(log_to_disp(pred[..., 6:9]))
            pred = pred.at[..., 9:].multiply(p_scale)
            return pred

        summary = run_ood_benchmark(
            apply_fn, params,
            grid_size=self.cfg.grid_size,
            n_samples=50,
            output_dir=str(self.cfg.output_dir),
        )
        self._log(f"  ID MAE(phi)={summary['id_mae_phi']:.4f}  OOD MAE(phi)={summary['ood_mae_phi']:.4f}  Gap={summary['ood_gap_relative']:.1%}")


def main():
    parser = argparse.ArgumentParser(description="Train Robust SSGO")
    parser.add_argument("--grid", type=int, default=8)
    parser.add_argument("--steps-s1", type=int, default=2000)
    parser.add_argument("--steps-s2", type=int, default=2000)
    parser.add_argument("--steps-s3", type=int, default=3000)
    parser.add_argument("--batch-size", type=int, default=4)
    parser.add_argument("--lr", type=float, default=5e-4)
    parser.add_argument("--dropout", type=float, default=0.05)
    parser.add_argument("--no-curriculum", action="store_true")
    parser.add_argument("--no-aug", action="store_true")
    parser.add_argument("--mixup", action="store_true", help="Enable mixup (default: disabled)")
    parser.add_argument("--adversarial", action="store_true", help="Enable adversarial training (default: disabled)")
    parser.add_argument("--physics-residual", action="store_true", help="Enable physics residual loss in Stage 3")
    parser.add_argument("--pr-weight", type=float, default=0.01, help="Weight for physics residual loss")
    parser.add_argument("--output", type=str, default="brnext_output")
    parser.add_argument("--hidden", type=int, default=48)
    parser.add_argument("--modes", type=int, default=8)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    cfg = RobustConfig(
        grid_size=args.grid,
        stage1_steps=args.steps_s1,
        stage2_steps=args.steps_s2,
        stage3_steps=args.steps_s3,
        batch_size=args.batch_size,
        lr=args.lr,
        hidden=args.hidden,
        modes=args.modes,
        dropout_rate=args.dropout,
        use_curriculum=not args.no_curriculum,
        use_augmentation=not args.no_aug,
        use_mixup=args.mixup,
        use_adversarial=args.adversarial,
        use_physics_residual=args.physics_residual,
        physics_residual_weight=args.pr_weight,
        output_dir=args.output,
        seed=args.seed,
    )
    trainer = RobustTrainer(cfg)
    trainer.run()


if __name__ == "__main__":
    main()
