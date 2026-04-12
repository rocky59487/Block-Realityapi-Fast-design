"""Block Reality ML — external training pipeline."""

try:
    import jax
    jax.config.update("jax_default_matmul_precision", "tensorfloat32")
except ImportError:
    pass

__version__ = "0.1.0"
