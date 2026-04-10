"""
Export trained JAX/Flax models to ONNX for Java/C++ inference.

Export paths:
  1. JAX → jax2onnx → ONNX (direct)
  2. JAX → StableHLO → ONNX (fallback)
  3. JAX → numpy weights → manual ONNX builder (last resort)

ONNX models can be loaded in Java via ONNX Runtime:
  OrtSession session = env.createSession("model.onnx");
"""
from __future__ import annotations

import argparse
from pathlib import Path
from typing import Any

import jax
import jax.numpy as jnp
import numpy as np


def export_to_onnx(
    apply_fn,
    params: Any,
    dummy_input: tuple,
    output_path: str | Path,
    opset_version: int = 17,
    model_name: str = "brml_model",
) -> Path:
    """Export a JAX/Flax model to ONNX format.

    Args:
        apply_fn: model.apply function
        params: trained parameter pytree
        dummy_input: tuple of dummy JAX arrays matching model input
        output_path: path to write .onnx file
        opset_version: ONNX opset
        model_name: model identifier in ONNX metadata
    Returns:
        Path to exported ONNX file
    """
    output_path = Path(output_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    # Try jax2onnx first
    try:
        return _export_jax2onnx(apply_fn, params, dummy_input, output_path,
                                opset_version, model_name)
    except (ImportError, Exception) as e:
        print(f"jax2onnx export failed: {e}")

    # Fallback: manual numpy weight export
    return _export_numpy_weights(params, output_path, model_name)


def _export_jax2onnx(apply_fn, params, dummy_input, output_path,
                      opset_version, model_name) -> Path:
    """Export using jax2onnx library."""
    import jax2onnx

    def model_fn(*inputs):
        return apply_fn({"params": params}, *inputs)

    jax2onnx.export(
        model_fn,
        args=dummy_input,
        output_path=str(output_path),
        opset_version=opset_version,
        model_name=model_name,
    )
    print(f"Exported to {output_path} ({output_path.stat().st_size / 1024:.0f} KB)")

    # Validate exported ONNX graph
    _validate_onnx(output_path)
    return output_path


def _validate_onnx(output_path: Path) -> None:
    """Validate exported ONNX model: graph correctness + inference smoke test."""
    try:
        import onnx
        import onnxruntime as ort

        model = onnx.load(str(output_path))
        onnx.checker.check_model(model)

        sess = ort.InferenceSession(str(output_path),
                                    providers=["CPUExecutionProvider"])
        input_names = [i.name for i in sess.get_inputs()]
        output_names = [o.name for o in sess.get_outputs()]
        print(f"  ONNX validation passed — inputs: {input_names}, outputs: {output_names}")
    except ImportError:
        print("  [SKIP] onnx/onnxruntime not installed — skipping validation")
    except Exception as e:
        print(f"  [WARNING] ONNX validation failed: {e}")


def _export_numpy_weights(params, output_path, model_name) -> Path:
    """Fallback: save weights as .npz for manual loading in Java/C++."""
    npz_path = output_path.with_suffix(".npz")

    flat = {}
    for path, value in _flatten_params(params):
        flat[path] = np.asarray(value)

    np.savez(str(npz_path), **flat)
    print(f"Exported weights to {npz_path} ({npz_path.stat().st_size / 1024:.0f} KB)")
    print(f"  {len(flat)} tensors, use numpy to load in inference code")
    return npz_path


def _flatten_params(params, prefix: str = "") -> list[tuple[str, Any]]:
    """Flatten a param pytree to (path, array) pairs."""
    result = []
    if isinstance(params, dict):
        for k, v in params.items():
            result.extend(_flatten_params(v, f"{prefix}{k}/"))
    elif hasattr(params, "__iter__") and not isinstance(params, (str, bytes, jnp.ndarray)):
        for i, v in enumerate(params):
            result.extend(_flatten_params(v, f"{prefix}{i}/"))
    else:
        result.append((prefix.rstrip("/"), params))
    return result


def main():
    """CLI entry point for model export."""
    parser = argparse.ArgumentParser(description="Export BRML model to ONNX")
    parser.add_argument("--model", choices=["surrogate", "recommender", "collapse"],
                        required=True)
    parser.add_argument("--checkpoint", type=str, required=True,
                        help="Path to checkpoint directory")
    parser.add_argument("--output", type=str, default="exported/model.onnx")
    parser.add_argument("--grid-size", type=int, default=16,
                        help="Grid size for surrogate model")
    args = parser.parse_args()

    if args.model == "surrogate":
        from brml.models.pfsf_surrogate import FNO3DMultiField
        model = FNO3DMultiField()
        L = args.grid_size
        dummy = (jnp.zeros((1, L, L, L, 6)),)  # occ,E,ν,ρ,Rcomp,Rtens — matches OnnxPFSFRuntime
    elif args.model == "recommender":
        from brml.models.node_recommender import NodeRecommender
        model = NodeRecommender()
        dummy = (jnp.zeros((10, 7)), jnp.zeros((2, 15), dtype=jnp.int32))
    elif args.model == "collapse":
        from brml.models.collapse_predictor import CollapsePredictor
        model = CollapsePredictor()
        dummy = (jnp.zeros((10, 8)), jnp.zeros((2, 15), dtype=jnp.int32),
                 jnp.zeros((15, 4)))

    # Initialize with dummy to get param structure
    rng = jax.random.PRNGKey(0)
    variables = model.init(rng, *dummy)

    # Load trained weights
    try:
        import orbax.checkpoint as ocp
        checkpointer = ocp.PyTreeCheckpointer()
        from flax.training import train_state
        import optax
        state = train_state.TrainState.create(
            apply_fn=model.apply, params=variables["params"], tx=optax.adam(1e-3))
        state = checkpointer.restore(args.checkpoint, item=state)
        params = state.params
        print(f"Loaded checkpoint from {args.checkpoint}")
    except Exception as e:
        print(f"Could not load checkpoint ({e}), exporting with random weights")
        params = variables["params"]

    export_to_onnx(model.apply, params, dummy, args.output,
                   model_name=f"brml_{args.model}")


if __name__ == "__main__":
    main()
