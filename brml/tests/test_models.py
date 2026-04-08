"""Smoke tests for BRML models — verify forward pass shapes."""
import jax
import jax.numpy as jnp
import pytest


def test_fno3d_forward():
    from brml.models.pfsf_surrogate import FNO3D, prepare_input

    model = FNO3D(hidden_channels=16, num_layers=2, modes=4, in_channels=9)
    rng = jax.random.PRNGKey(0)

    B, L = 2, 8
    x = jnp.ones((B, L, L, L, 9))
    variables = model.init(rng, x)
    out = model.apply(variables, x)

    assert out.shape == (B, L, L, L, 1)


def test_fno3d_prepare_input():
    from brml.models.pfsf_surrogate import prepare_input

    B, L = 2, 8
    source = jnp.ones((B, L, L, L))
    cond = jnp.ones((B, 6, L, L, L))
    vtype = jnp.ones((B, L, L, L), dtype=jnp.uint8)
    rcomp = jnp.ones((B, L, L, L))

    x = prepare_input(source, cond, vtype, rcomp)
    assert x.shape == (B, L, L, L, 9)


def test_node_recommender_forward():
    from brml.models.node_recommender import NodeRecommender

    model = NodeRecommender(node_vocab_size=32, embed_dim=16, num_gat_layers=2)
    rng = jax.random.PRNGKey(0)

    N = 5
    feats = jnp.ones((N, 7))
    edges = jnp.array([[0, 1, 2], [1, 2, 3]], dtype=jnp.int32)

    variables = model.init(rng, feats, edges)
    type_logits, port_scores = model.apply(variables, feats, edges)

    assert type_logits.shape == (32,)
    assert port_scores.shape == (N,)


def test_collapse_predictor_forward():
    from brml.models.collapse_predictor import CollapsePredictor

    model = CollapsePredictor(hidden_dim=32, num_mp_steps=2)
    rng = jax.random.PRNGKey(0)

    N, E = 8, 12
    node_feats = jnp.ones((N, 8))
    edge_idx = jnp.zeros((2, E), dtype=jnp.int32)
    edge_feats = jnp.ones((E, 4))

    variables = model.init(rng, node_feats, edge_idx, edge_feats)
    class_logits, collapse_prob = model.apply(variables, node_feats, edge_idx, edge_feats)

    assert class_logits.shape == (N, 5)
    assert collapse_prob.shape == (N,)


def test_collapse_loss():
    from brml.models.collapse_predictor import collapse_loss

    N = 8
    logits = jnp.ones((N, 5))
    labels = jnp.zeros(N, dtype=jnp.int32)
    probs = jnp.full(N, 0.5)

    loss = collapse_loss(logits, labels, probs)
    assert loss.shape == ()
    assert float(loss) > 0


def test_recommend_loss():
    from brml.models.node_recommender import recommend_loss

    type_logits = jnp.ones(32)
    port_scores = jnp.ones(5)

    loss = recommend_loss(type_logits, 0, port_scores, 0)
    assert loss.shape == ()
    assert float(loss) > 0
