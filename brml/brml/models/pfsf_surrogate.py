"""
Fourier Neural Operator (FNO) for 3D physics surrogate.

Architecture: Li et al. 2021 "Fourier Neural Operator for Parametric PDEs"
  - Lifting layer: input channels → hidden
  - N spectral convolution layers (Fourier space)
  - Multi-head projection: hidden → output fields

Input:  [B, Lx, Ly, Lz, C_in]  (geometry + material)
Output: depends on model variant:
  - FNO3D:          [B, Lx, Ly, Lz, 1]   — single field (phi or von Mises)
  - FNO3DMultiField: [B, Lx, Ly, Lz, 10]  — stress(6) + displacement(3) + phi(1)

Spectral convolution operates in frequency domain via rFFT3D,
keeping only the lowest `modes` frequencies per dimension.
"""
from __future__ import annotations

import jax
import jax.numpy as jnp
import flax.linen as nn


class SpectralConv3D(nn.Module):
    """3D spectral convolution layer in Fourier space."""

    in_channels: int
    out_channels: int
    modes: int = 8  # number of Fourier modes to keep per dimension

    @nn.compact
    def __call__(self, x: jnp.ndarray) -> jnp.ndarray:
        # x: [B, Lx, Ly, Lz, C_in]
        B, Lx, Ly, Lz, _ = x.shape

        # rFFT halves the LAST transformed axis: Lz → Lz//2+1
        # Clamp modes per axis: xy use min(modes, L), z uses min(modes, Lz//2+1)
        mx = min(self.modes, Lx)
        my = min(self.modes, Ly)
        mz = min(self.modes, Lz // 2 + 1)  # rFFT axis!

        # Complex weight tensor [C_in, C_out, mx, my, mz]
        scale = 1.0 / (self.in_channels * self.out_channels)
        weights_r = self.param(
            "weights_r",
            nn.initializers.normal(stddev=scale),
            (self.in_channels, self.out_channels, mx, my, mz),
        )
        weights_i = self.param(
            "weights_i",
            nn.initializers.normal(stddev=scale),
            (self.in_channels, self.out_channels, mx, my, mz),
        )
        weights = weights_r + 1j * weights_i

        # Forward FFT (real-to-complex, last spatial axis halved)
        x_ft = jnp.fft.rfftn(x, axes=(1, 2, 3))  # [B, Lx, Ly, Lz//2+1, C]

        # Truncate to kept modes (respecting rFFT axis limit)
        x_modes = x_ft[:, :mx, :my, :mz, :]  # [B, mx, my, mz, C_in]

        # Complex multiply: contract input channels, element-wise on spatial modes
        # x_modes: [B, mx, my, mz, C_in]   weights: [C_in, C_out, mx, my, mz]
        # → [B, mx, my, mz, C_out]
        # Note: optimize=True uses highly optimized BLAS paths under the hood
        out_modes = jnp.einsum("bxyzi,ioxyz->bxyzo", x_modes, weights, optimize=True)

        # Pad back to full frequency grid
        out_ft = jnp.zeros((B, Lx, Ly, Lz // 2 + 1, self.out_channels), dtype=jnp.complex64)
        out_ft = out_ft.at[:, :mx, :my, :mz, :].set(out_modes)

        # Inverse FFT
        out = jnp.fft.irfftn(out_ft, s=(Lx, Ly, Lz), axes=(1, 2, 3))  # [B, Lx, Ly, Lz, C_out]
        return out.real


class FNOBlock(nn.Module):
    """Single FNO block: spectral conv + linear bypass + activation."""

    channels: int
    modes: int = 8

    @nn.compact
    def __call__(self, x: jnp.ndarray) -> jnp.ndarray:
        # Spectral path
        x_spec = SpectralConv3D(
            in_channels=self.channels,
            out_channels=self.channels,
            modes=self.modes,
        )(x)

        # Local linear bypass (1×1×1 conv equivalent)
        x_local = nn.Dense(self.channels)(x)

        return nn.gelu(x_spec + x_local)


class PFSFSurrogate(nn.Module):
    """PFSF-native surrogate — predicts converged φ from geometry.

    Input channels (6): occupancy, E_norm, nu, density_norm, rcomp_norm, rtens_norm
    Output: 1-channel φ field (directly compatible with failure_scan)
    """

    hidden: int = 48
    layers: int = 4
    modes: int = 6

    @nn.compact
    def __call__(self, x: jnp.ndarray) -> jnp.ndarray:
        h = nn.Dense(self.hidden)(x)
        for _ in range(self.layers):
            h = FNOBlock(self.hidden, self.modes)(h)
        h = nn.Dense(64)(h)
        h = nn.gelu(h)
        return nn.Dense(1)(h)


class FNO3D(nn.Module):
    """3D Fourier Neural Operator for PFSF physics surrogate.

    Args:
        hidden_channels: Width of the FNO layers.
        num_layers: Number of spectral convolution blocks.
        modes: Number of Fourier modes to keep per dimension.
        in_channels: Input feature channels
            (source=1, cond=6, type=1, rcomp=1 → 9).
    """

    hidden_channels: int = 64
    num_layers: int = 4
    modes: int = 8
    in_channels: int = 9

    @nn.compact
    def __call__(self, x: jnp.ndarray) -> jnp.ndarray:
        """
        Args:
            x: [B, Lx, Ly, Lz, C_in] input fields
        Returns:
            phi: [B, Lx, Ly, Lz, 1] predicted steady-state potential
        """
        # Lifting: project input to hidden dimension
        h = nn.Dense(self.hidden_channels)(x)  # [B, Lx, Ly, Lz, H]

        # Spectral convolution blocks
        for _ in range(self.num_layers):
            h = FNOBlock(channels=self.hidden_channels, modes=self.modes)(h)

        # Projection: hidden → 1 (width scales with hidden_channels)
        h = nn.Dense(max(self.hidden_channels * 2, 64))(h)
        h = nn.gelu(h)
        h = nn.Dense(1)(h)

        return h  # [B, Lx, Ly, Lz, 1]


class FNO3DMultiField(nn.Module):
    """Multi-field FNO: predicts all physics quantities simultaneously.

    Output channels (10 total):
      [0:6]  stress tensor (Voigt): σ_xx, σ_yy, σ_zz, τ_xy, τ_yz, τ_xz
      [6:9]  displacement: u_x, u_y, u_z
      [9]    PFSF-compatible phi (potential field)

    This lets PFSF read the phi channel directly, while FEM-aware consumers
    can read the full stress tensor for accurate failure assessment.
    """

    hidden_channels: int = 64
    num_layers: int = 4
    modes: int = 8
    in_channels: int = 5

    @nn.compact
    def __call__(self, x: jnp.ndarray) -> jnp.ndarray:
        """
        Args:
            x: [B, Lx, Ly, Lz, C_in]
        Returns:
            fields: [B, Lx, Ly, Lz, 10]
        """
        # Shared backbone
        h = nn.Dense(self.hidden_channels)(x)
        for _ in range(self.num_layers):
            h = FNOBlock(channels=self.hidden_channels, modes=self.modes)(h)

        # Head width scales with backbone width (not hardcoded 64)
        head_w = max(self.hidden_channels, 32)

        # ── Stress head (6 channels) ──
        s = nn.Dense(head_w)(h)
        s = nn.gelu(s)
        stress = nn.Dense(6)(s)  # [B,L,L,L,6]

        # ── Displacement head (3 channels) ──
        d = nn.Dense(head_w)(h)
        d = nn.gelu(d)
        disp = nn.Dense(3)(d)  # [B,L,L,L,3]

        # ── Phi head (1 channel, PFSF-compatible) ──
        p = nn.Dense(head_w)(h)
        p = nn.gelu(p)
        phi = nn.Dense(1)(p)  # [B,L,L,L,1]

        return jnp.concatenate([stress, disp, phi], axis=-1)  # [B,L,L,L,10]


def split_multi_field(output: jnp.ndarray):
    """Split FNO3DMultiField output into named fields.

    Args:
        output: [B, Lx, Ly, Lz, 10]
    Returns:
        stress:       [B, Lx, Ly, Lz, 6]  (σ_xx, σ_yy, σ_zz, τ_xy, τ_yz, τ_xz)
        displacement: [B, Lx, Ly, Lz, 3]  (u_x, u_y, u_z)
        phi:          [B, Lx, Ly, Lz, 1]  (PFSF potential)
    """
    return output[..., :6], output[..., 6:9], output[..., 9:]


def compute_von_mises_from_stress(stress: jnp.ndarray) -> jnp.ndarray:
    """Compute von Mises from predicted stress tensor.

    Args:
        stress: [..., 6]  Voigt notation
    Returns:
        vm: [...]  scalar von Mises stress
    """
    s = stress
    return jnp.sqrt(
        s[..., 0]**2 + s[..., 1]**2 + s[..., 2]**2
        - s[..., 0]*s[..., 1] - s[..., 1]*s[..., 2] - s[..., 0]*s[..., 2]
        + 3.0 * (s[..., 3]**2 + s[..., 4]**2 + s[..., 5]**2)
    )


def huber_loss(pred, target, delta=1.0):
    """Huber loss — smooth L1, robust to outliers."""
    diff = jnp.abs(pred - target)
    return jnp.where(diff < delta, 0.5 * diff**2, delta * (diff - 0.5 * delta))


def surrogate_loss(pred, target, mask, delta=1.0):
    """Masked Huber loss for φ field prediction."""
    loss = huber_loss(pred.squeeze(-1), target, delta) * mask
    return jnp.sum(loss) / (jnp.sum(mask) + 1e-8)


def prepare_input(source: jnp.ndarray, conductivity: jnp.ndarray,
                  voxel_type: jnp.ndarray, rcomp: jnp.ndarray) -> jnp.ndarray:
    """Stack physics fields into FNO input tensor (DEPRECATED — 9ch version).

    WARNING: Uses per-batch max normalization which is INCONSISTENT with
    auto_train's fixed-constant normalization. Use build_input_tensor()
    from brml.pipeline.auto_train for production training instead.

    Only kept for backward compatibility with single-field FNO3D.
    """
    import warnings
    warnings.warn("prepare_input uses per-batch normalization; "
                  "use auto_train.build_input_tensor for fixed-constant normalization",
                  DeprecationWarning, stacklevel=2)

    B, Lx, Ly, Lz = source.shape

    # Fixed-constant normalization (aligned with auto_train)
    src_norm = source / (jnp.max(jnp.abs(source)) + 1e-8)
    cond_norm = conductivity / (jnp.max(jnp.abs(conductivity)) + 1e-8)
    type_norm = voxel_type.astype(jnp.float32) / 2.0
    rc_norm = rcomp / (jnp.max(jnp.abs(rcomp)) + 1e-8)

    # Rearrange conductivity: [B, 6, Lx, Ly, Lz] → [B, Lx, Ly, Lz, 6]
    cond_t = jnp.transpose(cond_norm, (0, 2, 3, 4, 1))

    # Stack: [source, cond×6, type, rcomp] = 9 channels
    return jnp.concatenate([
        src_norm[..., None],   # [B, Lx, Ly, Lz, 1]
        cond_t,                 # [B, Lx, Ly, Lz, 6]
        type_norm[..., None],  # [B, Lx, Ly, Lz, 1]
        rc_norm[..., None],    # [B, Lx, Ly, Lz, 1]
    ], axis=-1)
