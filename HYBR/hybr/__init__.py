"""HYBR — HyperNetwork meta-learning engine for PFSF structural dynamics."""

__version__ = "0.1.0"

from hybr.core.adaptive_ssgo import AdaptiveSSGO
from hybr.core.materialize import materialize_static_ssgo
from hybr.training.meta_trainer import HYBRTrainer, HYBRConfig

__all__ = [
    "AdaptiveSSGO",
    "materialize_static_ssgo",
    "HYBRTrainer",
    "HYBRConfig",
]
