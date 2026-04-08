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
from typing import Sequence


class SpectralConv3D(nn.Module):
    """3D spectral convolution layer in Fourier space."""

    in_channels: int
    out_channels: int
    modes: int = 8  # number of Fourier modes to keep per dimension

    @nn.compact
    def __call__(self, x: jnp.ndarray) -> jnp.ndarray:
        # x: [B, Lx, Ly, Lz, C_in]
        B, Lx, Ly, Lz, _ = x.shape
        m = self.modes

        # Complex weight tensor for kept modes
        # Shape: [C_in, C_out, modes, modes, modes//2+1]  (rFFT halves last dim)
        scale = 1.0 / (self.in_channels * self.out_channels)
        weights_r = self.param(
            "weights_r",
            nn.initializers.normal(stddev=scale),
            (self.in_channels, self.out_channels, m, m, m),
        )
        weights_i = self.param(
            "weights_i",
            nn.initializers.normal(stddev=scale),
            (self.in_channels, self.out_channels, m, m, m),
        )
        weights = weights_r + 1j * weights_i

        # Forward FFT (real-to-complex, last axis)
        x_ft = jnp.fft.rfftn(x, axes=(1, 2, 3))  # [B, Lx, Ly, Lz//2+1, C]

        # Truncate to kept modes
        x_modes = x_ft[:, :m, :m, :m, :]  # [B, m, m, m, C_in]

        # Complex multiply: contract input channels, element-wise on spatial modes
        # x_modes: [B, m, m, m, C_in]   weights: [C_in, C_out, m, m, m]
        # → [B, m, m, m, C_out]
        out_modes = jnp.einsum("bxyzi,ioxyz->bxyzo", x_modes, weights)

        # Pad back to full frequency grid
        out_ft = jnp.zeros((B, Lx, Ly, Lz // 2 + 1, self.out_channels), dtype=jnp.complex64)
        out_ft = out_ft.at[:, :m, :m, :m, :].set(out_modes)

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

        # Projection: hidden → 1
        h = nn.Dense(128)(h)
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

        # ── Stress head (6 channels) ──
        s = nn.Dense(64)(h)
        s = nn.gelu(s)
        stress = nn.Dense(6)(s)  # [B,L,L,L,6]

        # ── Displacement head (3 channels) ──
        d = nn.Dense(64)(h)
        d = nn.gelu(d)
        disp = nn.Dense(3)(d)  # [B,L,L,L,3]

        # ── Phi head (1 channel, PFSF-compatible) ──
        p = nn.Dense(64)(h)
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


def prepare_input(source: jnp.ndarray, conductivity: jnp.ndarray,
                  voxel_type: jnp.ndarray, rcomp: jnp.ndarray) -> jnp.ndarray:
    """Stack physics fields into FNO input tensor.

    Args:
        source:       [B, Lx, Ly, Lz]
        conductivity: [B, 6, Lx, Ly, Lz]
        voxel_type:   [B, Lx, Ly, Lz]
        rcomp:        [B, Lx, Ly, Lz]
    Returns:
        x: [B, Lx, Ly, Lz, 9]
    """
    B, Lx, Ly, Lz = source.shape

    # Normalize inputs
    src_norm = source / (source.max() + 1e-8)
    cond_norm = conductivity / (conductivity.max() + 1e-8)
    type_norm = voxel_type.astype(jnp.float32) / 2.0
    rc_norm = rcomp / (rcomp.max() + 1e-8)

    # Rearrange conductivity: [B, 6, Lx, Ly, Lz] → [B, Lx, Ly, Lz, 6]
    cond_t = jnp.transpose(cond_norm, (0, 2, 3, 4, 1))

    # Stack: [source, cond×6, type, rcomp] = 9 channels
    return jnp.concatenate([
        src_norm[..., None],   # [B, Lx, Ly, Lz, 1]
        cond_t,                 # [B, Lx, Ly, Lz, 6]
        type_norm[..., None],  # [B, Lx, Ly, Lz, 1]
        rc_norm[..., None],    # [B, Lx, Ly, Lz, 1]
    ], axis=-1)
