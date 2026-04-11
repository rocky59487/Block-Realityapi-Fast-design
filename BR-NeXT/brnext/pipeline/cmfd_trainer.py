"""Cascaded Multi-Fidelity Distillation (CMFD) trainer.

Stages:
  1. LEA pre-training (low-freq spectral alignment)
  2. PFSF distillation (phi + mid-high freq)
  3. FEM fine-tuning (PCGrad + uncertainty weighting + physics residual)
"""
from __future__ import annotations

import argparse
import os
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

import numpy as np


@dataclass
class CMFDConfig:
    grid_size: int = 12
    stage1_samples: int = 10000
    stage2_samples: int = 2000
    stage3_samples: int = 200
    stage1_steps: int = 5000
    stage2_steps: int = 5000
    stage3_steps: int = 10000
    batch_size: int = 4
    lr: float = 1e-3
    hidden: int = 48
    modes: int = 6
    seed: int = 42
    output_dir: str = "brnext_output"
    cache_dir: str = "brnext_output/cache"
    use_cache: bool = True
    augment: bool = True


class CMFDTrainer:
    """End-to-end CMFD trainer for SSGO."""

    def __init__(self, cfg: CMFDConfig,
                 on_log: Callable[[str], None] | None = None,
                 on_step: Callable[[str, int, float], None] | None = None,
                 tracker=None):
        import hashlib
        self.cfg = cfg
        self.on_log = on_log or print
        self.on_step = on_step or (lambda stage, step, loss: None)
        self.output_dir = Path(cfg.output_dir)
        self.output_dir.mkdir(parents=True, exist_ok=True)
        self._stop = False
        self.tracker = tracker

        # Cache infra
        self.cache_dir = Path(cfg.cache_dir)
        self.cache_dir.mkdir(parents=True, exist_ok=True)
        self.registry = None
        self.zarr_store = None
        self.config_hash = hashlib.sha256(
            repr((cfg.grid_size, cfg.seed, cfg.augment)).encode()
        ).hexdigest()[:16]
        if cfg.use_cache:
            try:
                from brnext.data import DatasetRegistry, ZarrDatasetStore
                self.registry = DatasetRegistry(self.cache_dir / "dataset_registry.duckdb")
                self.zarr_store = ZarrDatasetStore(self.cache_dir / "zarr_store")
            except Exception as e:
                self._log(f"  Cache init failed ({e}), proceeding without cache.")
                self.registry = None
                self.zarr_store = None

    def _log(self, msg: str):
        self.on_log(msg)

    def stop(self):
        self._stop = True

    def run(self):
        t0 = time.time()
        self._log("═══ BR-NeXT CMFD Trainer ═══")
        self._log(f"Config: {self.cfg}")
        if self.tracker:
            self.tracker.start_run(run_name="cmfd")
            self.tracker.log_params(self.cfg.__dict__)

        # ── Stage 1: LEA Pre-train ──
        params_s1, model = self._stage1_lea()
        if self._stop:
            return None

        # ── Stage 2: PFSF Distill ──
        params_s2, model = self._stage2_pfsf(params_s1, model)
        if self._stop:
            return None

        # ── Stage 3: FEM Fine-tune ──
        params_s3, model, history = self._stage3_fem(params_s2, model)
        if self._stop:
            return None

        # ── Export ──
        self._export(model, params_s3)

        self._log(f"═══ CMFD complete in {time.time()-t0:.0f}s ═══")
        if self.tracker:
            self.tracker.end_run()
        return params_s3, model, history

    def _import_jax(self):
        os.environ.setdefault("JAX_PLATFORM_NAME", "cpu")
        import jax
        import jax.numpy as jnp
        import optax
        import flax.linen as nn
        from flax.training import train_state
        return jax, jnp, optax, nn, train_state

    def _build_model(self):
        from brnext.models.ssgo import SSGO
        return SSGO(
            hidden=self.cfg.hidden,
            modes=self.cfg.modes,
            n_global_layers=3,
            n_focal_layers=2,
            n_backbone_layers=2,
            moe_hidden=32,
        )

    def _stage1_lea(self):
        self._log("\n═══ Stage 1: LEA Pre-training ═══")
        jax, jnp, optax, nn, train_state = self._import_jax()
        from brnext.models.losses import freq_align_loss
        from brnext.teachers.lea_teacher import LEATeacher
        from brnext.pipeline.structure_gen import generate_structure

        model = self._build_model()
        rng = jax.random.PRNGKey(self.cfg.seed)
        L = self.cfg.grid_size
        dummy = jnp.zeros((1, L, L, L, 6))
        variables = model.init(rng, dummy)

        opt = optax.chain(
            optax.clip_by_global_norm(1.0),
            optax.adamw(self.cfg.lr, weight_decay=1e-4),
        )
        state = train_state.TrainState.create(
            apply_fn=model.apply, params=variables["params"], tx=opt)

        teacher = LEATeacher()
        np_rng = np.random.default_rng(self.cfg.seed)
        styles = teacher.STYLES

        @jax.jit
        def train_step(state, x, target):
            def loss_fn(p):
                pred = model.apply({"params": p}, x)
                return freq_align_loss(pred[..., :6], target, x[..., 0], band="low")
            loss, grads = jax.value_and_grad(loss_fn)(state.params)
            return state.apply_gradients(grads=grads), loss

        for step in range(1, self.cfg.stage1_steps + 1):
            if self._stop:
                break
            style = styles[int(np_rng.integers(len(styles)))]
            struct = generate_structure(L, np_rng, style)
            if not struct.occupancy.any():
                continue
            target = teacher.compute(
                style, struct.occupancy, struct.E_field, struct.density_field)
            target = jnp.array(target)[None]
            x = self._build_input(struct)[None]
            state, loss = train_step(state, x, target)
            self.on_step("LEA", step, float(loss))
            if step % 500 == 0:
                self._log(f"  [S1 step {step}/{self.cfg.stage1_steps}] loss={float(loss):.6f}")
                if self.tracker:
                    self.tracker.log_metrics({"S1_loss": float(loss)}, step=step)

        self._log("  Stage 1 complete.")
        return state.params, model

    def _stage2_pfsf(self, params, model):
        self._log("\n═══ Stage 2: PFSF Distillation ═══")
        jax, jnp, optax, nn, train_state = self._import_jax()
        from brnext.models.losses import freq_align_loss, huber_loss
        from brnext.pipeline.async_data_loader import (
            AsyncBuffer, pfsf_worker, structure_generator, augmented_pfsf_worker
        )

        opt = optax.chain(
            optax.clip_by_global_norm(1.0),
            optax.adamw(self.cfg.lr, weight_decay=1e-4),
        )
        state = train_state.TrainState.create(
            apply_fn=model.apply, params=params, tx=opt)

        np_rng = np.random.default_rng(self.cfg.seed + 1)
        styles = ["bridge", "cantilever", "arch", "spiral", "tree", "cave", "overhang"]
        from brnext.pipeline.async_data_loader import CurriculumSampler
        sampler = CurriculumSampler(styles, self.cfg.grid_size, np_rng)
        n_attempts = self.cfg.stage2_samples * 3
        progresses = np.linspace(0.0, 1.0, n_attempts)
        style_list = [sampler.sample_styles(1, p)[0] for p in progresses]

        if self.cfg.augment:
            gen = (
                (self.cfg.grid_size, self.cfg.seed + 1 + i, style_list[i], True)
                for i in range(n_attempts)
            )
            worker = augmented_pfsf_worker
        else:
            from brnext.pipeline.structure_gen import generate_structure
            def _curriculum_gen():
                rng = np.random.default_rng(self.cfg.seed + 1)
                for style in style_list:
                    yield generate_structure(self.cfg.grid_size, rng, style)
            gen = _curriculum_gen()
            worker = pfsf_worker

        self._log("  Launching async PFSF workers...")
        with AsyncBuffer(
            gen, worker, n_workers=4, chunksize=2,
            registry=self.registry, zarr_store=self.zarr_store,
            config_hash=f"{self.config_hash}_pfsf", grid_size=self.cfg.grid_size,
            target_samples=self.cfg.stage2_samples,
        ) as buf:
            buf.prefetch(min_buffer=min(50, self.cfg.stage2_samples))
            self._log(f"  PFSF buffer ready: {len(buf)}")

            @jax.jit
            def train_step(state, x, phi_t):
                def loss_fn(p):
                    pred = model.apply({"params": p}, x)
                    loss_phi = huber_loss(pred[..., 9], phi_t, delta=1.0).mean()
                    loss_freq = freq_align_loss(pred, jnp.concatenate([
                        jnp.zeros_like(pred[..., :6]),
                        jnp.zeros_like(pred[..., 6:9]),
                        phi_t[..., None],
                    ], axis=-1), x[..., 0], band="midhigh")
                    return loss_phi + 0.5 * loss_freq
                loss, grads = jax.value_and_grad(loss_fn)(state.params)
                return state.apply_gradients(grads=grads), loss

            for step in range(1, self.cfg.stage2_steps + 1):
                if self._stop:
                    break
                buf.poll(max_size=self.cfg.stage2_samples)
                if len(buf) == 0:
                    self._log("  PFSF buffer empty, waiting...")
                    buf.prefetch(min_buffer=1, timeout=5.0)
                    if len(buf) == 0:
                        break
                struct, phi = buf.sample(np_rng, n=1)[0]
                x = self._build_input(struct)[None]
                phi_t = jnp.array(phi)[None]
                state, loss = train_step(state, x, phi_t)
                self.on_step("PFSF", step, float(loss))
                if step % 500 == 0:
                    self._log(f"  [S2 step {step}/{self.cfg.stage2_steps}] loss={float(loss):.6f} buffer={len(buf)}")
                    if self.tracker:
                        self.tracker.log_metrics({"S2_loss": float(loss), "S2_buffer": len(buf)}, step=step)

        self._log("  Stage 2 complete.")
        return state.params, model

    def _stage3_fem(self, params, model):
        self._log("\n═══ Stage 3: FEM Fine-tuning ═══")
        jax, jnp, optax, nn, train_state = self._import_jax()
        from brnext.models.losses import hybrid_task_loss, physics_residual_loss
        from brnext.pipeline.async_data_loader import (
            AsyncBuffer, fem_worker, structure_generator, augmented_fem_worker, CurriculumSampler
        )

        # Uncertainty weighting: 5 tasks
        all_params = {
            "model": params,
            "log_sigma": jnp.zeros(5),  # stress, disp, phi, cons, phys
        }
        schedule = optax.warmup_cosine_decay_schedule(
            0.0, self.cfg.lr, warmup_steps=min(500, self.cfg.stage3_steps // 10),
            decay_steps=self.cfg.stage3_steps, end_value=self.cfg.lr * 0.01)
        opt = optax.chain(
            optax.clip_by_global_norm(1.0),
            optax.adamw(schedule, weight_decay=1e-4),
        )
        state = train_state.TrainState.create(
            apply_fn=model.apply, params=all_params, tx=opt)

        np_rng = np.random.default_rng(self.cfg.seed + 2)
        styles = ["bridge", "cantilever", "arch", "spiral", "tree", "cave", "overhang"]
        sampler = CurriculumSampler(styles, self.cfg.grid_size, np_rng)
        n_attempts = self.cfg.stage3_samples * 3
        progresses = np.linspace(0.0, 1.0, n_attempts)
        style_list = [sampler.sample_styles(1, p)[0] for p in progresses]

        if self.cfg.augment:
            gen = (
                (self.cfg.grid_size, self.cfg.seed + 2 + i, style_list[i], True)
                for i in range(n_attempts)
            )
            worker = augmented_fem_worker
        else:
            from brnext.pipeline.structure_gen import generate_structure
            def _curriculum_gen():
                rng = np.random.default_rng(self.cfg.seed + 2)
                for style in style_list:
                    yield generate_structure(self.cfg.grid_size, rng, style)
            gen = _curriculum_gen()
            worker = fem_worker

        self._log("  Launching async FEM workers (zero-bubble)...")
        with AsyncBuffer(
            gen, worker, n_workers=4, chunksize=2,
            registry=self.registry, zarr_store=self.zarr_store,
            config_hash=f"{self.config_hash}_fem", grid_size=self.cfg.grid_size,
            target_samples=self.cfg.stage3_samples,
        ) as buf:
            buf.prefetch(min_buffer=min(20, self.cfg.stage3_samples))
            self._log(f"  FEM buffer ready: {len(buf)}")

            # Compute scales from current buffer (filter PFSF-only cached items)
            fem_items = [item for item in buf.buffer if item[1] is not None]
            if len(fem_items) == 0:
                raise RuntimeError("No FEM-converged samples in buffer")
            all_stress = np.concatenate([f.stress.reshape(-1, 6) for _, f, _ in fem_items])
            stress_scale = float(np.percentile(np.abs(all_stress[all_stress != 0]), 99)) + 1e-8
            all_disp = np.concatenate([f.displacement.reshape(-1, 3) for _, f, _ in fem_items])
            disp_scale = float(np.percentile(np.abs(all_disp[all_disp != 0]), 99)) + 1e-8
            all_phi = np.concatenate([p.flatten() for _, _, p in fem_items])
            phi_p99 = float(np.percentile(np.abs(all_phi[all_phi != 0]), 99)) + 1e-8

            @jax.jit
            def train_step(state, x, stress_t, disp_t, phi_t, mask, E, nu, rho, fem_trust):
                mp = state.params["model"]
                log_s = state.params["log_sigma"]

                def loss_fn(p):
                    pred = model.apply({"params": p}, x)
                    tasks = hybrid_task_loss(
                        pred[..., :6], pred[..., 6:9], pred[..., 9:],
                        stress_t, disp_t, phi_t, mask, fem_trust,
                    )
                    phys = physics_residual_loss(
                        pred[..., :6], pred[..., 6:9], E, nu, mask, rho,
                    )
                    losses = jnp.stack([tasks["stress"], tasks["disp"], tasks["phi"],
                                        tasks["consistency"], phys])
                    # Uncertainty weighting
                    weights = jnp.exp(-2.0 * log_s) / 2.0
                    total = jnp.sum(losses * weights + log_s)
                    return total

                total, grads = jax.value_and_grad(loss_fn)(mp)
                # log_sigma grad (analytical)
                pred = model.apply({"params": mp}, x)
                tasks = hybrid_task_loss(
                    pred[..., :6], pred[..., 6:9], pred[..., 9:],
                    stress_t, disp_t, phi_t, mask, fem_trust,
                )
                phys = physics_residual_loss(
                    pred[..., :6], pred[..., 6:9], E, nu, mask, rho,
                )
                losses = jnp.stack([tasks["stress"], tasks["disp"], tasks["phi"],
                                    tasks["consistency"], phys])
                g_log_s = 1.0 - losses * jnp.exp(-2.0 * log_s)
                all_grads = {"model": grads, "log_sigma": g_log_s}
                return state.apply_gradients(grads=all_grads), total

            from brnext.pipeline.async_data_loader import MixupCollator
            collator = MixupCollator(alpha=0.4, prob=0.5, rng=np_rng)

            history = []
            for step in range(1, self.cfg.stage3_steps + 1):
                if self._stop:
                    break
                buf.poll(max_size=self.cfg.stage3_samples)
                if len(buf) == 0:
                    self._log("  FEM buffer empty, waiting...")
                    buf.prefetch(min_buffer=1, timeout=5.0)
                    if len(buf) == 0:
                        break

                batch_items = buf.sample(np_rng, n=self.cfg.batch_size)
                mixed = collator.maybe_mix(batch_items)
                struct, fem, phi = mixed[0]
                if fem is None:
                    continue
                x = self._build_input(struct)[None]
                mask = jnp.array(struct.occupancy)[None]

                # Corner-aware fem trust
                occ = struct.occupancy.astype(bool)
                exposure = np.zeros(occ.shape, dtype=np.int32)
                for ax in range(3):
                    for d in (-1, 1):
                        shifted = np.roll(occ, d, axis=ax)
                        exposure += (occ & ~shifted).astype(np.int32)
                fem_trust_np = np.where(exposure > 5, 0.1, 1.0).astype(np.float32)
                fem_trust = jnp.array(fem_trust_np)[None]

                stress_t = jnp.array(fem.stress)[None] / stress_scale
                disp_t = jnp.array(fem.displacement)[None] / disp_scale
                phi_t = jnp.array(phi)[None]
                E = jnp.array(struct.E_field)[None]
                nu = jnp.array(struct.nu_field)[None]
                rho = jnp.array(struct.density_field)[None]

                state, loss = train_step(state, x, stress_t, disp_t, phi_t,
                                         mask, E, nu, rho, fem_trust)
                history.append(float(loss))
                self.on_step("FEM", step, float(loss))
                if step % 500 == 0:
                    self._log(f"  [S3 step {step}/{self.cfg.stage3_steps}] loss={float(loss):.6f} buffer={len(buf)}")
                    if self.tracker:
                        self.tracker.log_metrics({"S3_loss": float(loss), "S3_buffer": len(buf)}, step=step)

        self._log("  Stage 3 complete.")
        return state.params["model"], model, {
            "loss": history,
            "stress_scale": stress_scale,
            "disp_scale": disp_scale,
            "phi_p99": phi_p99,
        }

    def _build_input(self, struct):
        import jax.numpy as jnp
        from brnext.config import load_norm_constants
        norm = load_norm_constants()
        occ = jnp.array(struct.occupancy)
        E = jnp.array(struct.E_field) / norm["E_SCALE"]
        nu = jnp.array(struct.nu_field)
        rho = jnp.array(struct.density_field) / norm["RHO_SCALE"]
        rc = jnp.array(struct.rcomp_field) / norm["RC_SCALE"]
        rt = jnp.array(struct.rtens_field) / norm["RT_SCALE"]
        return jnp.stack([occ, E, nu, rho, rc, rt], axis=-1)

    def _export(self, model, params):
        self._log("\n═══ Exporting ONNX ═══")
        import jax.numpy as jnp
        import shutil
        from brnext.export.onnx_export import export_ssgo_to_onnx
        from brnext.config import _CONFIG_PATH
        L = self.cfg.grid_size
        dummy = (jnp.zeros((1, L, L, L, 6)),)
        onnx_path = self.output_dir / "brnext_ssgo.onnx"
        try:
            export_ssgo_to_onnx(model, params, dummy, str(onnx_path))
            self._log(f"  Exported: {onnx_path}")
            if _CONFIG_PATH.exists():
                shutil.copy(_CONFIG_PATH, self.output_dir / "normalization.yaml")
            if self.tracker:
                self.tracker.log_artifact(onnx_path)
                self.tracker.log_artifact(self.output_dir / "normalization.yaml")
        except Exception as e:
            self._log(f"  ONNX export failed: {e}")


def main():
    parser = argparse.ArgumentParser(description="BR-NeXT CMFD Trainer")
    parser.add_argument("--grid", type=int, default=12)
    parser.add_argument("--steps-s1", type=int, default=5000)
    parser.add_argument("--steps-s2", type=int, default=5000)
    parser.add_argument("--steps-s3", type=int, default=10000)
    parser.add_argument("--output", type=str, default="brnext_output")
    args = parser.parse_args()

    cfg = CMFDConfig(
        grid_size=args.grid,
        stage1_steps=args.steps_s1,
        stage2_steps=args.steps_s2,
        stage3_steps=args.steps_s3,
        output_dir=args.output,
    )
    trainer = CMFDTrainer(cfg)
    trainer.run()


if __name__ == "__main__":
    main()
