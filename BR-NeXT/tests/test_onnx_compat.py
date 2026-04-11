"""ONNX contract compatibility tests.

Requires jax2onnx and onnxruntime.
"""
from __future__ import annotations

import numpy as np


def test_onnx_shape_contract():
    """Export a tiny SSGO and validate shapes."""
    try:
        import jax
        import jax.numpy as jnp
        from brnext.models.ssgo import SSGO
        from brnext.export.onnx_export import export_ssgo_to_onnx
        from brnext.export.contract_adapter import validate_surrogate_contract
    except ImportError as e:
        print(f"SKIP: missing dependency {e}")
        return

    model = SSGO(hidden=8, modes=2, n_global_layers=1,
                 n_focal_layers=1, n_backbone_layers=1, moe_hidden=8)
    rng = jax.random.PRNGKey(0)
    dummy = (jnp.zeros((1, 4, 4, 4, 6)),)
    variables = model.init(rng, *dummy)

    import tempfile, pathlib
    with tempfile.TemporaryDirectory() as tmpdir:
        onnx_path = pathlib.Path(tmpdir) / "test.onnx"
        export_ssgo_to_onnx(model, variables["params"], dummy, str(onnx_path))
        validate_surrogate_contract(str(onnx_path))
    print("ONNX contract test passed")
