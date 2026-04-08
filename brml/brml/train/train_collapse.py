"""
Training script for structural collapse predictor (MPNN).

Usage:
  brml-train-collapse --data-dir ./data/collapse --steps 30000
  brml-train-collapse --synthetic 1000 --steps 30000
"""
from __future__ import annotations

import argparse
import time

import jax
import jax.numpy as jnp
import numpy as np

from brml.data.collapse_dataset import CollapseDataset
from brml.models.collapse_predictor import CollapsePredictor, collapse_loss
from brml.train.trainer import Trainer, TrainConfig


def make_loss_fn(model):
    """Create collapse prediction loss."""

    def loss_fn(params, batch):
        node_features, edge_index, edge_features, failure_labels = batch

        class_logits, collapse_prob = model.apply(
            {"params": params},
            node_features, edge_index, edge_features,
        )

        return collapse_loss(class_logits, failure_labels, collapse_prob)

    return loss_fn


def main():
    parser = argparse.ArgumentParser(description="Train collapse predictor (MPNN)")
    parser.add_argument("--data-dir", type=str, default=None, help="Collapse journal directory")
    parser.add_argument("--synthetic", type=int, default=500, help="Generate N synthetic samples")
    parser.add_argument("--hidden", type=int, default=128)
    parser.add_argument("--mp-steps", type=int, default=6)
    parser.add_argument("--lr", type=float, default=5e-4)
    parser.add_argument("--steps", type=int, default=30000)
    parser.add_argument("--checkpoint-dir", type=str, default="checkpoints/collapse")
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    # ── Data ──
    dataset = CollapseDataset()

    if args.data_dir:
        n = dataset.load_from_journal(args.data_dir)
        print(f"Loaded {n} samples from journal files")
    if len(dataset) == 0:
        n = dataset.generate_synthetic(count=args.synthetic, seed=args.seed)
        print(f"Generated {n} synthetic samples")

    print(f"Total samples: {len(dataset)}")

    # Compute class distribution
    all_labels = np.concatenate([s.failure_labels for s in dataset.samples])
    for c in range(5):
        pct = 100.0 * np.sum(all_labels == c) / len(all_labels)
        names = ["OK", "cantilever", "crushing", "no_support", "tension"]
        print(f"  class {c} ({names[c]}): {pct:.1f}%")

    # ── Model ──
    model = CollapsePredictor(
        hidden_dim=args.hidden,
        num_mp_steps=args.mp_steps,
        num_classes=5,
        node_feat_dim=8,
        edge_feat_dim=4,
    )

    # ── Trainer ──
    config = TrainConfig(
        learning_rate=args.lr,
        total_steps=args.steps,
        checkpoint_dir=args.checkpoint_dir,
        seed=args.seed,
    )
    trainer = Trainer(config)

    # Dummy input
    dummy = (
        jnp.zeros((10, 8)),         # node_features
        jnp.zeros((2, 15), dtype=jnp.int32),  # edge_index
        jnp.zeros((15, 4)),         # edge_features
    )
    state = trainer.create_state(model, dummy)
    loss_fn = make_loss_fn(model)

    n_params = sum(p.size for p in jax.tree_util.tree_leaves(state.params))
    print(f"Model params: {n_params:,}")

    jit_step = jax.jit(lambda s, b: Trainer.train_step(s, b, loss_fn))

    # ── Training loop ──
    rng = np.random.default_rng(args.seed)
    t0 = time.time()

    for step in range(1, args.steps + 1):
        sample = dataset.samples[rng.integers(len(dataset))]

        batch = (
            jnp.array(sample.node_features),
            jnp.array(sample.edge_index),
            jnp.array(sample.edge_features),
            jnp.array(sample.failure_labels),
        )

        state, loss = jit_step(state, batch)

        if step % config.log_every == 0:
            elapsed = time.time() - t0
            print(f"[step {step:6d}] loss={float(loss):.4f}  ({elapsed:.1f}s)")

        if step % config.save_every == 0:
            trainer.save_checkpoint(state, step, prefix="collapse")

    trainer.save_checkpoint(state, step, prefix="collapse_final")
    print(f"Training complete.")


if __name__ == "__main__":
    main()
