"""ONNX export for BR-NeXT models."""
from __future__ import annotations

from pathlib import Path
from typing import Any

import jax
import jax.numpy as jnp
import numpy as np


def export_ssgo_to_onnx(
    model,
    params: Any,
    dummy_input: tuple,
    output_path: str | Path,
    opset_version: int = 17,
    dynamic_batch: bool = False,
) -> Path:
    """Export SSGO to ONNX, validating against the surrogate contract."""
    output_path = Path(output_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    try:
        path = _export_jax2onnx(model, params, dummy_input, output_path, opset_version, dynamic_batch)
    except Exception as e:
        print(f"jax2onnx failed: {e}")
        print("  Falling back to manual ONNX builder (zero dependency)...")
        from brnext.export.manual_onnx import export_ssgo_manual
        # Infer architecture from model if available
        n_global = getattr(model, "n_global_layers", 3)
        n_focal = getattr(model, "n_focal_layers", 2)
        n_backbone = getattr(model, "n_backbone_layers", 2)
        hidden = getattr(model, "hidden", 48)
        moe_h = getattr(model, "moe_hidden", 32)
        path = export_ssgo_manual(
            params,
            grid_size=dummy_input[0].shape[1],
            output_path=output_path,
            n_global_layers=n_global,
            n_focal_layers=n_focal,
            n_backbone_layers=n_backbone,
            hidden=hidden,
            moe_hidden=moe_h,
        )
    _embed_metadata_and_validate(path)
    return path


def _export_jax2onnx(model, params, dummy_input, output_path, opset_version, dynamic_batch: bool = False) -> Path:
    import jax2onnx
    import jax

    def model_fn(*inputs):
        return model.apply({"params": params}, *inputs)

    input_specs = []
    for arr in dummy_input:
        if dynamic_batch:
            spec = [tuple(["B"] + list(arr.shape[1:]))]
        else:
            spec = [jax.ShapeDtypeStruct(arr.shape, arr.dtype)]
        input_specs.extend(spec)

    model_proto = jax2onnx.to_onnx(
        model_fn,
        inputs=input_specs,
        opset=opset_version,
        model_name="brnext_ssgo",
    )

    # Save
    import onnx
    onnx.save(model_proto, str(output_path))
    return output_path


def _embed_metadata_and_validate(output_path: Path):
    from brnext.config import load_norm_constants
    import onnx
    norm = load_norm_constants()
    onnx_model = onnx.load(str(output_path))
    meta = onnx_model.metadata_props
    for key, value in norm.items():
        entry = meta.add()
        entry.key = key
        entry.value = str(value)
    onnx.save(onnx_model, str(output_path))
    print(f"Exported ONNX: {output_path} ({output_path.stat().st_size / 1024:.0f} KB)")
    from brnext.export.contract_adapter import validate_surrogate_contract
    validate_surrogate_contract(str(output_path))
    print("  Contract validation passed.")


def _export_numpy_weights(params, output_path: Path) -> Path:
    npz_path = output_path.with_suffix(".npz")
    flat = {}

    def _flatten(p, prefix=""):
        if isinstance(p, dict):
            for k, v in p.items():
                yield from _flatten(v, f"{prefix}{k}/")
        else:
            yield (prefix.rstrip("/"), p)

    for path, val in _flatten(params):
        flat[path] = np.asarray(val)
    np.savez(str(npz_path), **flat)
    print(f"Exported weights: {npz_path}")
    return npz_path


def main():
    import argparse
    parser = argparse.ArgumentParser(description="Export BR-NeXT SSGO to ONNX")
    parser.add_argument("--checkpoint", type=str, required=True)
    parser.add_argument("--grid", type=int, default=12)
    parser.add_argument("--output", type=str, default="brnext_output/brnext_ssgo.onnx")
    args = parser.parse_args()

    from brnext.models.ssgo import SSGO
    model = SSGO()
    rng = jax.random.PRNGKey(0)
    dummy = (jnp.zeros((1, args.grid, args.grid, args.grid, 7)),)
    variables = model.init(rng, *dummy)

    # Load checkpoint
    try:
        import orbax.checkpoint as ocp
        from flax.training import train_state
        import optax
        state = train_state.TrainState.create(
            apply_fn=model.apply, params=variables["params"], tx=optax.adam(1e-3))
        checkpointer = ocp.PyTreeCheckpointer()
        state = checkpointer.restore(args.checkpoint, item=state)
        params = state.params
        print(f"Loaded checkpoint: {args.checkpoint}")
    except Exception as e:
        print(f"Could not load checkpoint ({e}), using random init")
        params = variables["params"]

    export_ssgo_to_onnx(model, params, dummy, args.output)


if __name__ == "__main__":
    main()
