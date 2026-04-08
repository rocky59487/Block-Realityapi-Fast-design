"""Block Reality ML — external training pipeline."""
import os

# JAX hardware configuration for performance
os.environ["XLA_PYTHON_CLIENT_PREALLOCATE"] = "false"
os.environ["XLA_PYTHON_CLIENT_MEM_FRACTION"] = "0.85"

# Enable TensorFloat-32 for Nvidia 30/40/50-series GPUs (e.g. 5070 Ti)
os.environ["NVIDIA_TF32_OVERRIDE"] = "1"

try:
    import jax
    jax.config.update("jax_default_matmul_precision", "tensorfloat32")
except ImportError:
    pass

__version__ = "0.1.0"
