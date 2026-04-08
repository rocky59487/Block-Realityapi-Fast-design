"""
ONNX contracts — exact tensor specifications for Python↔Java boundary.

Every model exported from brml MUST match these contracts.
Every Java loader MUST validate against these contracts.

If either side changes, the contract here must be updated FIRST,
then both sides updated to match.
"""
from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class TensorSpec:
    name: str
    shape: tuple           # symbolic: -1 = dynamic, >0 = fixed
    dtype: str             # "float32", "int32", "int64"
    description: str


@dataclass(frozen=True)
class ModelContract:
    model_id: str
    version: int
    inputs: list[TensorSpec]
    outputs: list[TensorSpec]
    notes: str


# ═══════════════════════════════════════════════════════════════
#  Contract 1: FNO3DMultiField — structural physics surrogate
# ═══════════════════════════════════════════════════════════════

SURROGATE = ModelContract(
    model_id="bifrost_surrogate",
    version=1,
    inputs=[
        TensorSpec("input", (1, -1, -1, -1, 6), "float32",
                   "occ(1) + E_norm(1) + nu(1) + rho_norm(1) + rcomp_norm(1) + rtens_norm(1)"),
    ],
    outputs=[
        TensorSpec("output", (1, -1, -1, -1, 10), "float32",
                   "stress_voigt(6) + displacement(3) + phi(1)"),
    ],
    notes="Grid dims must match training grid. "
          "Normalization: E/200e9, rho/7850, rcomp/250. "
          "Output denorm: multiply by vm_scale (stored in npz __vm_scale__)."
)

# ═══════════════════════════════════════════════════════════════
#  Contract 2: FNOFluid3D — water simulation
# ═══════════════════════════════════════════════════════════════

FLUID = ModelContract(
    model_id="bifrost_fluid",
    version=1,
    inputs=[
        TensorSpec("input", (1, -1, -1, -1, 8), "float32",
                   "velocity(3) + pressure(1) + boundary(1) + position(3)"),
    ],
    outputs=[
        TensorSpec("output", (1, -1, -1, -1, 4), "float32",
                   "velocity_next(3) + pressure_next(1)"),
    ],
    notes="Resolution 0.1m per cell. 1 block = 10×10×10 cells. "
          "Boundary: 1=fluid, 0=solid. Position normalized to [0,1]."
)

# ═══════════════════════════════════════════════════════════════
#  Contract 3: LODClassifier — chunk physics tier
# ═══════════════════════════════════════════════════════════════

LOD = ModelContract(
    model_id="bifrost_lod",
    version=1,
    inputs=[
        TensorSpec("input", (-1, 14), "float32",
                   "14-dim chunk features (see lod_classifier.py)"),
    ],
    outputs=[
        TensorSpec("output", (-1, 4), "float32",
                   "logits for 4 tiers: SKIP(0) MARK(1) PFSF(2) FNO(3)"),
    ],
    notes="Batch dimension flexible. argmax(output) = tier."
)

# ═══════════════════════════════════════════════════════════════
#  Contract 4: CollapsePredictor — failure classification
# ═══════════════════════════════════════════════════════════════

COLLAPSE = ModelContract(
    model_id="bifrost_collapse",
    version=1,
    inputs=[
        TensorSpec("node_features", (-1, 8), "float32",
                   "density, rcomp, rtens, youngs, poisson, is_anchor, height, degree"),
        TensorSpec("edge_index", (2, -1), "int32",
                   "src/dst pairs"),
        TensorSpec("edge_features", (-1, 4), "float32",
                   "conductivity, dx, dy, dz"),
    ],
    outputs=[
        TensorSpec("class_logits", (-1, 5), "float32",
                   "5 classes: OK(0) CANTILEVER(1) CRUSHING(2) NO_SUPPORT(3) TENSION(4)"),
        TensorSpec("collapse_prob", (-1,), "float32",
                   "per-node collapse probability [0,1]"),
    ],
    notes="Graph inputs — variable size. Java must pad or handle dynamic shapes."
)

ALL_CONTRACTS = {
    "surrogate": SURROGATE,
    "fluid": FLUID,
    "lod": LOD,
    "collapse": COLLAPSE,
}


def validate_contract(model_id: str, input_shapes: dict, output_shapes: dict) -> list[str]:
    """Validate actual tensor shapes against contract.

    Returns list of errors (empty = OK).
    """
    contract = ALL_CONTRACTS.get(model_id)
    if not contract:
        return [f"Unknown model_id: {model_id}"]

    errors = []

    for spec in contract.inputs:
        if spec.name not in input_shapes:
            errors.append(f"Missing input: {spec.name}")
            continue
        actual = input_shapes[spec.name]
        if len(actual) != len(spec.shape):
            errors.append(f"Input {spec.name}: expected {len(spec.shape)}D, got {len(actual)}D")
        else:
            for i, (expected, got) in enumerate(zip(spec.shape, actual)):
                if expected > 0 and got != expected:
                    errors.append(f"Input {spec.name} dim[{i}]: expected {expected}, got {got}")

    for spec in contract.outputs:
        if spec.name not in output_shapes:
            errors.append(f"Missing output: {spec.name}")
            continue
        actual = output_shapes[spec.name]
        if len(actual) != len(spec.shape):
            errors.append(f"Output {spec.name}: expected {len(spec.shape)}D, got {len(actual)}D")

    return errors
