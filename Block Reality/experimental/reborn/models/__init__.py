# models — AI/風格模組層
from .fno_proxy import FNOProxy
from .stress_path import (
    extract_principal_stress_paths,
    classify_path_morphology,
    filter_arch_paths,
    filter_flow_paths,
)
from .gaudi_style import GaudiStyle
from .zaha_style import ZahaStyle, blend_styles
from .hybr_proxy import HYBRProxy, STYLE_TOKENS

# JAX 可微分模組（需要 JAX/Flax，延遲匯入避免無 JAX 環境崩潰）
try:
    from .diff_sdf_ops import (
        density_to_sdf_diff, smooth_union, smooth_subtraction,
        smooth_intersection, sdf_sphere, sdf_hyperboloid,
        sdf_catenary_arch, blend_sdf,
    )
    from .diff_gaudi import DiffGaudiStyle
    from .diff_zaha import DiffZahaStyle
    from .style_net import StyleConditionedSSGO, StyleEmbedding, StyleDiscriminator
    _HAS_JAX = True
except ImportError:
    _HAS_JAX = False

__all__ = [
    "FNOProxy",
    "extract_principal_stress_paths",
    "classify_path_morphology",
    "filter_arch_paths",
    "filter_flow_paths",
    "GaudiStyle",
    "ZahaStyle",
    "blend_styles",
    "HYBRProxy",
    "STYLE_TOKENS",
    # JAX 可微分模組
    "DiffGaudiStyle",
    "DiffZahaStyle",
    "StyleConditionedSSGO",
    "StyleEmbedding",
    "StyleDiscriminator",
]
