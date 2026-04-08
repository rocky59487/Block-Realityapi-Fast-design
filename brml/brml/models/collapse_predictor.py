"""
Structural collapse predictor — Message Passing Neural Network (MPNN).

Given a structure graph (nodes = blocks, edges = adjacency), predicts:
  1. Per-node failure type (5-class: OK, cantilever, crushing, no_support, tension)
  2. Per-node collapse probability (regression)

Architecture:
  - Input embedding: node features → hidden
  - K rounds of message passing (aggregate neighbor messages)
  - Node classifier: MLP → 5-class softmax
  - Collapse regressor: MLP → sigmoid probability

Uses jraph-compatible graph representation.
"""
from __future__ import annotations

import jax
import jax.numpy as jnp
import flax.linen as nn


class MessagePassingLayer(nn.Module):
    """Single round of message passing on a graph.

    message_ij = MLP([h_i || h_j || e_ij])
    m_i = sum_j(message_ij)
    h_i' = GRU(h_i, m_i)
    """

    hidden_dim: int
    edge_feat_dim: int = 4

    @nn.compact
    def __call__(self, node_feats: jnp.ndarray, edge_index: jnp.ndarray,
                 edge_feats: jnp.ndarray) -> jnp.ndarray:
        """
        Args:
            node_feats: [N, H]
            edge_index: [2, E]
            edge_feats: [E, D_e]
        Returns:
            updated: [N, H]
        """
        N, H = node_feats.shape
        E = edge_index.shape[1]

        src_idx = edge_index[0]
        dst_idx = edge_index[1]

        # Gather source and destination features
        h_src = node_feats[src_idx]  # [E, H]
        h_dst = node_feats[dst_idx]  # [E, H]

        # Message function: MLP on concatenated features
        msg_input = jnp.concatenate([h_src, h_dst, edge_feats], axis=-1)  # [E, 2H+D_e]
        messages = nn.Dense(H)(msg_input)
        messages = nn.relu(messages)
        messages = nn.Dense(H)(messages)  # [E, H]

        # Aggregate: scatter-add to destination nodes
        # Use jax.ops.segment_sum for much faster GPU execution than agg.at[dst_idx].add
        agg = jax.ops.segment_sum(messages, dst_idx, num_segments=N)  # [N, H]

        # Update: GRU-style gating
        z = jax.nn.sigmoid(nn.Dense(H)(jnp.concatenate([node_feats, agg], axis=-1)))
        r = jax.nn.sigmoid(nn.Dense(H)(jnp.concatenate([node_feats, agg], axis=-1)))
        h_tilde = jnp.tanh(nn.Dense(H)(jnp.concatenate([r * node_feats, agg], axis=-1)))
        updated = (1 - z) * node_feats + z * h_tilde

        return updated


class CollapsePredictor(nn.Module):
    """MPNN-based structural collapse predictor.

    Args:
        hidden_dim: Hidden dimension for message passing.
        num_mp_steps: Number of message passing rounds.
        num_classes: Number of failure types (5: OK + 4 failures).
        node_feat_dim: Input node feature dimension.
        edge_feat_dim: Input edge feature dimension.
    """

    hidden_dim: int = 128
    num_mp_steps: int = 6
    num_classes: int = 5
    node_feat_dim: int = 8
    edge_feat_dim: int = 4

    @nn.compact
    def __call__(self, node_features: jnp.ndarray, edge_index: jnp.ndarray,
                 edge_features: jnp.ndarray) -> tuple[jnp.ndarray, jnp.ndarray]:
        """
        Args:
            node_features: [N, F_node]
            edge_index:    [2, E]
            edge_features: [E, F_edge]
        Returns:
            class_logits: [N, 5] — failure type logits
            collapse_prob: [N] — collapse probability (0-1)
        """
        # ── Input embedding ──
        h = nn.Dense(self.hidden_dim)(node_features)
        h = nn.relu(h)

        # ── Message passing rounds ──
        for _ in range(self.num_mp_steps):
            h_new = MessagePassingLayer(
                hidden_dim=self.hidden_dim,
                edge_feat_dim=self.edge_feat_dim,
            )(h, edge_index, edge_features)
            h = nn.LayerNorm()(h + h_new)  # residual + norm

        # ── Node classifier: failure type ──
        cls_h = nn.Dense(64)(h)
        cls_h = nn.relu(cls_h)
        class_logits = nn.Dense(self.num_classes)(cls_h)  # [N, 5]

        # ── Collapse regressor ──
        reg_h = nn.Dense(64)(h)
        reg_h = nn.relu(reg_h)
        collapse_prob = jax.nn.sigmoid(nn.Dense(1)(reg_h).squeeze(-1))  # [N]

        return class_logits, collapse_prob


def collapse_loss(class_logits: jnp.ndarray, failure_labels: jnp.ndarray,
                  collapse_prob: jnp.ndarray) -> jnp.ndarray:
    """Combined loss for collapse prediction.

    Args:
        class_logits:  [N, 5] unnormalized scores
        failure_labels: [N] ground truth failure type (0-4)
        collapse_prob: [N] predicted probability
    Returns:
        Scalar loss
    """
    N = class_logits.shape[0]

    # Cross-entropy for failure classification
    one_hot = jax.nn.one_hot(failure_labels, 5)
    ce_loss = -jnp.sum(one_hot * jax.nn.log_softmax(class_logits), axis=-1)
    cls_loss = jnp.mean(ce_loss)

    # Binary cross-entropy for collapse probability (numerically stable)
    did_collapse = (failure_labels > 0).astype(jnp.float32)
    p = jnp.clip(collapse_prob, 1e-7, 1.0 - 1e-7)
    bce_loss = -jnp.mean(
        did_collapse * jnp.log(p) + (1 - did_collapse) * jnp.log(1 - p)
    )

    return cls_loss + 0.5 * bce_loss
