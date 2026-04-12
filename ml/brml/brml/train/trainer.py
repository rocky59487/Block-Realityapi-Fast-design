"""
Generic JAX/Flax training loop with Optax optimizer.

Features:
  - Checkpoint saving/loading (orbax)
  - TensorBoard logging
  - Learning rate scheduling
  - Gradient clipping
"""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

import jax
import jax.numpy as jnp
import optax
from flax.training import train_state
from flax import linen as nn

TrainState = train_state.TrainState


@dataclass
class TrainConfig:
    """Training hyperparameters."""
    learning_rate: float = 1e-3
    weight_decay: float = 1e-4
    warmup_steps: int = 500
    total_steps: int = 50_000
    grad_clip: float = 1.0
    log_every: int = 100
    save_every: int = 5000
    checkpoint_dir: str = "checkpoints"
    seed: int = 42


class Trainer:
    """Generic JAX training loop."""

    def __init__(self, config: TrainConfig):
        self.config = config
        self.rng = jax.random.PRNGKey(config.seed)

    def create_state(self, model: nn.Module, dummy_input: Any) -> TrainState:
        """Initialize model parameters and optimizer."""
        self.rng, init_rng = jax.random.split(self.rng)
        variables = model.init(init_rng, *dummy_input)
        params = variables["params"]

        # Cosine schedule with warmup
        schedule = optax.warmup_cosine_decay_schedule(
            init_value=0.0,
            peak_value=self.config.learning_rate,
            warmup_steps=self.config.warmup_steps,
            decay_steps=self.config.total_steps,
            end_value=self.config.learning_rate * 0.01,
        )

        optimizer = optax.chain(
            optax.clip_by_global_norm(self.config.grad_clip),
            optax.adamw(learning_rate=schedule, weight_decay=self.config.weight_decay),
        )

        return TrainState.create(
            apply_fn=model.apply,
            params=params,
            tx=optimizer,
        )

    @staticmethod
    def train_step(state: TrainState, batch: Any,
                   loss_fn: Callable) -> tuple[TrainState, float]:
        """Single training step.

        Args:
            state: Current train state
            batch: Input data
            loss_fn: Callable(params, batch) → scalar loss
        Returns:
            (updated_state, loss_value)
        """
        def compute_loss(params):
            return loss_fn(params, batch)

        loss, grads = jax.value_and_grad(compute_loss)(state.params)
        state = state.apply_gradients(grads=grads)
        return state, loss

    @staticmethod
    def create_parallel_train_step(loss_fn: Callable) -> Callable:
        """Create a parallelized train step using pmap across available GPUs."""
        num_devices = jax.local_device_count()
        if num_devices <= 1:
            # Fall back to jit for single device
            @jax.jit
            def step(state: TrainState, batch: Any) -> tuple[TrainState, float]:
                return Trainer.train_step(state, batch, loss_fn)
            return step

        # Multiple devices: use pmap
        @jax.pmap(axis_name='batch')
        def pmap_step(state: TrainState, batch: Any) -> tuple[TrainState, float]:
            def compute_loss(params):
                return loss_fn(params, batch)

            loss, grads = jax.value_and_grad(compute_loss)(state.params)
            grads = jax.lax.pmean(grads, axis_name='batch')
            loss = jax.lax.pmean(loss, axis_name='batch')
            state = state.apply_gradients(grads=grads)
            return state, loss

        return pmap_step

    def save_checkpoint(self, state: TrainState, step: int, prefix: str = "model"):
        """Save checkpoint using orbax."""
        ckpt_dir = Path(self.config.checkpoint_dir)
        ckpt_dir.mkdir(parents=True, exist_ok=True)

        try:
            import orbax.checkpoint as ocp
            checkpointer = ocp.PyTreeCheckpointer()
            save_path = ckpt_dir / f"{prefix}_step{step}"
            checkpointer.save(str(save_path), state)
        except ImportError:
            # Fallback: save as numpy
            import numpy as np
            save_path = ckpt_dir / f"{prefix}_step{step}.npz"
            flat_params = jax.tree_util.tree_leaves(state.params)
            np.savez(str(save_path), *[np.asarray(p) for p in flat_params])

    def load_checkpoint(self, state: TrainState, step: int,
                        prefix: str = "model") -> TrainState:
        """Load checkpoint."""
        ckpt_dir = Path(self.config.checkpoint_dir)
        try:
            import orbax.checkpoint as ocp
            checkpointer = ocp.PyTreeCheckpointer()
            load_path = ckpt_dir / f"{prefix}_step{step}"
            restored = checkpointer.restore(str(load_path), item=state)
            return restored
        except (ImportError, FileNotFoundError):
            return state
