"""Thin re-export shim — canonical implementations live in their respective modules."""
from brml.models.pfsf_surrogate import PFSFSurrogate, FNO3D, FNO3DMultiField, SpectralConv3D, FNOBlock, surrogate_loss
from brml.models.fno_fluid import FluidSurrogate, fluid_loss
from brml.models.collapse_predictor import CollapsePredictor, collapse_loss

__all__ = [
    "PFSFSurrogate", "FNO3D", "FNO3DMultiField",
    "SpectralConv3D", "FNOBlock",
    "FluidSurrogate",
    "CollapsePredictor",
    "surrogate_loss", "fluid_loss", "collapse_loss"
]
