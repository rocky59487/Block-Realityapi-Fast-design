"""Data management for BR-NeXT — DuckDB metadata + Zarr storage."""
from __future__ import annotations

from .dataset_registry import DatasetRegistry
from .zarr_store import ZarrDatasetStore
from .schematic_loader import SchematicLoader, SchematicData
from .block_material_map import guess_material, MATERIAL_PROPS

__all__ = [
    "DatasetRegistry",
    "ZarrDatasetStore",
    "SchematicLoader",
    "SchematicData",
    "guess_material",
    "MATERIAL_PROPS",
]
