"""Convergence test: AdaptiveSSGO should fit a small synthetic dataset."""
from __future__ import annotations

import sys
from pathlib import Path

BRNEXT_ROOT = Path(__file__).resolve().parent.parent / "BR-NeXT"
if str(BRNEXT_ROOT) not in sys.path:
    sys.path.insert(0, str(BRNEXT_ROOT))

import jax
import jax.numpy as jnp
import optax
import numpy as np
import flax

from hybr.core.adaptive_ssgo import AdaptiveSSGO
from brnext.pipeline.structure_gen import generate_structure
from brnext.models.losses import hybrid_task_loss


def build_input(struct):
    occ = jnp.array(struct.occupancy)
    E = jnp.array(struct.E_field) / 200e9
    nu = jnp.array(struct.nu_field)
    rho = jnp.array(struct.density_field) / 7850.0
    rc = jnp.array(struct.rcomp_field) / 250.0
    rt = jnp.array(struct.rtens_field) / 500.0
    return jnp.stack([occ, E, nu, rho, rc, rt], axis=-1)


def generate_dataset(n_samples, L, seed):
    rng = np.random.default_rng(seed)
    styles = ["tower", "bridge", "cantilever"]
    xs, targets, masks = [], [], []
    for _ in range(n_samples):
        style = rng.choice(styles)
        struct = generate_structure(L, rng, style)
        occ = struct.occupancy.astype(bool)
        if not occ.any():
            continue
        x = build_input(struct)
        # Fake target: just a function of input so the net can learn it
        target_stress = jnp.tile(x[..., 1:2], (1, 1, 1, 6)) * occ[..., None]
        target_disp = jnp.tile(x[..., 2:3], (1, 1, 1, 3)) * occ[..., None]
        target_phi = x[..., 0:1] * occ[..., None]
        target = jnp.concatenate([target_stress, target_disp, target_phi], axis=-1)
        xs.append(x)
        targets.append(target)
        masks.append(occ)
    return jnp.stack(xs), jnp.stack(targets), jnp.stack(masks)


def test_convergence():
    L = 8
    n_samples = 8
    n_steps = 50
    model = AdaptiveSSGO(
        hidden=16, modes=4,
        n_global_layers=1, n_focal_layers=1, n_backbone_layers=1,
        moe_hidden=16, latent_dim=16, hypernet_widths=(32, 32),
        rank=2, encoder_type="spectral",
    )

    rng = jax.random.PRNGKey(42)
    dummy = jnp.zeros((1, L, L, L, 6))
    variables = model.init(rng, dummy, update_stats=False, mutable=["params", "batch_stats"])
    params = variables["params"]
    batch_stats = variables.get("batch_stats", {})

    xs, targets, masks = generate_dataset(n_samples, L, seed=0)

    opt = optax.chain(
        optax.clip_by_global_norm(1.0),
        optax.adamw(1e-3, weight_decay=1e-4),
    )
    def _label_leaf(path, _):
        path_str = "/".join(str(p.key) for p in path)
        return "hyper" if "HyperMLP" in path_str or "SpectralWeightHead" in path_str else "base"

    mask = jax.tree_util.tree_map_with_path(_label_leaf, params)
    tx = optax.multi_transform(
        {"base": opt, "hyper": opt},
        mask,
    )
    from flax.training import train_state
    state = train_state.TrainState.create(
        apply_fn=lambda p, x: model.apply(
            {"params": p, "batch_stats": batch_stats},
            x, update_stats=False
        ),
        params=params,
        tx=tx,
    )

    @jax.jit
    def train_step(state, x, target, mask):
        def loss_fn(p):
            pred = state.apply_fn(p, x)
            loss = jnp.mean((pred - target) ** 2 * mask[..., None])
            return loss
        loss, grads = jax.value_and_grad(loss_fn)(state.params)
        return state.apply_gradients(grads=grads), loss

    losses = []
    for step in range(n_steps):
        perm = jax.random.permutation(jax.random.PRNGKey(step), n_samples)
        batch_losses = []
        for i in range(0, n_samples, 2):
            idx = perm[i:i+2]
            xb = xs[idx]
            tb = targets[idx]
            mb = masks[idx]
            state, loss = train_step(state, xb, tb, mb)
            batch_losses.append(float(loss))
        losses.append(np.mean(batch_losses))

    # Check that loss decreased significantly
    initial = losses[0]
    final = losses[-1]
    assert final < initial * 0.8, f"Loss did not decrease: {initial:.6f} -> {final:.6f}"
    print(f"[OK] test_convergence passed: loss {initial:.6f} -> {final:.6f}")


if __name__ == "__main__":
    test_convergence()
