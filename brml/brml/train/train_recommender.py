"""
Training script for node graph recommender (GAT).

Uses leave-one-out training: for each graph, remove one node and
predict its type + connection from the remaining subgraph.

Usage:
  brml-train-recommender --data-dir ./data/graphs --steps 20000
"""
from __future__ import annotations

import argparse
import time

import jax
import jax.numpy as jnp
import numpy as np

from brml.data.node_graph_loader import NodeGraphLoader
from brml.models.node_recommender import NodeRecommender, recommend_loss
from brml.train.trainer import Trainer, TrainConfig


def make_loss_fn(model, vocab_size: int):
    """Create leave-one-out loss for recommender."""

    def loss_fn(params, batch):
        node_features, edge_index, removed_type_id, removed_neighbor_idx = batch

        type_logits, port_scores = model.apply(
            {"params": params},
            node_features, edge_index,
            training=True,
        )

        return recommend_loss(type_logits, removed_type_id,
                              port_scores, removed_neighbor_idx)

    return loss_fn


def leave_one_out(sample, rng):
    """Remove one node from graph and create training target.

    Returns: (subgraph_features, subgraph_edges, removed_type_id, neighbor_idx)
    """
    N = sample.num_nodes
    if N < 3:
        return None

    # Pick random node to remove (not the first/last for stability)
    remove_idx = int(rng.integers(1, N))
    type_id = int(sample.node_type_ids[remove_idx])

    # Find a neighbor of the removed node
    edge_src = sample.edge_index[0]
    edge_dst = sample.edge_index[1]
    neighbor_idx = -1
    for i in range(len(edge_src)):
        if edge_src[i] == remove_idx and edge_dst[i] != remove_idx:
            neighbor_idx = int(edge_dst[i])
            break
        if edge_dst[i] == remove_idx and edge_src[i] != remove_idx:
            neighbor_idx = int(edge_src[i])
            break
    if neighbor_idx < 0:
        neighbor_idx = 0

    # Build subgraph (remove node and remap indices)
    keep = [i for i in range(N) if i != remove_idx]
    remap = {old: new for new, old in enumerate(keep)}

    sub_features = sample.node_features[keep]

    new_src, new_dst = [], []
    for i in range(sample.edge_index.shape[1]):
        s, d = int(edge_src[i]), int(edge_dst[i])
        if s != remove_idx and d != remove_idx:
            new_src.append(remap[s])
            new_dst.append(remap[d])

    sub_edges = np.array([new_src, new_dst], dtype=np.int32) if new_src else \
        np.zeros((2, 0), dtype=np.int32)

    # Remap neighbor index
    remapped_neighbor = remap.get(neighbor_idx, 0)

    return sub_features, sub_edges, type_id, remapped_neighbor


def main():
    parser = argparse.ArgumentParser(description="Train node graph recommender")
    parser.add_argument("--data-dir", type=str, required=True, help="NodeGraph JSON directory")
    parser.add_argument("--batch-size", type=int, default=1, help="Batch size (graphs/step)")
    parser.add_argument("--embed-dim", type=int, default=64)
    parser.add_argument("--gat-layers", type=int, default=3)
    parser.add_argument("--lr", type=float, default=5e-4)
    parser.add_argument("--steps", type=int, default=20000)
    parser.add_argument("--checkpoint-dir", type=str, default="checkpoints/recommender")
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    # ── Data ──
    loader = NodeGraphLoader(args.data_dir)
    files = loader.list_files()
    vocab = loader.build_vocabulary(files)
    print(f"Found {len(files)} graph files, {len(vocab)} node types")

    samples = []
    for f in files:
        s = loader.load(f)
        if s and s.num_nodes >= 3:
            samples.append(s)
    print(f"Loaded {len(samples)} valid graphs")

    if not samples:
        print("No valid graphs found. Exiting.")
        return

    vocab_size = len(vocab) + 1  # +1 for unknown

    # ── Model ──
    model = NodeRecommender(
        node_vocab_size=vocab_size,
        embed_dim=args.embed_dim,
        num_gat_layers=args.gat_layers,
    )

    # ── Trainer ──
    config = TrainConfig(
        learning_rate=args.lr,
        total_steps=args.steps,
        checkpoint_dir=args.checkpoint_dir,
        seed=args.seed,
    )
    trainer = Trainer(config)

    # Dummy input for init
    dummy_feats = jnp.zeros((3, 7))
    dummy_edges = jnp.array([[0, 1], [1, 2]], dtype=jnp.int32)
    dummy = (dummy_feats, dummy_edges)
    state = trainer.create_state(model, dummy)
    loss_fn = make_loss_fn(model, vocab_size)

    n_params = sum(p.size for p in jax.tree_util.tree_leaves(state.params))
    print(f"Model params: {n_params:,}")

    jit_step = jax.jit(lambda s, b: Trainer.train_step(s, b, loss_fn))

    # ── Training loop ──
    rng = np.random.default_rng(args.seed)
    t0 = time.time()

    for step in range(1, args.steps + 1):
        sample = samples[rng.integers(len(samples))]
        result = leave_one_out(sample, rng)
        if result is None:
            continue

        sub_feats, sub_edges, type_id, neighbor_idx = result

        batch = (
            jnp.array(sub_feats),
            jnp.array(sub_edges),
            type_id,
            neighbor_idx,
        )

        state, loss = jit_step(state, batch)

        if step % config.log_every == 0:
            elapsed = time.time() - t0
            print(f"[step {step:6d}] loss={float(loss):.4f}  "
                  f"({elapsed:.1f}s)")

        if step % config.save_every == 0:
            trainer.save_checkpoint(state, step, prefix="recommender")

    trainer.save_checkpoint(state, step, prefix="recommender_final")
    print(f"Training complete.")


if __name__ == "__main__":
    main()
