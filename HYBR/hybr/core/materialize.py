"""Materialize adaptive weights into a static SSGO parameter dict.

This is critical for ONNX export: we evaluate the HyperNet once for a given
geometry, obtain the spectral weights, and inject them into a standard
(non-adaptive) SSGO.  The resulting static model can then be exported with
standard jax2onnx or manual ONNX builders.
"""
from __future__ import annotations

import jax
import jax.numpy as jnp

from brnext.models.ssgo import SSGO
from hybr.core.adaptive_ssgo import AdaptiveSSGO
from hybr.core.geometry_encoder import SpectralGeometryEncoder
from hybr.core.hypernet import HyperMLP, SpectralWeightHead
from hybr.core.weight_bank import SpectralWeightBank
from hybr.models.low_rank_factors import cp_reconstruct_5d


def materialize_static_ssgo(
    adaptive_model: AdaptiveSSGO,
    variables: dict,
    occupancy: jnp.ndarray,
) -> tuple:
    """Generate a static SSGO parameter dict from an AdaptiveSSGO for a given occupancy.

    Args:
        adaptive_model: instance of AdaptiveSSGO (used only for shape/metadata)
        variables: dict with 'params' and 'batch_stats' from adaptive model init
        occupancy: [B, L, L, L] bool/float mask
    Returns:
        (static_params, static_model) where static_model is a standard SSGO
        and static_params can be passed to static_model.apply(...)
    """
    params = variables["params"]
    batch_stats = variables.get("batch_stats", {})

    occ = occupancy.astype(jnp.float32)
    B, L, _, _ = occ.shape

    # 1) Geometry encoder
    z = SpectralGeometryEncoder(adaptive_model.latent_dim).apply(
        {"params": params.get("SpectralGeometryEncoder_0", {})}, occ
    )

    # 2) HyperNet backbone
    hyper_feats = HyperMLP(
        hidden_widths=adaptive_model.hypernet_widths,
        latent_dim=adaptive_model.latent_dim,
    ).apply(
        {"params": params.get("HyperMLP_0", {}), "batch_stats": batch_stats.get("HyperMLP_0", {})},
        z, update_stats=False,
    )

    mx = min(adaptive_model.modes, L)
    my = min(adaptive_model.modes, L)
    mz = min(adaptive_model.modes, L // 2 + 1)

    def _materialize_one(head_key_r: str, head_key_i: str, bank_key: str):
        head_r_p = params.get(head_key_r, {})
        head_i_p = params.get(head_key_i, {})
        cp_r = SpectralWeightHead(
            rank=adaptive_model.rank,
            in_channels=adaptive_model.hidden,
            out_channels=adaptive_model.hidden,
            mx=mx, my=my, mz=mz,
            generate_mode_w=True,
        ).apply({"params": head_r_p}, hyper_feats)
        cp_i = SpectralWeightHead(
            rank=adaptive_model.rank,
            in_channels=adaptive_model.hidden,
            out_channels=adaptive_model.hidden,
            mx=mx, my=my, mz=mz,
            generate_mode_w=False,
        ).apply({"params": head_i_p}, hyper_feats)
        mode_delta = cp_r.pop("mode_w_delta")

        bank_params = params.get(bank_key, {})
        alpha = jax.nn.softplus(bank_params["alpha"])

        delta_r = jax.vmap(cp_reconstruct_5d, in_axes=(0, 0, 0, 0, 0, 0))(
            cp_r["lam"], cp_r["a"], cp_r["b"], cp_r["u"], cp_r["v"], cp_r["w"]
        )
        delta_i = jax.vmap(cp_reconstruct_5d, in_axes=(0, 0, 0, 0, 0, 0))(
            cp_i["lam"], cp_i["a"], cp_i["b"], cp_i["u"], cp_i["v"], cp_i["w"]
        )

        W_r = bank_params["weights_r_base"][None, ...] + alpha * delta_r
        W_i = bank_params["weights_i_base"][None, ...] + alpha * delta_i
        mode_w = bank_params["mode_w_base"][None, ...] + mode_delta
        mode_w = jax.nn.sigmoid(mode_w)

        return W_r + 1j * W_i, mode_w

    # Build static SSGO
    static_model = SSGO(
        hidden=adaptive_model.hidden,
        modes=adaptive_model.modes,
        n_global_layers=adaptive_model.n_global_layers,
        n_focal_layers=adaptive_model.n_focal_layers,
        n_backbone_layers=adaptive_model.n_backbone_layers,
        moe_hidden=adaptive_model.moe_hidden,
    )
    dummy_x = jnp.zeros((B, L, L, L, 6))
    static_vars = static_model.init(jax.random.PRNGKey(0), dummy_x)
    static_params = dict(static_vars["params"])

    n_global = adaptive_model.n_global_layers
    n_backbone = adaptive_model.n_backbone_layers

    bank_keys = sorted([k for k in params.keys() if k.startswith("SpectralWeightBank_")], key=lambda k: int(k.split("_")[-1]))
    head_r_keys = sorted([k for k in params.keys() if k.startswith("SpectralWeightHead_") and int(k.split("_")[-1]) % 2 == 0], key=lambda k: int(k.split("_")[-1]))
    head_i_keys = sorted([k for k in params.keys() if k.startswith("SpectralWeightHead_") and int(k.split("_")[-1]) % 2 == 1], key=lambda k: int(k.split("_")[-1]))

    assert len(bank_keys) == n_global + n_backbone
    assert len(head_r_keys) == len(bank_keys)
    assert len(head_i_keys) == len(bank_keys)

    fno_block_keys = sorted([k for k in static_params.keys() if k.startswith("FNOBlock_")], key=lambda k: int(k.split("_")[-1]))
    assert len(fno_block_keys) == n_global + n_backbone

    for hrk, hik, bnk, fbk in zip(head_r_keys, head_i_keys, bank_keys, fno_block_keys):
        W, mode_w = _materialize_one(hrk, hik, bnk)
        static_params[fbk]["WeightedSpectralConv3D_0"] = {
            "weights_r": W.real[0],
            "weights_i": W.imag[0],
            "mode_w": mode_w[0],
        }

    # Copy non-spectral parameters from adaptive to static
    # Mapping of adaptive key -> static key
    direct_copies = {
        "Dense_0": "Dense_0",
        "Dense_1": "Dense_1",
        "Dense_2": "Dense_2",
        "SparseVoxelGraphConv_0": "SparseVoxelGraphConv_0",
        "MoESpectralHead_0": "MoESpectralHead_0",
        "Dense_3": "Dense_3",
        "Dense_4": "Dense_4",
        "Dense_5": "Dense_5",
        "Dense_6": "Dense_6",
        "Dense_7": "Dense_7",
        "Dense_8": "Dense_8",
    }
    for ak, sk in direct_copies.items():
        if ak in params and sk in static_params:
            static_params[sk] = params[ak]

    # Map AdaptiveFNOBlock local bypass Dense to FNOBlock local bypass Dense
    adaptive_fno_keys = sorted([k for k in params.keys() if k.startswith("AdaptiveFNOBlock_")], key=lambda k: int(k.split("_")[-1]))
    for i, afk in enumerate(adaptive_fno_keys):
        fbk = fno_block_keys[i]
        if "Dense_0" in params[afk]:
            static_params[fbk]["Dense_0"] = params[afk]["Dense_0"]

    return static_params, static_model
