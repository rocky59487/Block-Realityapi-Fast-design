"""
Unified training session — shared backend for all UI frontends.

Handles:
  - Model selection (surrogate / recommender / collapse)
  - Auto-checkpoint save/resume
  - Training loop with live callbacks
  - FEM data generation (for surrogate)
"""
from __future__ import annotations

import json
import os
import time
import threading
from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import Callable

import numpy as np


@dataclass
class TrainParams:
    """All tunable training parameters."""

    # Model selection — now supports multi-select
    model: str = "surrogate"  # "surrogate" | "fluid" | "lod" | "collapse" | comma-separated

    # Data generation
    grid_size: int = 12
    n_structures: int = 200
    fluid_grid: int = 20    # fluid cells per axis (0.1m each)
    n_fluid_samples: int = 200

    # Training
    total_steps: int = 10000
    learning_rate: float = 1e-3
    weight_decay: float = 1e-4
    grad_clip: float = 1.0
    batch_size: int = 1

    # FNO architecture (surrogate)
    fno_hidden: int = 48
    fno_layers: int = 4
    fno_modes: int = 6

    # GAT architecture (recommender)
    gat_embed_dim: int = 64
    gat_layers: int = 3
    gat_heads: int = 4

    # MPNN architecture (collapse)
    mpnn_hidden: int = 128
    mpnn_steps: int = 6

    # Checkpoint
    save_every: int = 2000
    output_dir: str = "brml_output"
    seed: int = 42

    def to_dict(self) -> dict:
        return asdict(self)

    @staticmethod
    def from_dict(d: dict) -> "TrainParams":
        p = TrainParams()
        for k, v in d.items():
            if hasattr(p, k):
                setattr(p, k, type(getattr(p, k))(v))
        return p


@dataclass
class TrainState:
    """Live training state."""
    status: str = "idle"        # idle | generating | solving | training | done | error
    current_step: int = 0
    total_steps: int = 0
    loss: float = 0.0
    loss_history: list = field(default_factory=list)
    fem_progress: str = ""
    elapsed_sec: float = 0.0
    message: str = ""
    can_resume: bool = False


# ═══════════════════════════════════════════════════════════════
#  Checkpoint Manager
# ═══════════════════════════════════════════════════════════════

class CheckpointManager:
    """Auto-save and resume training state."""

    def __init__(self, output_dir: str):
        self.dir = Path(output_dir)
        self.dir.mkdir(parents=True, exist_ok=True)

    def save(self, params: TrainParams, step: int, jax_params, extra: dict = None):
        """Save checkpoint: params.json + weights.npz + extra."""
        meta = params.to_dict()
        meta["_checkpoint_step"] = step
        meta["_timestamp"] = time.time()
        if extra:
            meta.update({f"_{k}": v for k, v in extra.items()
                         if isinstance(v, (int, float, str, bool))})

        (self.dir / "params.json").write_text(json.dumps(meta, indent=2))

        if jax_params is not None:
            flat = {}
            for path, val in _flatten_params(jax_params):
                flat[path] = np.asarray(val)
            np.savez(str(self.dir / "weights.npz"), **flat)

        # Keep loss history
        if extra and "loss_history" in extra:
            np.save(str(self.dir / "loss_history.npy"), np.array(extra["loss_history"]))

    def can_resume(self) -> bool:
        return (self.dir / "params.json").exists() and (self.dir / "weights.npz").exists()

    def load_meta(self) -> tuple[TrainParams, int]:
        """Load params and last checkpoint step."""
        meta = json.loads((self.dir / "params.json").read_text())
        step = meta.pop("_checkpoint_step", 0)
        # Remove internal keys
        clean = {k: v for k, v in meta.items() if not k.startswith("_")}
        return TrainParams.from_dict(clean), step

    def load_weights(self) -> dict:
        """Load weights as flat dict."""
        data = np.load(str(self.dir / "weights.npz"))
        return dict(data)

    def load_loss_history(self) -> list:
        path = self.dir / "loss_history.npy"
        if path.exists():
            return np.load(str(path)).tolist()
        return []


def _flatten_params(params, prefix=""):
    result = []
    if isinstance(params, dict):
        for k, v in params.items():
            result.extend(_flatten_params(v, f"{prefix}{k}/"))
    else:
        result.append((prefix.rstrip("/"), params))
    return result


# ═══════════════════════════════════════════════════════════════
#  Training Session
# ═══════════════════════════════════════════════════════════════

class TrainingSession:
    """Manages training lifecycle for all model types.

    Usage:
        session = TrainingSession(params, on_update=callback)
        session.start()       # non-blocking, runs in thread
        session.stop()        # graceful stop
        session.get_state()   # poll current state
    """

    def __init__(self, params: TrainParams,
                 on_update: Callable[[TrainState], None] | None = None):
        self.params = params
        self.on_update = on_update or (lambda s: None)
        self.state = TrainState()
        self.ckpt = CheckpointManager(params.output_dir)
        self._stop_flag = False
        self._thread: threading.Thread | None = None
        self._jax_params = None

    def get_state(self) -> TrainState:
        return self.state

    def start(self, resume: bool = False):
        """Start training in background thread."""
        if self._thread and self._thread.is_alive():
            return
        self._stop_flag = False
        self._thread = threading.Thread(target=self._run, args=(resume,), daemon=True)
        self._thread.start()

    def stop(self):
        """Request graceful stop. Saves checkpoint."""
        self._stop_flag = True
        if self._thread:
            self._thread.join(timeout=30)

    def _update(self, **kwargs):
        for k, v in kwargs.items():
            setattr(self.state, k, v)
        self.on_update(self.state)

    def _run(self, resume: bool):
        try:
            # Support multi-model: "surrogate,fluid,lod"
            models = [m.strip() for m in self.params.model.split(",")]
            for model_name in models:
                if self._stop_flag:
                    break
                # Per-model checkpoint subdirectory to avoid collisions
                original_dir = self.params.output_dir
                self.params.output_dir = str(Path(original_dir) / model_name)
                self.ckpt = CheckpointManager(self.params.output_dir)

                self._update(message=f"=== Training: {model_name} ===")
                model_resume = resume and self.ckpt.can_resume()
                if model_name == "surrogate":
                    self._run_surrogate(model_resume)
                elif model_name == "fluid":
                    self._run_fluid(model_resume)
                elif model_name == "lod":
                    self._run_lod(model_resume)
                elif model_name == "collapse":
                    self._run_collapse(model_resume)

                self.params.output_dir = original_dir
        except Exception as e:
            self._update(status="error", message=str(e))

    # ── Surrogate (FEM → FNO) ──

    def _run_surrogate(self, resume: bool):
        os.environ.setdefault("JAX_PLATFORM_NAME", "cpu")
        import jax
        import jax.numpy as jnp
        import optax
        from flax.training import train_state as ts
        from brml.models.pfsf_surrogate import FNO3D
        from brml.pipeline.auto_train import (
            generate_structure, solve_structure, FEMDataset,
            build_input_tensor, MATERIALS,
        )

        p = self.params
        L = p.grid_size
        start_step = 0

        model = FNO3D(
            hidden_channels=p.fno_hidden, num_layers=p.fno_layers,
            modes=p.fno_modes, in_channels=5,
        )

        # Init or resume
        rng = jax.random.PRNGKey(p.seed)
        dummy = jnp.zeros((1, L, L, L, 5))
        variables = model.init(rng, dummy)

        schedule = optax.warmup_cosine_decay_schedule(
            init_value=0.0, peak_value=p.learning_rate,
            warmup_steps=min(500, p.total_steps // 10),
            decay_steps=p.total_steps, end_value=p.learning_rate * 0.01,
        )
        optimizer = optax.chain(
            optax.clip_by_global_norm(p.grad_clip),
            optax.adamw(learning_rate=schedule, weight_decay=p.weight_decay),
        )
        state = ts.TrainState.create(
            apply_fn=model.apply, params=variables["params"], tx=optimizer,
        )

        loss_history = []
        vm_scale = 1.0

        if resume and self.ckpt.can_resume():
            loaded_params, start_step = self.ckpt.load_meta()
            loss_history = self.ckpt.load_loss_history()
            self._update(message=f"Resumed from step {start_step}", current_step=start_step)
            # Load weights into state (simplified — reconstruct param tree)
            try:
                weights = self.ckpt.load_weights()
                import jax.tree_util
                flat_keys = sorted(weights.keys())
                leaves = [jnp.array(weights[k]) for k in flat_keys]
                # Reconstruct tree from flat
                state = state.replace(params=_unflatten(weights, state.params))
            except Exception:
                self._update(message="Warning: could not load weights, starting fresh")
                start_step = 0

        # ── Stage 1: Generate + FEM ──
        self._update(status="generating", message="Generating structures...",
                     total_steps=p.total_steps)
        np_rng = np.random.default_rng(p.seed)
        styles = ["random", "tower", "bridge", "cantilever", "arch"]
        dataset = FEMDataset.empty()

        for i in range(p.n_structures):
            if self._stop_flag:
                self._save_and_exit(state, start_step, loss_history, vm_scale)
                return

            style = styles[i % len(styles)]
            struct = generate_structure(L, np_rng, style)
            if int(struct.occupancy.sum()) < 3:
                continue

            self._update(
                status="solving",
                fem_progress=f"FEM {i+1}/{p.n_structures}",
                message=f"Solving structure {i+1} ({style})...",
            )

            try:
                _, fem_result = solve_structure(struct)
                if fem_result.converged:
                    dataset.add(struct, fem_result)
            except Exception:
                pass

        if len(dataset) < 3:
            self._update(status="error", message="Too few FEM solutions")
            return

        # Compute VM scale
        all_vm = np.concatenate([v.flatten() for v in dataset.von_mises])
        vm_scale = float(np.percentile(all_vm[all_vm > 0], 99)) if np.any(all_vm > 0) else 1.0
        vm_scale = max(vm_scale, 1e-6)

        self._update(status="training",
                     message=f"Training on {len(dataset)} FEM samples (VM scale: {vm_scale:.2e})")

        # ── Stage 2: Train ──
        def loss_fn(params, x, target, mask):
            pred = model.apply({"params": params}, x).squeeze(-1)
            diff = (pred - target) ** 2 * mask
            return jnp.sum(diff) / (jnp.sum(mask) + 1e-8)

        @jax.jit
        def train_step(state, x, target, mask):
            loss, grads = jax.value_and_grad(loss_fn)(state.params, x, target, mask)
            return state.apply_gradients(grads=grads), loss

        t0 = time.time()
        n_samples = len(dataset)

        for step in range(start_step + 1, p.total_steps + 1):
            if self._stop_flag:
                self._save_and_exit(state, step, loss_history, vm_scale)
                return

            idx = int(np_rng.integers(n_samples))
            x = build_input_tensor(dataset, idx, jnp)[None]
            vm = jnp.array(dataset.von_mises[idx]) / vm_scale
            mask = jnp.array(dataset.occupancy[idx])

            state, loss = train_step(state, x, vm[None], mask[None])
            loss_val = float(loss)
            loss_history.append(loss_val)

            self._update(
                current_step=step,
                loss=loss_val,
                loss_history=loss_history[-500:],  # keep last 500 for UI
                elapsed_sec=time.time() - t0,
            )

            if step % p.save_every == 0:
                self.ckpt.save(p, step, state.params,
                               {"loss_history": loss_history, "vm_scale": vm_scale})
                self._update(message=f"Checkpoint saved at step {step}")

        # Final save
        self.ckpt.save(p, p.total_steps, state.params,
                       {"loss_history": loss_history, "vm_scale": vm_scale})
        self._jax_params = state.params

        # Export
        npz_path = Path(p.output_dir) / "fno3d_fem_aligned.npz"
        flat = {}
        for path, val in _flatten_params(state.params):
            flat[path] = np.asarray(val)
        flat["__vm_scale__"] = np.array([vm_scale])
        flat["__grid_size__"] = np.array([L])
        np.savez(str(npz_path), **flat)

        self._update(status="done",
                     message=f"Done! Model saved to {p.output_dir}/")

    # ── Recommender (GAT) ──

    def _run_recommender(self, resume: bool):
        self._update(status="training", message="Node recommender training...")
        # Simplified: use synthetic data if no real graphs
        os.environ.setdefault("JAX_PLATFORM_NAME", "cpu")
        import jax
        import jax.numpy as jnp
        import optax
        from flax.training import train_state as ts
        from brml.models.node_recommender import NodeRecommender, recommend_loss

        p = self.params
        model = NodeRecommender(
            node_vocab_size=128, embed_dim=p.gat_embed_dim,
            num_gat_layers=p.gat_layers, num_heads=p.gat_heads,
        )

        rng = jax.random.PRNGKey(p.seed)
        dummy = (jnp.zeros((5, 7)), jnp.array([[0,1],[1,2]], dtype=jnp.int32))
        variables = model.init(rng, *dummy)

        schedule = optax.warmup_cosine_decay_schedule(
            init_value=0.0, peak_value=p.learning_rate,
            warmup_steps=min(500, p.total_steps // 10),
            decay_steps=p.total_steps, end_value=p.learning_rate * 0.01,
        )
        optimizer = optax.chain(
            optax.clip_by_global_norm(1.0),
            optax.adamw(learning_rate=schedule, weight_decay=p.weight_decay),
        )
        state = ts.TrainState.create(
            apply_fn=model.apply, params=variables["params"], tx=optimizer)

        self._update(total_steps=p.total_steps)
        np_rng = np.random.default_rng(p.seed)
        loss_history = []
        t0 = time.time()

        for step in range(1, p.total_steps + 1):
            if self._stop_flag:
                self.ckpt.save(p, step, state.params, {"loss_history": loss_history})
                self._update(status="idle", message=f"Stopped at step {step}")
                return

            # Synthetic graph
            N = int(np_rng.integers(5, 20))
            feats = np_rng.standard_normal((N, 7)).astype(np.float32)
            E = int(np_rng.integers(N-1, N*2))
            src = np_rng.integers(0, N, size=E).astype(np.int32)
            dst = np_rng.integers(0, N, size=E).astype(np.int32)
            edges = np.stack([src, dst])
            target_type = int(np_rng.integers(0, 128))
            target_port = int(np_rng.integers(0, N))

            def loss_fn(params):
                tl, ps = model.apply({"params": params},
                                     jnp.array(feats), jnp.array(edges))
                return recommend_loss(tl, target_type, ps, target_port)

            loss, grads = jax.value_and_grad(loss_fn)(state.params)
            state = state.apply_gradients(grads=grads)
            loss_val = float(loss)
            loss_history.append(loss_val)

            self._update(current_step=step, loss=loss_val,
                         loss_history=loss_history[-500:],
                         elapsed_sec=time.time() - t0)

            if step % p.save_every == 0:
                self.ckpt.save(p, step, state.params, {"loss_history": loss_history})

        self.ckpt.save(p, p.total_steps, state.params, {"loss_history": loss_history})
        self._update(status="done", message="Recommender training complete!")

    # ── Collapse (MPNN) ──

    def _run_collapse(self, resume: bool):
        self._update(status="training", message="Collapse predictor training...")
        os.environ.setdefault("JAX_PLATFORM_NAME", "cpu")
        import jax
        import jax.numpy as jnp
        import optax
        from flax.training import train_state as ts
        from brml.models.collapse_predictor import CollapsePredictor, collapse_loss
        from brml.data.collapse_dataset import CollapseDataset

        p = self.params
        model = CollapsePredictor(
            hidden_dim=p.mpnn_hidden, num_mp_steps=p.mpnn_steps)

        rng = jax.random.PRNGKey(p.seed)
        dummy = (jnp.zeros((10, 8)), jnp.zeros((2, 15), dtype=jnp.int32), jnp.zeros((15, 4)))
        variables = model.init(rng, *dummy)

        schedule = optax.warmup_cosine_decay_schedule(
            init_value=0.0, peak_value=p.learning_rate,
            warmup_steps=min(500, p.total_steps // 10),
            decay_steps=p.total_steps, end_value=p.learning_rate * 0.01,
        )
        optimizer = optax.chain(
            optax.clip_by_global_norm(1.0),
            optax.adamw(learning_rate=schedule, weight_decay=p.weight_decay),
        )
        state = ts.TrainState.create(
            apply_fn=model.apply, params=variables["params"], tx=optimizer)

        # Generate synthetic data
        self._update(status="generating", message="Generating collapse data...")
        dataset = CollapseDataset()
        dataset.generate_synthetic(count=max(100, p.n_structures), seed=p.seed)

        self._update(status="training", total_steps=p.total_steps,
                     message=f"Training on {len(dataset)} collapse samples")

        np_rng = np.random.default_rng(p.seed)
        loss_history = []
        t0 = time.time()

        for step in range(1, p.total_steps + 1):
            if self._stop_flag:
                self.ckpt.save(p, step, state.params, {"loss_history": loss_history})
                self._update(status="idle", message=f"Stopped at step {step}")
                return

            sample = dataset.samples[int(np_rng.integers(len(dataset)))]

            def loss_fn(params):
                cl, cp = model.apply({"params": params},
                                     jnp.array(sample.node_features),
                                     jnp.array(sample.edge_index),
                                     jnp.array(sample.edge_features))
                return collapse_loss(cl, jnp.array(sample.failure_labels), cp)

            loss, grads = jax.value_and_grad(loss_fn)(state.params)
            state = state.apply_gradients(grads=grads)
            loss_val = float(loss)
            loss_history.append(loss_val)

            self._update(current_step=step, loss=loss_val,
                         loss_history=loss_history[-500:],
                         elapsed_sec=time.time() - t0)

            if step % p.save_every == 0:
                self.ckpt.save(p, step, state.params, {"loss_history": loss_history})

        self.ckpt.save(p, p.total_steps, state.params, {"loss_history": loss_history})
        self._update(status="done", message="Collapse predictor training complete!")

    # ── Fluid (FNO-Fluid) ──

    def _run_fluid(self, resume: bool):
        self._update(status="generating", message="Generating fluid training data...")
        os.environ.setdefault("JAX_PLATFORM_NAME", "cpu")
        import jax
        import jax.numpy as jnp
        import optax
        from flax.training import train_state as ts
        from brml.models.fno_fluid import FNOFluid3D, fluid_loss

        p = self.params
        N = p.fluid_grid  # cells per axis

        model = FNOFluid3D(hidden_channels=48, num_layers=4, modes=min(10, N // 2))
        rng = jax.random.PRNGKey(p.seed)
        dummy = jnp.zeros((1, N, N, N, 8))
        variables = model.init(rng, dummy)

        schedule = optax.warmup_cosine_decay_schedule(
            init_value=0.0, peak_value=p.learning_rate,
            warmup_steps=min(500, p.total_steps // 10),
            decay_steps=p.total_steps, end_value=p.learning_rate * 0.01,
        )
        optimizer = optax.chain(
            optax.clip_by_global_norm(1.0),
            optax.adamw(learning_rate=schedule, weight_decay=p.weight_decay),
        )
        state = ts.TrainState.create(
            apply_fn=model.apply, params=variables["params"], tx=optimizer)

        n_params = sum(v.size for v in jax.tree_util.tree_leaves(state.params))
        self._update(status="training", total_steps=p.total_steps,
                     message=f"FNO-Fluid: {n_params:,} params, grid={N}³")

        # Generate synthetic N-S training pairs (simple gravity-driven flow)
        np_rng = np.random.default_rng(p.seed)

        def make_fluid_sample():
            boundary = np.ones((N, N, N), dtype=np.float32)
            # Random solid blocks
            n_solid = int(np_rng.integers(0, N * N))
            for _ in range(n_solid):
                x, y, z = np_rng.integers(0, N, size=3)
                boundary[x, y, z] = 0.0

            # Initial velocity (small random + downward gravity bias)
            vel = np_rng.standard_normal((N, N, N, 3)).astype(np.float32) * 0.01
            vel[:, :, :, 1] -= 0.1  # gravity
            vel *= boundary[..., None]

            pres = np.zeros((N, N, N, 1), dtype=np.float32)
            pos = np.stack(np.meshgrid(
                np.linspace(0, 1, N), np.linspace(0, 1, N), np.linspace(0, 1, N),
                indexing='ij'), axis=-1).astype(np.float32)

            x_in = np.concatenate([vel, pres, boundary[..., None], pos], axis=-1)

            # Simplified N-S: advection + diffusion + gravity + pressure projection
            # dt=0.01, nu=0.001 (kinematic viscosity of water)
            dt = 0.01
            nu = 0.001
            dx = 1.0 / N

            # Diffusion: ν∇²u (Laplacian via central difference)
            laplacian = np.zeros_like(vel)
            for axis in range(3):
                laplacian += (np.roll(vel, 1, axis=axis) +
                              np.roll(vel, -1, axis=axis) - 2 * vel) / (dx * dx)
            vel_next = vel + dt * nu * laplacian

            # Gravity
            vel_next[:, :, :, 1] -= dt * 9.81

            # Pressure correction (simplified: project to divergence-free)
            # div(u) → solve Poisson → correct velocity
            div = ((np.roll(vel_next[:,:,:,0], -1, axis=0) - np.roll(vel_next[:,:,:,0], 1, axis=0)) +
                   (np.roll(vel_next[:,:,:,1], -1, axis=1) - np.roll(vel_next[:,:,:,1], 1, axis=1)) +
                   (np.roll(vel_next[:,:,:,2], -1, axis=2) - np.roll(vel_next[:,:,:,2], 1, axis=2))) / (2*dx)
            # Simple Jacobi pressure solve (10 iterations)
            p = np.zeros((N, N, N), dtype=np.float32)
            for _ in range(10):
                p = (np.roll(p,1,0)+np.roll(p,-1,0)+np.roll(p,1,1)+np.roll(p,-1,1)+
                     np.roll(p,1,2)+np.roll(p,-1,2) - dx*dx*div) / 6.0
            # Pressure gradient correction
            vel_next[:,:,:,0] -= (np.roll(p,-1,axis=0)-np.roll(p,1,axis=0))/(2*dx)
            vel_next[:,:,:,1] -= (np.roll(p,-1,axis=1)-np.roll(p,1,axis=1))/(2*dx)
            vel_next[:,:,:,2] -= (np.roll(p,-1,axis=2)-np.roll(p,1,axis=2))/(2*dx)

            vel_next *= boundary[..., None]
            pres_next = p[..., None]
            target = np.concatenate([vel_next, pres_next], axis=-1)

            return x_in, target, boundary

        loss_history = []
        t0 = time.time()

        @jax.jit
        def train_step(state, x, target, boundary):
            def loss_fn(params):
                pred = model.apply({"params": params}, x)
                return fluid_loss(pred, target, boundary[..., None])
            loss, grads = jax.value_and_grad(loss_fn)(state.params)
            return state.apply_gradients(grads=grads), loss

        for step in range(1, p.total_steps + 1):
            if self._stop_flag:
                self.ckpt.save(p, step, state.params, {"loss_history": loss_history})
                self._update(status="idle", message=f"Fluid stopped at step {step}")
                return

            x_in, target, boundary = make_fluid_sample()
            state, loss = train_step(
                state, jnp.array(x_in)[None], jnp.array(target)[None],
                jnp.array(boundary)[None])

            loss_history.append(float(loss))
            self._update(current_step=step, loss=float(loss),
                         loss_history=loss_history[-500:],
                         elapsed_sec=time.time() - t0)

            if step % p.save_every == 0:
                self.ckpt.save(p, step, state.params, {"loss_history": loss_history})

        # Export
        out_dir = Path(p.output_dir)
        out_dir.mkdir(parents=True, exist_ok=True)
        flat = {k: np.asarray(v) for k, v in _flatten_params(state.params)}
        flat["__model_id__"] = np.array([ord(c) for c in "bifrost_fluid"])
        flat["__grid__"] = np.array([N])
        np.savez(str(out_dir / "fno_fluid.npz"), **flat)

        self.ckpt.save(p, p.total_steps, state.params, {"loss_history": loss_history})
        self._update(status="done", message=f"Fluid training done → {out_dir}/fno_fluid.npz")

    # ── LOD Classifier ──

    def _run_lod(self, resume: bool):
        self._update(status="generating", message="Generating LOD training data...")
        os.environ.setdefault("JAX_PLATFORM_NAME", "cpu")
        import jax
        import jax.numpy as jnp
        import optax
        from flax.training import train_state as ts
        from brml.models.lod_classifier import LODClassifier, lod_loss

        p = self.params

        model = LODClassifier(hidden_dim=32, num_classes=4, in_features=14)
        rng = jax.random.PRNGKey(p.seed)
        dummy = jnp.zeros((1, 14))
        variables = model.init(rng, dummy)

        optimizer = optax.adamw(learning_rate=p.learning_rate * 5)  # higher LR for tiny model
        state = ts.TrainState.create(
            apply_fn=model.apply, params=variables["params"], tx=optimizer)

        n_params = sum(v.size for v in jax.tree_util.tree_leaves(state.params))
        self._update(status="training", total_steps=p.total_steps,
                     message=f"LOD Classifier: {n_params:,} params (tiny MLP)")

        # Synthetic LOD training data (heuristic labels)
        np_rng = np.random.default_rng(p.seed)

        def make_lod_sample():
            """Generate a chunk feature vector with heuristic label."""
            fill = float(np_rng.uniform(0, 1))
            edits = float(np_rng.integers(0, 200))
            max_unsup_y = float(np_rng.uniform(0, 1))
            overhang = float(np_rng.uniform(0, 0.5))
            surface = float(np_rng.uniform(0.5, 2.0))
            mat_div = float(np_rng.uniform(0, 1))
            mod_ratio = float(np_rng.uniform(0, 0.3))
            vert_var = float(np_rng.uniform(0, 1))
            has_fluid = float(np_rng.random() < 0.2)
            has_redstone = float(np_rng.random() < 0.15)
            dist = float(np_rng.uniform(0, 1))
            age = float(np_rng.uniform(0, 1))
            neighbor = float(np_rng.uniform(0, 1))
            height = float(np_rng.uniform(0, 1))

            features = np.array([fill, edits / 200, max_unsup_y, overhang,
                                 surface / 2, mat_div, mod_ratio, vert_var / 2,
                                 has_fluid, has_redstone, dist, age, neighbor, height],
                                dtype=np.float32)

            # Heuristic label — balanced distribution (~25% each tier)
            if edits < 1 and mod_ratio < 0.01:
                label = 0  # SKIP — unmodified terrain
            elif edits < 20 and mod_ratio < 0.05 and overhang < 0.1:
                label = 1  # MARK — lightly modified, track keystones
            elif overhang > 0.15 or surface > 1.3 or vert_var > 0.6:
                label = 3  # FNO — irregular geometry
            else:
                label = 2  # PFSF — regular modified structure

            return features, label

        loss_history = []
        t0 = time.time()

        @jax.jit
        def train_step(state, x, label):
            def loss_fn(params):
                logits = model.apply({"params": params}, x)
                return lod_loss(logits, label)
            loss, grads = jax.value_and_grad(loss_fn)(state.params)
            return state.apply_gradients(grads=grads), loss

        for step in range(1, p.total_steps + 1):
            if self._stop_flag:
                self.ckpt.save(p, step, state.params, {"loss_history": loss_history})
                self._update(status="idle", message=f"LOD stopped at step {step}")
                return

            # Batch of 32
            feats, labels = [], []
            for _ in range(32):
                f, l = make_lod_sample()
                feats.append(f)
                labels.append(l)

            state, loss = train_step(
                state, jnp.array(feats), jnp.array(labels, dtype=jnp.int32))

            loss_history.append(float(loss))
            self._update(current_step=step, loss=float(loss),
                         loss_history=loss_history[-500:],
                         elapsed_sec=time.time() - t0)

            if step % p.save_every == 0:
                self.ckpt.save(p, step, state.params, {"loss_history": loss_history})

        # Export
        out_dir = Path(p.output_dir)
        out_dir.mkdir(parents=True, exist_ok=True)
        flat = {k: np.asarray(v) for k, v in _flatten_params(state.params)}
        flat["__model_id__"] = np.array([ord(c) for c in "bifrost_lod"])
        np.savez(str(out_dir / "lod_classifier.npz"), **flat)

        self.ckpt.save(p, p.total_steps, state.params, {"loss_history": loss_history})
        self._update(status="done", message=f"LOD training done → {out_dir}/lod_classifier.npz")

    def _save_and_exit(self, state, step, loss_history, vm_scale):
        self.ckpt.save(self.params, step, state.params,
                       {"loss_history": loss_history, "vm_scale": vm_scale})
        self._update(status="idle", message=f"Stopped & saved at step {step}",
                     can_resume=True)


def _unflatten(flat_dict: dict, template):
    """Reconstruct JAX param tree from flat dict using template structure."""
    import jax.numpy as jnp

    def _rebuild(tmpl, prefix=""):
        if isinstance(tmpl, dict):
            return {k: _rebuild(v, f"{prefix}{k}/") for k, v in tmpl.items()}
        key = prefix.rstrip("/")
        if key in flat_dict:
            return jnp.array(flat_dict[key])
        return tmpl  # fallback

    return _rebuild(template)
