"""ONNX contract validation adapter.

Ensures that any model exported from BR-NeXT matches the Java-side
contracts defined in brml.export.onnx_contracts.
"""
from __future__ import annotations

from pathlib import Path
from typing import Any

import numpy as np


class ContractViolationError(Exception):
    """Raised when exported ONNX model violates the Java↔Python contract."""
    pass


def validate_surrogate_contract(onnx_path: str | Path) -> None:
    """Smoke-test an ONNX file against the surrogate contract.

    Contract (from brml.export.onnx_contracts):
      input:  [1, -1, -1, -1, 6] float32
      output: [1, -1, -1, -1, 10] float32
    """
    try:
        import onnx
        import onnxruntime as ort
    except ImportError as e:
        raise ContractViolationError(f"Missing validation deps: {e}")

    onnx_path = Path(onnx_path)
    if not onnx_path.exists():
        raise ContractViolationError(f"ONNX file not found: {onnx_path}")

    model = onnx.load(str(onnx_path))
    onnx.checker.check_model(model)

    sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    inputs = sess.get_inputs()
    outputs = sess.get_outputs()

    if len(inputs) != 1:
        raise ContractViolationError(f"Expected 1 input, got {len(inputs)}")
    if len(outputs) != 1:
        raise ContractViolationError(f"Expected 1 output, got {len(outputs)}")

    in_shape = inputs[0].shape
    out_shape = outputs[0].shape

    # Validate dims
    if len(in_shape) != 5 or in_shape[0] != 1 or in_shape[4] != 6:
        raise ContractViolationError(
            f"Input shape contract violation: expected [1, L, L, L, 6], got {in_shape}"
        )
    if len(out_shape) != 5 or out_shape[0] != 1 or out_shape[4] != 10:
        raise ContractViolationError(
            f"Output shape contract violation: expected [1, L, L, L, 10], got {out_shape}"
        )

    # Runtime shape check with dummy inference
    L = 8
    dummy = np.zeros((1, L, L, L, 6), dtype=np.float32)
    result = sess.run(None, {inputs[0].name: dummy})
    if result[0].shape != (1, L, L, L, 10):
        raise ContractViolationError(
            f"Runtime output shape mismatch: expected (1, {L}, {L}, {L}, 10), got {result[0].shape}"
        )


def validate_contract(model_id: str, onnx_path: str | Path) -> None:
    """Dispatch to the right validator."""
    if model_id == "surrogate":
        validate_surrogate_contract(onnx_path)
    else:
        # Fall back to brml native validator if available
        try:
            import sys
            sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "brml"))
            from brml.export.onnx_contracts import validate_contract as _validate
            import brml.export.onnx_contracts as oc
            sess = oc.ort.InferenceSession(str(onnx_path))
            in_shapes = {i.name: tuple(i.shape) for i in sess.get_inputs()}
            out_shapes = {o.name: tuple(o.shape) for o in sess.get_outputs()}
            errors = _validate(model_id, in_shapes, out_shapes)
            if errors:
                raise ContractViolationError("; ".join(errors))
        except Exception as e:
            raise ContractViolationError(f"Contract validation failed: {e}")
