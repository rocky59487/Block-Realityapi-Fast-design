"""
Concurrent Pipeline — produce PFSF data + train ML simultaneously.

Architecture:
  Producer thread: generates structures → PFSF/FEM solve → pushes to queue
  Consumer thread: pops from queue → trains model → checkpoints

Training starts after MIN_SAMPLES (20), does NOT wait for all data.
New samples are fed in as they arrive — true online learning.

GPU support: auto-detects JAX GPU backend.
"""
from __future__ import annotations

import os
import time
import threading
import queue
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

import numpy as np


@dataclass
class Sample:
    """Single training sample: input features + target φ."""
    input_6ch: np.ndarray   # [L, L, L, 6] — occ, E, nu, rho, rcomp, rtens (normalized)
    target: np.ndarray       # [L, L, L]   — converged φ (PFSF) or vm (FEM)
    mask: np.ndarray         # [L, L, L]   — occupancy mask
    model_type: str          # "surrogate" | "fluid" | "collapse"


@dataclass
class PipelineConfig:
    grid_size: int = 12
    n_structures: int = 200
    train_steps: int = 10000
    batch_size: int = 4
    learning_rate: float = 1e-3
    hidden: int = 48
    layers: int = 4
    modes: int = 6
    min_samples: int = 20       # start training after this many
    save_every: int = 2000
    output_dir: str = "brml_output"
    seed: int = 42
    models: list = field(default_factory=lambda: ["surrogate"])


@dataclass
class PipelineStatus:
    phase: str = "idle"          # idle | producing | training | done | error
    produced: int = 0
    trained_steps: int = 0
    total_steps: int = 0
    loss: float = 0.0
    loss_history: list = field(default_factory=list)
    samples_queued: int = 0
    gpu_detected: bool = False
    message: str = ""


class ConcurrentPipeline:
    """Producer-consumer pipeline: data generation ‖ model training."""

    def __init__(self, config: PipelineConfig,
                 on_status: Callable[[PipelineStatus], None] | None = None):
        self.cfg = config
        self.on_status = on_status or (lambda s: None)
        self.status = PipelineStatus(total_steps=config.train_steps)
        self._stop = False
        self._queue: queue.Queue[Sample] = queue.Queue(maxsize=500)
        self._buffer: list[Sample] = []  # accumulated training data
        self._lock = threading.Lock()

    def run(self):
        """Run the full pipeline (blocking). Call from thread if needed."""
        self._stop = False
        self._detect_gpu()

        producer = threading.Thread(target=self._produce, daemon=True)
        producer.start()

        # Wait for minimum samples before training
        self._update(phase="producing", message=f"Generating data (need {self.cfg.min_samples})...")
        while len(self._buffer) < self.cfg.min_samples and not self._stop:
            self._drain_queue()
            time.sleep(0.2)

        if self._stop:
            return

        # Start training (producer continues in background)
        self._train_all()

        self._stop = True
        producer.join(timeout=10)
        self._update(phase="done", message="Pipeline complete!")

    def stop(self):
        self._stop = True

    def _detect_gpu(self):
        try:
            os.environ.setdefault("JAX_PLATFORM_NAME", "gpu")
            import jax
            devices = jax.devices()
            gpu = any(d.platform == "gpu" for d in devices)
            self.status.gpu_detected = gpu
            if gpu:
                self._update(message=f"GPU detected: {devices[0]}")
            else:
                os.environ["JAX_PLATFORM_NAME"] = "cpu"
                self._update(message="No GPU, using CPU")
        except Exception:
            os.environ["JAX_PLATFORM_NAME"] = "cpu"

    def _drain_queue(self):
        """Move samples from queue to buffer."""
        while not self._queue.empty():
            try:
                sample = self._queue.get_nowait()
                with self._lock:
                    self._buffer.append(sample)
                self.status.samples_queued = len(self._buffer)
            except queue.Empty:
                break

    def _update(self, **kw):
        for k, v in kw.items():
            setattr(self.status, k, v)
        self.on_status(self.status)

    # ═══ Producer ═══

    def _produce(self):
        """Generate PFSF training data in background."""
        from brml.pipeline.auto_train import generate_structure, solve_structure, FEMDataset

        rng = np.random.default_rng(self.cfg.seed)
        L = self.cfg.grid_size
        styles = ["bridge", "cantilever", "arch", "spiral", "tree",
                  "cave", "overhang", "random", "tower"]

        for i in range(self.cfg.n_structures):
            if self._stop:
                break

            style = styles[rng.integers(len(styles))]
            struct = generate_structure(L, rng, style)
            if int(struct.occupancy.sum()) < 3:
                continue

            try:
                _, fem = solve_structure(struct)
                if not fem.converged:
                    continue

                # Build 6ch input (matches Java OnnxPFSFRuntime normalization)
                occ = struct.occupancy.astype(np.float32)
                E_n = struct.E_field.astype(np.float32) / 200e9
                nu = struct.nu_field.astype(np.float32)
                rho_n = struct.density_field.astype(np.float32) / 7850.0
                rc_n = struct.rcomp_field.astype(np.float32) / 250.0
                rt_n = struct.rtens_field.astype(np.float32) / 500.0  # steel rtens
                inp = np.stack([occ, E_n, nu, rho_n, rc_n, rt_n], axis=-1)

                self._queue.put(Sample(
                    input_6ch=inp,
                    target=fem.von_mises,
                    mask=occ,
                    model_type="surrogate",
                ))
                self.status.produced += 1
                self._update(phase="producing",
                             message=f"Produced {self.status.produced}/{self.cfg.n_structures} ({style})")
            except Exception:
                pass

    # ═══ Consumer (Training) ═══

    def _train_all(self):
        """Train all selected models."""
        for model_name in self.cfg.models:
            if self._stop:
                break
            self._update(phase="training", message=f"Training {model_name}...")
            if model_name == "surrogate":
                self._train_surrogate()
            elif model_name == "fluid":
                self._train_fluid()
            elif model_name == "collapse":
                self._train_collapse()

    def _train_surrogate(self):
        import jax
        import jax.numpy as jnp
        import optax
        from flax.training import train_state
        from brml.models.unified import PFSFSurrogate, surrogate_loss

        cfg = self.cfg
        L = cfg.grid_size
        model = PFSFSurrogate(hidden=cfg.hidden, layers=cfg.layers, modes=cfg.modes)

        rng = jax.random.PRNGKey(cfg.seed)
        variables = model.init(rng, jnp.zeros((1, L, L, L, 6)))
        opt = optax.chain(
            optax.clip_by_global_norm(1.0),
            optax.adamw(learning_rate=cfg.learning_rate, weight_decay=1e-4),
        )
        state = train_state.TrainState.create(
            apply_fn=model.apply, params=variables["params"], tx=opt)

        n_params = sum(p.size for p in jax.tree_util.tree_leaves(state.params))
        self._update(message=f"PFSFSurrogate: {n_params:,} params, GPU={self.status.gpu_detected}")

        # Compute target scale from buffer
        with self._lock:
            all_targets = [s.target for s in self._buffer if s.model_type == "surrogate"]
        if not all_targets:
            self._update(message="No surrogate samples, skipping")
            return
        all_vm = np.concatenate([t.flatten() for t in all_targets])
        vm_scale = float(np.percentile(all_vm[all_vm > 0], 99)) if np.any(all_vm > 0) else 1.0
        vm_scale = max(vm_scale, 1e-6)

        @jax.jit
        def step(state, x, target, mask):
            def loss_fn(params):
                pred = model.apply({"params": params}, x)
                return surrogate_loss(pred, target, mask)
            loss, grads = jax.value_and_grad(loss_fn)(state.params)
            return state.apply_gradients(grads=grads), loss

        np_rng = np.random.default_rng(cfg.seed + 1)
        t0 = time.time()
        out_dir = Path(cfg.output_dir) / "surrogate"
        out_dir.mkdir(parents=True, exist_ok=True)

        for s in range(1, cfg.train_steps + 1):
            if self._stop:
                break

            # Drain new samples from producer
            self._drain_queue()

            with self._lock:
                surr_samples = [s for s in self._buffer if s.model_type == "surrogate"]
            if not surr_samples:
                time.sleep(0.1)
                continue

            # Random sample from buffer
            idx = int(np_rng.integers(len(surr_samples)))
            sample = surr_samples[idx]

            x = jnp.array(sample.input_6ch)[None]
            t = jnp.array(sample.target / vm_scale)[None]
            m = jnp.array(sample.mask)[None]

            state, loss = step(state, x, t, m)

            self.status.trained_steps = s
            self.status.loss = float(loss)
            self.status.loss_history.append(float(loss))
            if s % 50 == 0:
                self._update()

            if s % cfg.save_every == 0:
                self._save(state.params, out_dir, "surrogate", s, vm_scale)

        self._save(state.params, out_dir, "surrogate", cfg.train_steps, vm_scale)

    def _train_fluid(self):
        import jax
        import jax.numpy as jnp
        import optax
        from flax.training import train_state
        from brml.models.unified import FluidSurrogate, fluid_loss

        cfg = self.cfg
        N = 16  # fluid grid
        model = FluidSurrogate(hidden=48, layers=4, modes=min(8, N // 2))

        rng = jax.random.PRNGKey(cfg.seed)
        variables = model.init(rng, jnp.zeros((1, N, N, N, 8)))
        opt = optax.adamw(learning_rate=cfg.learning_rate)
        state = train_state.TrainState.create(
            apply_fn=model.apply, params=variables["params"], tx=opt)

        @jax.jit
        def step(state, x, target, boundary):
            def loss_fn(params):
                pred = model.apply({"params": params}, x)
                return fluid_loss(pred, target, boundary)
            loss, grads = jax.value_and_grad(loss_fn)(state.params)
            return state.apply_gradients(grads=grads), loss

        np_rng = np.random.default_rng(cfg.seed)
        out_dir = Path(cfg.output_dir) / "fluid"
        out_dir.mkdir(parents=True, exist_ok=True)

        for s in range(1, cfg.train_steps + 1):
            if self._stop:
                break

            # Synthetic N-S sample
            boundary = np.ones((N, N, N), dtype=np.float32)
            for _ in range(int(np_rng.integers(0, N * 2))):
                boundary[tuple(np_rng.integers(0, N, 3))] = 0.0
            vel = np_rng.standard_normal((N, N, N, 3)).astype(np.float32) * 0.01
            vel[:, :, :, 1] -= 0.1
            vel *= boundary[..., None]
            pres = np.zeros((N, N, N, 1), dtype=np.float32)
            pos = np.stack(np.meshgrid(
                np.linspace(0,1,N), np.linspace(0,1,N), np.linspace(0,1,N), indexing='ij'),
                axis=-1).astype(np.float32)
            x_in = np.concatenate([vel, pres, boundary[..., None], pos], axis=-1)

            # Simple N-S step (diffusion + gravity + pressure projection)
            dt, nu_visc, dx = 0.01, 0.001, 1.0 / N
            lap = sum((np.roll(vel,1,ax)+np.roll(vel,-1,ax)-2*vel)/(dx*dx) for ax in range(3))
            vn = vel + dt * nu_visc * lap
            vn[:,:,:,1] -= dt * 9.81
            div = sum((np.roll(vn[:,:,:,c],-1,c)-np.roll(vn[:,:,:,c],1,c))/(2*dx) for c in range(3))
            p = np.zeros((N,N,N), dtype=np.float32)
            for _ in range(10):
                p = (sum(np.roll(p,d,ax) for ax in range(3) for d in [-1,1]) - dx*dx*div) / 6
            for c in range(3):
                vn[:,:,:,c] -= (np.roll(p,-1,c)-np.roll(p,1,c))/(2*dx)
            vn *= boundary[..., None]
            target = np.concatenate([vn, p[...,None]], axis=-1)

            state, loss = step(state, jnp.array(x_in)[None], jnp.array(target)[None],
                               jnp.array(boundary)[None,...,None])
            self.status.trained_steps = s
            self.status.loss = float(loss)
            if s % 50 == 0:
                self._update(message=f"Fluid step {s}/{cfg.train_steps} loss={float(loss):.4f}")

        self._save(state.params, out_dir, "fluid", cfg.train_steps, 1.0)

    def _train_collapse(self):
        import jax
        import jax.numpy as jnp
        import optax
        from flax.training import train_state
        from brml.models.unified import CollapsePredictor, collapse_loss
        from brml.data.collapse_dataset import CollapseDataset

        cfg = self.cfg
        model = CollapsePredictor(hidden=cfg.hidden, mp_steps=6)

        rng = jax.random.PRNGKey(cfg.seed)
        variables = model.init(rng, jnp.zeros((10,8)), jnp.zeros((2,15),dtype=jnp.int32), jnp.zeros((15,4)))
        opt = optax.adamw(learning_rate=cfg.learning_rate)
        state = train_state.TrainState.create(
            apply_fn=model.apply, params=variables["params"], tx=opt)

        dataset = CollapseDataset()
        dataset.generate_synthetic(count=max(100, cfg.n_structures), seed=cfg.seed)
        np_rng = np.random.default_rng(cfg.seed)
        out_dir = Path(cfg.output_dir) / "collapse"
        out_dir.mkdir(parents=True, exist_ok=True)

        @jax.jit
        def step(state, nf, ei, ef, labels):
            def loss_fn(params):
                cl, cp = model.apply({"params": params}, nf, ei, ef)
                return collapse_loss(cl, labels, cp)
            loss, grads = jax.value_and_grad(loss_fn)(state.params)
            return state.apply_gradients(grads=grads), loss

        for s in range(1, cfg.train_steps + 1):
            if self._stop:
                break
            sample = dataset.samples[int(np_rng.integers(len(dataset)))]
            state, loss = step(state, jnp.array(sample.node_features),
                               jnp.array(sample.edge_index), jnp.array(sample.edge_features),
                               jnp.array(sample.failure_labels))
            self.status.trained_steps = s
            self.status.loss = float(loss)
            if s % 50 == 0:
                self._update(message=f"Collapse step {s}/{cfg.train_steps} loss={float(loss):.4f}")

        self._save(state.params, out_dir, "collapse", cfg.train_steps, 1.0)

    def _save(self, params, out_dir, name, step, scale):
        flat = {}
        for path, val in _flatten(params):
            flat[path] = np.asarray(val)
        flat["__scale__"] = np.array([scale])
        flat["__step__"] = np.array([step])
        np.savez(str(out_dir / f"{name}_step{step}.npz"), **flat)
        self._update(message=f"Saved {name} checkpoint at step {step}")


def _flatten(params, prefix=""):
    result = []
    if isinstance(params, dict):
        for k, v in params.items():
            result.extend(_flatten(v, f"{prefix}{k}/"))
    else:
        result.append((prefix.rstrip("/"), params))
    return result
