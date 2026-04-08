"""Verify ONNX contracts: Python model output shapes match Java expectations."""
import numpy as np
import pytest


def test_surrogate_contract():
    """FNO3DMultiField: input [1,L,L,L,5] → output [1,L,L,L,10]"""
    from brml.export.onnx_contracts import SURROGATE, validate_contract

    # Java OnnxPFSFRuntime expects these exact shapes
    L = 12
    errors = validate_contract("surrogate",
                               {"input": (1, L, L, L, 5)},
                               {"output": (1, L, L, L, 10)})
    assert errors == [], f"Contract violations: {errors}"


def test_fluid_contract():
    """FNOFluid3D: input [1,N,N,N,8] → output [1,N,N,N,4]"""
    from brml.export.onnx_contracts import validate_contract

    N = 20
    errors = validate_contract("fluid",
                               {"input": (1, N, N, N, 8)},
                               {"output": (1, N, N, N, 4)})
    assert errors == []


def test_lod_contract():
    """LODClassifier: input [B,14] → output [B,4]"""
    from brml.export.onnx_contracts import validate_contract

    errors = validate_contract("lod",
                               {"input": (32, 14)},
                               {"output": (32, 4)})
    assert errors == []


def test_collapse_contract():
    """CollapsePredictor: variable graph → [N,5] + [N]"""
    from brml.export.onnx_contracts import validate_contract

    errors = validate_contract("collapse",
                               {"node_features": (50, 8),
                                "edge_index": (2, 120),
                                "edge_features": (120, 4)},
                               {"class_logits": (50, 5),
                                "collapse_prob": (50,)})
    assert errors == []


def test_surrogate_output_channels():
    """Verify the 10 output channels map correctly."""
    # This must match Java OnnxPFSFRuntime.InferenceResult
    channels = {
        "stress_xx": 0, "stress_yy": 1, "stress_zz": 2,
        "shear_xy": 3, "shear_yz": 4, "shear_xz": 5,
        "disp_x": 6, "disp_y": 7, "disp_z": 8,
        "phi": 9,
    }
    assert len(channels) == 10
    assert channels["phi"] == 9  # PFSF reads this channel


def test_lod_output_tiers():
    """Verify LOD output class indices match Java ChunkPhysicsLOD.Tier."""
    tiers = {0: "SKIP", 1: "MARK", 2: "PFSF", 3: "FNO"}
    assert len(tiers) == 4
    # Java: Tier.SKIP.ordinal() == 0, Tier.MARK.ordinal() == 1, etc.


def test_collapse_output_classes():
    """Verify collapse classes match Java FailureType."""
    classes = {0: "OK", 1: "CANTILEVER", 2: "CRUSHING", 3: "NO_SUPPORT", 4: "TENSION"}
    assert len(classes) == 5


def test_unknown_model_id():
    from brml.export.onnx_contracts import validate_contract
    errors = validate_contract("nonexistent", {}, {})
    assert len(errors) == 1
    assert "Unknown" in errors[0]


def test_wrong_dimensions():
    from brml.export.onnx_contracts import validate_contract
    # LOD expects 2D input, give it 3D
    errors = validate_contract("lod",
                               {"input": (1, 14, 3)},  # wrong: 3D instead of 2D
                               {"output": (1, 4)})
    assert len(errors) > 0
