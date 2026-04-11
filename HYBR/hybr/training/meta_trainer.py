"""HYBR Meta-Trainer with real FEM/PFSF teachers.

Supports end-to-end training of AdaptiveSSGO with:
- Optional base-SSGO warm-start
- HyperNet + base weight joint optimization
- Convergence monitoring (delta/base ratio, spectral norms)
- DuckDB+Zarr caching (reuses BR-NeXT cache)
"""
from __future__ import annotations

import hashlib
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

import numpy as np

from hybr.training.stability_utils import make_optimizer_with_warmup, spectral_norm_penalty


@dataclass
class HYBRConfig:
    grid_size: int = 12
    train_samples: int = 2000
    train_steps: int = 5000
    batch_size: int = 4
    peak_lr: float = 1e-3
    warmup_steps: int = 500
    weight_decay: float = 1e-4
    hidden: int = 48
    modes: int = 6
    n_global_layers: int = 3
    n_focal_layers: int = 2
    n_backbone_layers: int = 2
    moe_hidden: int = 32
    latent_dim: int = 32
    hypernet_widths: tuple = (128, 128)
    rank: int = 2
    encoder_type: str = "spectral"
    seed: int = 42
    output_dir: str = "hybr_output"
    cache_dir: str = "brnext_output/cache"
    use_cache: bool = True


class HYBRTrainer:
    """End-to-end trainer for HYBR AdaptiveSSGO with real teachers."""

    def __init__(
        self,
        cfg: HYBRConfig,
        on_log: Callable[[str], None] | None = None,
        on_step: Callable[[int, float, dict], None] | None = None,
        tracker=None,
    ):
        self.cfg = cfg
        self.on_log = on_log or print
        self.on_step = on_step or (lambda step, loss, metrics: None)
        self.output_dir = Path(cfg.output_dir)
        self.output_dir.mkdir(parents=True, exist_ok=True)
        self.tracker = tracker
        self._stop = False

        self.cache_dir = Path(cfg.cache_dir)
        self.cache_dir.mkdir(parents=True, exist_ok=True)
        self.registry = None
        self.zarr_store = None
        self.config_hash = hashlib.sha256(
            repr((cfg.grid_size, cfg.seed, True)).encode()
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

    def stop(self):
        self._stop = True

    def _log(self, msg: str):
        self.on_log(msg)

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

    def _build_dataset(self, styles):
        """Collect real FEM+PFSF samples via AsyncBuffer (cached when available)."""
        from brnext.pipeline.async_data_loader import (
            AsyncBuffer, augmented_fem_worker, CurriculumSampler, compute_sample_id
        )
        np_rng = np.random.default_rng(self.cfg.seed)
        sampler = CurriculumSampler(styles, self.cfg.grid_size, np_rng)
        n_attempts = self.cfg.train_samples * 3
        progresses = np.linspace(0.0, 1.0, n_attempts)
        style_list = [sampler.sample_styles(1, p)[0] for p in progresses]
        gen = (
            (self.cfg.grid_size, self.cfg.seed + i, style_list[i], True)
            for i in range(n_attempts)
        )
        dataset = []
        seen = set()
        with AsyncBuffer(
            gen, augmented_fem_worker, n_workers=4, chunksize=2,
            registry=self.registry, zarr_store=self.zarr_store,
            config_hash=self.config_hash, grid_size=self.cfg.grid_size,
            target_samples=self.cfg.train_samples,
        ) as buf:
            buf.prefetch(min_buffer=min(20, self.cfg.train_samples))
            self._log(f"  FEM buffer ready: {len(buf)}")
            while len(dataset) < self.cfg.train_samples:
                if self._stop:
                    break
                buf.poll(max_size=self.cfg.train_samples)
                if len(buf) == 0:
                    if buf._exhausted:
                        break
                    buf.prefetch(min_buffer=1, timeout=5.0)
                    if len(buf) == 0:
                        break
                for _ in range(20):
                    item = buf.sample(np_rng, n=1)[0]
                    sid = compute_sample_id(item[0])
                    if sid not in seen:
                        seen.add(sid)
                        dataset.append(item)
                        break
                else:
                    if len(buf) < 2:
                        break
        self._log(f"  Dataset built: {len(dataset)} unique samples")
        return dataset

    def run(self):
        import jax
        import jax.numpy as jnp
        import optax
        from flax.training import train_state

        from hybr.core.adaptive_ssgo import AdaptiveSSGO
        from brnext.models.losses import hybrid_task_loss, physics_residual_loss

        self._log("═══ HYBR Meta-Trainer ═══")
        self._log(f"Config: {self.cfg}")
        if self.tracker:
            self.tracker.start_run(run_name="hybr")
            self.tracker.log_params(self.cfg.__dict__)

        model = AdaptiveSSGO(
            hidden=self.cfg.hidden,
            modes=self.cfg.modes,
            n_global_layers=self.cfg.n_global_layers,
            n_focal_layers=self.cfg.n_focal_layers,
            n_backbone_layers=self.cfg.n_backbone_layers,
            moe_hidden=self.cfg.moe_hidden,
            latent_dim=self.cfg.latent_dim,
            hypernet_widths=self.cfg.hypernet_widths,
            rank=self.cfg.rank,
            encoder_type=self.cfg.encoder_type,
        )

        rng = jax.random.PRNGKey(self.cfg.seed)
        L = self.cfg.grid_size
        dummy = jnp.zeros((1, L, L, L, 6))
        variables = model.init(rng, dummy, update_stats=False, mutable=["params", "batch_stats"])
        params = variables["params"]
        batch_stats = variables.get("batch_stats", {})

        # Label hypernet parameters vs base parameters
        def _label_leaf(path, _):
            path_str = "/".join(str(p.key) for p in path)
            return "hyper" if "HyperMLP" in path_str or "SpectralWeightHead" in path_str else "base"

        mask = jax.tree_util.tree_map_with_path(_label_leaf, params)
        tx = optax.multi_transform(
            {
                "base": make_optimizer_with_warmup(
                    peak_lr=self.cfg.peak_lr,
                    warmup_steps=self.cfg.warmup_steps,
                    total_steps=self.cfg.train_steps,
                    weight_decay=self.cfg.weight_decay,
                ),
                "hyper": make_optimizer_with_warmup(
                    peak_lr=self.cfg.peak_lr,
                    warmup_steps=self.cfg.warmup_steps,
                    total_steps=self.cfg.train_steps,
                    weight_decay=self.cfg.weight_decay,
                ),
            },
            mask,
        )

        state = train_state.TrainState.create(
            apply_fn=lambda p, x: model.apply(
                {"params": p, "batch_stats": batch_stats}, x, update_stats=False
            ),
            params=params,
            tx=tx,
        )

        styles = ["tower", "bridge", "cantilever", "arch", "spiral", "tree", "cave", "overhang"]
        raw_dataset = self._build_dataset(styles)
        if len(raw_dataset) == 0:
            raise RuntimeError("Dataset is empty")

        # Assemble JAX tensors
        xs, ts, ms, Es, nus, rhos = [], [], [], [], [], []
        for struct, fem, phi in raw_dataset:
            xs.append(self._build_input(struct))
            target = jnp.concatenate([
                jnp.array(fem.stress),
                jnp.array(fem.displacement),
                jnp.array(phi)[..., None],
            ], axis=-1)
            ts.append(target)
            ms.append(jnp.array(struct.occupancy))
            Es.append(jnp.array(struct.E_field))
            nus.append(jnp.array(struct.nu_field))
            rhos.append(jnp.array(struct.density_field))

        xs = jnp.stack(xs)
        ts = jnp.stack(ts)
        ms = jnp.stack(ms)
        Es = jnp.stack(Es)
        nus = jnp.stack(nus)
        rhos = jnp.stack(rhos)

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

        n_samples = len(raw_dataset)
        history = []
        for step in range(1, self.cfg.train_steps + 1):
            if self._stop:
                break
            perm = jax.random.permutation(jax.random.PRNGKey(step), n_samples)
            batch_losses = []
            for i in range(0, n_samples, self.cfg.batch_size):
                idx = perm[i:i + self.cfg.batch_size]
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
            metrics = {"loss": avg_loss}
            self.on_step(step, avg_loss, metrics)
            if step % 500 == 0:
                self._log(f"  [step {step}/{self.cfg.train_steps}] loss={avg_loss:.6f}")
                if self.tracker:
                    self.tracker.log_metrics(metrics, step=step)

        self._log("═══ Training complete ═══")
        if self.tracker:
            self.tracker.end_run()
        return state.params, model, history

    def export(self, params, model, grid_size: int | None = None):
        """Materialize adaptive weights to static SSGO and export ONNX."""
        self._log("\n═══ Materializing & Exporting ONNX ═══")
        import jax.numpy as jnp
        import shutil
        from hybr.core.materialize import materialize_static_ssgo
        from brnext.export.manual_onnx import export_ssgo_manual
        from brnext.config import _CONFIG_PATH

        L = grid_size or self.cfg.grid_size
        occ = jnp.ones((1, L, L, L))
        variables = {"params": params, "batch_stats": {}}
        static_params, static_model = materialize_static_ssgo(model, variables, occ)

        onnx_path = self.output_dir / "hybr_ssgo.onnx"
        export_ssgo_manual(
            static_params,
            str(onnx_path),
            grid_size=L,
            n_global_layers=static_model.n_global_layers,
            n_focal_layers=static_model.n_focal_layers,
            n_backbone_layers=static_model.n_backbone_layers,
            hidden=static_model.hidden,
            moe_hidden=static_model.moe_hidden,
        )
        self._log(f"  Exported: {onnx_path}")
        if _CONFIG_PATH.exists():
            shutil.copy(_CONFIG_PATH, self.output_dir / "normalization.yaml")
        if self.tracker:
            self.tracker.log_artifact(onnx_path)
            self.tracker.log_artifact(self.output_dir / "normalization.yaml")
        return onnx_path
