"""
Training script for PFSF physics surrogate (FNO3D).

Usage:
  brml-train-surrogate --data-dir ./data/physics --grid-size 16 --steps 50000
  brml-train-surrogate --synthetic 2000 --grid-size 16
"""
from __future__ import annotations

import argparse
import time

import jax
import jax.numpy as jnp

from brml.data.physics_dataset import PhysicsDataset
from brml.models.pfsf_surrogate import FNO3D, prepare_input
from brml.train.trainer import Trainer, TrainConfig


def make_loss_fn(model):
    """Create loss function for FNO surrogate."""

    def loss_fn(params, batch):
        source, conductivity, voxel_type, rcomp, phi_target = batch

        x = prepare_input(source, conductivity, voxel_type, rcomp)
        phi_pred = model.apply({"params": params}, x)  # [B, Lx, Ly, Lz, 1]
        phi_pred = phi_pred.squeeze(-1)  # [B, Lx, Ly, Lz]

        # Masked MSE (only on solid/anchor voxels)
        mask = (voxel_type > 0).astype(jnp.float32)
        diff = (phi_pred - phi_target) ** 2 * mask
        loss = jnp.sum(diff) / (jnp.sum(mask) + 1e-8)

        return loss

    return loss_fn


def main():
    parser = argparse.ArgumentParser(description="Train PFSF surrogate (FNO3D)")
    parser.add_argument("--data-dir", type=str, default=None, help="Binary data directory")
    parser.add_argument("--blueprint-dir", type=str, default=None, help="Blueprint directory")
    parser.add_argument("--synthetic", type=int, default=1000, help="Generate N synthetic samples")
    parser.add_argument("--grid-size", type=int, default=16)
    parser.add_argument("--batch-size", type=int, default=8)
    parser.add_argument("--hidden", type=int, default=64)
    parser.add_argument("--layers", type=int, default=4)
    parser.add_argument("--modes", type=int, default=8)
    parser.add_argument("--lr", type=float, default=1e-3)
    parser.add_argument("--steps", type=int, default=50000)
    parser.add_argument("--checkpoint-dir", type=str, default="checkpoints/surrogate")
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    # ── Data ──
    dataset = PhysicsDataset(max_grid=args.grid_size)

    if args.data_dir:
        n = dataset.load_from_binary(args.data_dir)
        print(f"Loaded {n} samples from binary")
    if args.blueprint_dir:
        n = dataset.load_from_blueprints(args.blueprint_dir)
        print(f"Loaded {n} samples from blueprints")
    if len(dataset) == 0:
        n = dataset.generate_synthetic(count=args.synthetic, grid_size=args.grid_size,
                                        seed=args.seed)
        print(f"Generated {n} synthetic samples")

    print(f"Total samples: {len(dataset)}")

    # ── Model ──
    model = FNO3D(
        hidden_channels=args.hidden,
        num_layers=args.layers,
        modes=args.modes,
        in_channels=9,
    )

    # ── Trainer ──
    config = TrainConfig(
        learning_rate=args.lr,
        total_steps=args.steps,
        checkpoint_dir=args.checkpoint_dir,
        seed=args.seed,
    )
    trainer = Trainer(config)

    L = args.grid_size
    dummy = (jnp.zeros((1, L, L, L, 9)),)
    state = trainer.create_state(model, dummy)
    loss_fn = make_loss_fn(model)

    n_params = sum(p.size for p in jax.tree_util.tree_leaves(state.params))
    print(f"Model params: {n_params:,}")

    # ── JIT compile ──
    jit_step = jax.jit(lambda s, b: Trainer.train_step(s, b, loss_fn))

    # ── Training loop ──
    step = 0
    t0 = time.time()

    while step < args.steps:
        for batch in dataset.batches(args.batch_size, pad_to=L, seed=step):
            if step >= args.steps:
                break

            jax_batch = (
                jnp.array(batch.source),
                jnp.array(batch.conductivity),
                jnp.array(batch.voxel_type),
                jnp.array(batch.rcomp),
                jnp.array(batch.phi_target),
            )
            state, loss = jit_step(state, jax_batch)
            step += 1

            if step % config.log_every == 0:
                elapsed = time.time() - t0
                print(f"[step {step:6d}] loss={float(loss):.6f}  "
                      f"({elapsed:.1f}s, {step / elapsed:.0f} steps/s)")

            if step % config.save_every == 0:
                trainer.save_checkpoint(state, step, prefix="fno3d")
                print(f"  → checkpoint saved at step {step}")

    trainer.save_checkpoint(state, step, prefix="fno3d_final")
    print(f"Training complete. Final loss: {float(loss):.6f}")


if __name__ == "__main__":
    main()
