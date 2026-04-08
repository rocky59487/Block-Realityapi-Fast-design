"""
LOD Classifier — lightweight model to predict physics LOD tier per chunk.

Input: chunk voxel snapshot (16×256×16 → compressed features)
Output: LOD tier (0=SKIP, 1=MARK, 2=PFSF, 3=FNO)

This model is designed to be EXTREMELY fast (<0.1ms per chunk) because
it runs on every loaded chunk. Uses a tiny MLP on hand-crafted features
rather than a heavy CNN.

Features (14-dim per chunk):
  [0]  fill_ratio         — fraction of non-air blocks
  [1]  player_edit_count  — number of player-placed/broken blocks
  [2]  max_unsupported_y  — highest block with no support below
  [3]  overhang_ratio     — blocks with no support / total
  [4]  surface_ratio      — exposed faces / ideal
  [5]  material_diversity — number of distinct materials
  [6]  mod_block_ratio    — mod blocks / total blocks
  [7]  vertical_variance  — std dev of blocks per Y layer
  [8]  has_fluid          — any water/lava source blocks
  [9]  has_redstone       — any redstone components
  [10] distance_to_player — nearest player distance (chunks)
  [11] last_edit_age      — ticks since last player edit
  [12] neighbor_activity  — average tier of adjacent chunks
  [13] structure_height   — max y - min y of player-placed blocks

The model learns to predict which tier is needed, saving compute
by not running PFSF on terrain that doesn't need it.
"""
from __future__ import annotations

import jax
import jax.numpy as jnp
import flax.linen as nn


class LODClassifier(nn.Module):
    """Tiny MLP for chunk physics LOD tier classification.

    4 classes: SKIP(0), MARK(1), PFSF(2), FNO(3)

    ~2K parameters — runs in <0.1ms on CPU.
    """

    hidden_dim: int = 32
    num_classes: int = 4
    in_features: int = 14

    @nn.compact
    def __call__(self, x: jnp.ndarray) -> jnp.ndarray:
        """
        Args:
            x: [B, 14] chunk feature vector
        Returns:
            logits: [B, 4] per-tier scores
        """
        h = nn.Dense(self.hidden_dim)(x)
        h = nn.relu(h)
        h = nn.Dense(self.hidden_dim)(h)
        h = nn.relu(h)
        return nn.Dense(self.num_classes)(h)

    def predict_tier(self, params, x: jnp.ndarray) -> jnp.ndarray:
        """Get tier prediction (argmax).

        Args:
            params: model parameters
            x: [B, 14] or [14]
        Returns:
            tier: [B] or [] int tier index
        """
        logits = self.apply({"params": params}, x)
        return jnp.argmax(logits, axis=-1)


def lod_loss(logits: jnp.ndarray, labels: jnp.ndarray,
             class_weights: jnp.ndarray = None) -> jnp.ndarray:
    """Weighted cross-entropy for LOD classification.

    Weight PFSF and FNO classes higher since misclassifying them
    as SKIP would cause visible physics bugs.

    Args:
        logits: [B, 4] unnormalized scores
        labels: [B] ground truth tier (0-3)
        class_weights: [4] per-class weights (default: [1, 2, 4, 4])
    """
    if class_weights is None:
        # SKIP is cheap to misclassify; PFSF/FNO are expensive to miss
        class_weights = jnp.array([1.0, 2.0, 4.0, 4.0])

    one_hot = jax.nn.one_hot(labels, 4)
    log_probs = jax.nn.log_softmax(logits)
    weighted = -jnp.sum(one_hot * log_probs * class_weights, axis=-1)
    return jnp.mean(weighted)


def extract_chunk_features(
    blocks: jnp.ndarray,         # [16, 256, 16] block type IDs
    player_edits: int,           # number of player modifications
    mod_block_count: int,        # number of mod blocks
    has_fluid: bool,
    has_redstone: bool,
    distance_to_player: float,   # chunk distance
    last_edit_ticks: int,        # ticks since last edit
    neighbor_avg_tier: float,    # average tier of 8 neighbors
) -> jnp.ndarray:
    """Extract 14-dim feature vector from a chunk.

    Args:
        blocks: voxel grid (0=air, >0=solid)
        ... other scalar features
    Returns:
        features: [14] normalized feature vector
    """
    solid = blocks > 0
    n_solid = jnp.sum(solid)
    n_total = 16 * 256 * 16

    # Fill ratio
    fill_ratio = n_solid / n_total

    # Overhang ratio
    shifted_down = jnp.roll(solid, 1, axis=1)
    shifted_down = shifted_down.at[:, 0, :].set(True)  # ground always supported
    unsupported = solid & ~shifted_down
    overhang_ratio = jnp.sum(unsupported) / jnp.maximum(n_solid, 1)

    # Max unsupported Y
    unsupported_y = jnp.where(unsupported, jnp.arange(256)[None, :, None], 0)
    max_unsupported_y = jnp.max(unsupported_y) / 256.0

    # Surface ratio
    n_surface = 0
    for axis in range(3):
        for delta in [-1, 1]:
            shifted = jnp.roll(solid, delta, axis=axis)
            n_surface = n_surface + jnp.sum(solid & ~shifted)
    ideal_surface = 6.0 * jnp.power(n_solid.astype(jnp.float32), 2.0/3.0)
    surface_ratio = n_surface / jnp.maximum(ideal_surface, 1.0)

    # Material diversity (unique non-zero block types)
    unique_count = jnp.sum(jnp.unique(blocks, size=256, fill_value=0) > 0)
    material_diversity = unique_count / 50.0  # normalize

    # Vertical variance
    layer_counts = jnp.sum(solid, axis=(0, 2)).astype(jnp.float32)
    vertical_variance = jnp.std(layer_counts) / jnp.maximum(jnp.mean(layer_counts), 1.0)

    # Structure height
    any_per_y = jnp.any(solid, axis=(0, 2))
    y_indices = jnp.where(any_per_y, jnp.arange(256), 0)
    height = (jnp.max(y_indices) - jnp.min(jnp.where(any_per_y, jnp.arange(256), 255))) / 256.0

    return jnp.array([
        fill_ratio,
        jnp.minimum(player_edits / 100.0, 1.0),
        max_unsupported_y,
        overhang_ratio,
        jnp.minimum(surface_ratio, 2.0) / 2.0,
        material_diversity,
        jnp.minimum(mod_block_count / jnp.maximum(n_solid, 1), 1.0),
        jnp.minimum(vertical_variance, 2.0) / 2.0,
        float(has_fluid),
        float(has_redstone),
        jnp.minimum(distance_to_player / 16.0, 1.0),
        jnp.minimum(last_edit_ticks / 6000.0, 1.0),  # 5 min
        neighbor_avg_tier / 3.0,
        height,
    ])
