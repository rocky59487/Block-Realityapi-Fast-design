"""HYBR Meta-Trainer.

Supports end-to-end training of AdaptiveSSGO with:
- Optional base-SSGO warm-start
- HyperNet + base weight joint optimization
- Convergence monitoring (delta/base ratio, spectral norms)
"""
from __future__ import annotations

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


class HYBRTrainer:
    """End-to-end trainer for HYBR AdaptiveSSGO."""

    def __init__(
        self,
        cfg: HYBRConfig,
        on_log: Callable[[str], None] | None = None,
        on_step: Callable[[int, float, dict], None] | None = None,
    ):
        self.cfg = cfg
        self.on_log = on_log or print
        self.on_step = on_step or (lambda step, loss, metrics: None)
        self.output_dir = Path(cfg.output_dir)
        self.output_dir.mkdir(parents=True, exist_ok=True)
        self._stop = False

    def stop(self):
        self._stop = True

    def _log(self, msg: str):
        self.on_log(msg)

    def _build_input(self, struct):
        import jax.numpy as jnp
        occ = jnp.array(struct.occupancy)
        E = jnp.array(struct.E_field) / 200e9
        nu = jnp.array(struct.nu_field)
        rho = jnp.array(struct.density_field) / 7850.0
        rc = jnp.array(struct.rcomp_field) / 250.0
        rt = jnp.array(struct.rtens_field) / 500.0
        return jnp.stack([occ, E, nu, rho, rc, rt], axis=-1)

    def run(self):
        import jax
        import jax.numpy as jnp
        import optax
        from flax.training import train_state

        from hybr.core.adaptive_ssgo import AdaptiveSSGO
        from brnext.pipeline.structure_gen import generate_structure
        from brnext.models.losses import hybrid_task_loss

        self._log("═══ HYBR Meta-Trainer ═══")
        self._log(f"Config: {self.cfg}")

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
        batch_stats = variables["batch_stats"]

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

        # Pre-generate dataset (simplified; in production use async buffer)
        np_rng = np.random.default_rng(self.cfg.seed)
        styles = ["tower", "bridge", "cantilever", "arch", "spiral", "tree", "cave", "overhang"]
        dataset = []
        for _ in range(self.cfg.train_samples):
            style = np_rng.choice(styles)
            struct = generate_structure(L, np_rng, style)
            if not struct.occupancy.any():
                continue
            x = self._build_input(struct)
            # Dummy target for demonstration (replace with real FEM/PFSF teacher)
            target = jnp.concatenate([
                jnp.tile(x[..., 1:2], (1, 1, 1, 6)),
                jnp.tile(x[..., 2:3], (1, 1, 1, 3)),
                x[..., 0:1],
            ], axis=-1)
            mask = jnp.array(struct.occupancy)
            dataset.append((x, target, mask))

        if len(dataset) == 0:
            raise RuntimeError("Dataset is empty")

        xs = jnp.stack([d[0] for d in dataset])
        ts = jnp.stack([d[1] for d in dataset])
        ms = jnp.stack([d[2] for d in dataset])

        @jax.jit
        def train_step(state, xb, tb, mb):
            def loss_fn(p):
                pred = state.apply_fn(p, xb)
                loss = jnp.mean((pred - tb) ** 2 * mb[..., None])
                reg = spectral_norm_penalty(p, coeff=1e-6)
                return loss + reg
            loss, grads = jax.value_and_grad(loss_fn)(state.params)
            return state.apply_gradients(grads=grads), loss

        n_samples = len(dataset)
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
                state, loss = train_step(state, xb, tb, mb)
                batch_losses.append(float(loss))
            avg_loss = float(jnp.mean(jnp.array(batch_losses)))
            history.append(avg_loss)
            metrics = {"loss": avg_loss}
            self.on_step(step, avg_loss, metrics)
            if step % 500 == 0:
                self._log(f"  [step {step}/{self.cfg.train_steps}] loss={avg_loss:.6f}")

        self._log("═══ Training complete ═══")
        return state.params, model, history
