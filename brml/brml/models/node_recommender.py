"""
Node graph recommendation model — Graph Attention Network + autoregressive decoder.

Given a partial node graph, predicts the next node type to add and which
existing port to connect it to.

Architecture:
  1. GAT encoder: embed existing nodes via multi-head attention over edges
  2. Graph-level readout: mean pooling → graph embedding
  3. Node type predictor: MLP → softmax over node type vocabulary
  4. Port predictor: cross-attention between candidate ports and graph embedding
"""
from __future__ import annotations

import jax
import jax.numpy as jnp
import flax.linen as nn


class GATLayer(nn.Module):
    """Graph Attention layer (Veličković et al. 2018)."""

    out_features: int
    num_heads: int = 4
    dropout_rate: float = 0.1

    @nn.compact
    def __call__(self, node_feats: jnp.ndarray, edge_index: jnp.ndarray,
                 training: bool = False) -> jnp.ndarray:
        """
        Args:
            node_feats: [N, F_in]
            edge_index: [2, E]
        Returns:
            out: [N, out_features]
        """
        N, _ = node_feats.shape
        H = self.num_heads
        D = self.out_features // H

        # Linear projection for each head
        Wh = nn.Dense(H * D, use_bias=False)(node_feats)  # [N, H*D]
        Wh = Wh.reshape(N, H, D)  # [N, H, D]

        # Attention coefficients
        a_src = self.param("a_src", nn.initializers.glorot_uniform(), (H, D))
        a_dst = self.param("a_dst", nn.initializers.glorot_uniform(), (H, D))

        # Score = LeakyReLU(a_src · h_i + a_dst · h_j)
        src_score = jnp.sum(Wh * a_src, axis=-1)  # [N, H]
        dst_score = jnp.sum(Wh * a_dst, axis=-1)  # [N, H]

        src_idx = edge_index[0]  # [E]
        dst_idx = edge_index[1]  # [E]

        edge_score = nn.leaky_relu(
            src_score[src_idx] + dst_score[dst_idx], negative_slope=0.2
        )  # [E, H]

        # Softmax over neighbors (scatter-based)
        # For simplicity, use dense attention matrix
        attn = jnp.full((N, N, H), -1e9)
        attn = attn.at[dst_idx, src_idx].set(edge_score)
        attn = jax.nn.softmax(attn, axis=1)  # normalize over source nodes

        if training:
            attn = nn.Dropout(rate=self.dropout_rate)(attn, deterministic=False)

        # Weighted aggregation: [N, N, H] × [N, H, D] → [N, H, D]
        out = jnp.einsum("nsh,shd->nhd", attn, Wh)
        out = out.reshape(N, H * D)

        return out


class NodeRecommender(nn.Module):
    """Graph Attention Network for node graph recommendation.

    Args:
        node_vocab_size: Number of distinct node types in vocabulary.
        embed_dim: Node embedding dimension.
        num_gat_layers: Number of GAT encoder layers.
        num_heads: Attention heads per GAT layer.
    """

    node_vocab_size: int = 128
    embed_dim: int = 64
    num_gat_layers: int = 3
    num_heads: int = 4

    @nn.compact
    def __call__(self, node_features: jnp.ndarray, edge_index: jnp.ndarray,
                 training: bool = False) -> tuple[jnp.ndarray, jnp.ndarray]:
        """
        Args:
            node_features: [N, F_in] node feature vectors
            edge_index:    [2, E] edge connectivity
            training:      dropout enabled

        Returns:
            type_logits: [vocab_size] — predicted next node type distribution
            port_scores: [N] — score for connecting to each existing node
        """
        N, _ = node_features.shape

        # ── Encoder: stack of GAT layers ──
        h = nn.Dense(self.embed_dim)(node_features)

        for _ in range(self.num_gat_layers):
            h_new = GATLayer(
                out_features=self.embed_dim,
                num_heads=self.num_heads,
            )(h, edge_index, training=training)
            h = nn.LayerNorm()(h + h_new)  # residual + norm

        # ── Graph readout: mean pooling ──
        graph_embed = jnp.mean(h, axis=0)  # [embed_dim]

        # ── Node type prediction ──
        type_hidden = nn.Dense(128)(graph_embed)
        type_hidden = nn.gelu(type_hidden)
        type_logits = nn.Dense(self.node_vocab_size)(type_hidden)

        # ── Port prediction: score each existing node ──
        # Cross-attention: graph embedding queries each node
        query = nn.Dense(self.embed_dim)(graph_embed)  # [D]
        keys = nn.Dense(self.embed_dim)(h)              # [N, D]
        port_scores = jnp.dot(keys, query) / jnp.sqrt(float(self.embed_dim))  # [N]

        return type_logits, port_scores


def recommend_loss(type_logits: jnp.ndarray, type_target: int,
                   port_scores: jnp.ndarray, port_target: int) -> jnp.ndarray:
    """Combined loss for node recommendation.

    Args:
        type_logits: [V] unnormalized type scores
        type_target: ground truth type index
        port_scores: [N] unnormalized port attachment scores
        port_target: ground truth port index
    Returns:
        Scalar loss
    """
    type_loss = -jax.nn.log_softmax(type_logits)[type_target]
    port_loss = -jax.nn.log_softmax(port_scores)[port_target]
    return type_loss + port_loss
