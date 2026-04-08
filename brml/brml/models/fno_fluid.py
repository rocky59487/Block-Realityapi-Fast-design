"""
FNO-Fluid — Fourier Neural Operator for water simulation at 0.1m resolution.

Learns Navier-Stokes dynamics from training data:
  Input:  velocity field u(t) + pressure p(t) + boundary mask
  Output: velocity field u(t+Δt) + pressure p(t+Δt)

Resolution: 0.1m per cell (10× finer than Minecraft's 1m voxels)
  → A single Minecraft block = 10×10×10 fluid cells

Architecture:
  - Shared FNO backbone (spectral convolution)
  - Velocity head: 3 channels (u_x, u_y, u_z)
  - Pressure head: 1 channel (p)
  - Boundary-aware: solid blocks mask out fluid cells

Compared to FluidGPUEngine (PFSF-Fluid):
  - PFSF-Fluid: 1m resolution, diffusion approximation, ~4ms/tick
  - FNO-Fluid: 0.1m resolution, learned N-S dynamics, ~2ms/tick (GPU inference)
"""
from __future__ import annotations

import jax
import jax.numpy as jnp
import flax.linen as nn


class SpectralConv3DFluid(nn.Module):
    """Spectral convolution for fluid dynamics — same as physics FNO but
    with different mode counts optimized for fluid turbulence."""

    in_channels: int
    out_channels: int
    modes: int = 12  # higher modes for turbulent flow

    @nn.compact
    def __call__(self, x: jnp.ndarray) -> jnp.ndarray:
        B, Lx, Ly, Lz, _ = x.shape
        mx = min(self.modes, Lx)
        my = min(self.modes, Ly)
        mz = min(self.modes, Lz // 2 + 1)  # rFFT halves last axis

        scale = 1.0 / (self.in_channels * self.out_channels)
        weights_r = self.param("wr", nn.initializers.normal(stddev=scale),
                               (self.in_channels, self.out_channels, mx, my, mz))
        weights_i = self.param("wi", nn.initializers.normal(stddev=scale),
                               (self.in_channels, self.out_channels, mx, my, mz))
        weights = weights_r + 1j * weights_i

        x_ft = jnp.fft.rfftn(x, axes=(1, 2, 3))
        x_modes = x_ft[:, :mx, :my, :mz, :]

        out_modes = jnp.einsum("bxyzi,ioxyz->bxyzo", x_modes, weights)

        out_ft = jnp.zeros((B, Lx, Ly, Lz // 2 + 1, self.out_channels), dtype=jnp.complex64)
        out_ft = out_ft.at[:, :mx, :my, :mz, :].set(out_modes)

        return jnp.fft.irfftn(out_ft, s=(Lx, Ly, Lz), axes=(1, 2, 3)).real


class FluidFNOBlock(nn.Module):
    channels: int
    modes: int = 12

    @nn.compact
    def __call__(self, x: jnp.ndarray) -> jnp.ndarray:
        x_spec = SpectralConv3DFluid(self.channels, self.channels, self.modes)(x)
        x_local = nn.Dense(self.channels)(x)
        return nn.gelu(x_spec + x_local)


class FNOFluid3D(nn.Module):
    """3D FNO for Navier-Stokes fluid simulation.

    Input channels (8):
      [0:3]  velocity u(t): u_x, u_y, u_z
      [3]    pressure p(t)
      [4]    boundary mask (1=fluid, 0=solid)
      [5:8]  gravity-relative position (x/L, y/L, z/L)

    Output channels (4):
      [0:3]  velocity u(t+Δt)
      [3]    pressure p(t+Δt)

    Resolution: operates at sub-block level.
    For a 3×3×3 block region at 0.1m → 30×30×30 fluid grid.
    """

    hidden_channels: int = 48
    num_layers: int = 4
    modes: int = 12
    in_channels: int = 8

    @nn.compact
    def __call__(self, x: jnp.ndarray) -> jnp.ndarray:
        """
        Args:
            x: [B, Nx, Ny, Nz, 8] — fluid state + boundary
        Returns:
            y: [B, Nx, Ny, Nz, 4] — next velocity + pressure
        """
        h = nn.Dense(self.hidden_channels)(x)

        for _ in range(self.num_layers):
            h = FluidFNOBlock(self.hidden_channels, self.modes)(h)

        # Velocity head (3ch)
        v = nn.Dense(64)(h)
        v = nn.gelu(v)
        velocity = nn.Dense(3)(v)

        # Pressure head (1ch)
        p = nn.Dense(64)(h)
        p = nn.gelu(p)
        pressure = nn.Dense(1)(p)

        output = jnp.concatenate([velocity, pressure], axis=-1)

        # Mask: zero out solid cells
        boundary = x[..., 4:5]  # [B, Nx, Ny, Nz, 1]
        return output * boundary


def fluid_loss(pred: jnp.ndarray, target: jnp.ndarray,
               boundary: jnp.ndarray) -> jnp.ndarray:
    """Loss for fluid simulation.

    Components:
      - Velocity MSE (channels 0-2)
      - Pressure MSE (channel 3)
      - Divergence penalty: ∇·u should be ≈ 0 (incompressibility)

    Args:
        pred:     [B, Nx, Ny, Nz, 4]
        target:   [B, Nx, Ny, Nz, 4]
        boundary: [B, Nx, Ny, Nz, 1]
    """
    mask = boundary[..., 0]  # [B, Nx, Ny, Nz]

    # Velocity loss
    v_pred = pred[..., :3]
    v_target = target[..., :3]
    v_loss = jnp.sum((v_pred - v_target)**2 * mask[..., None]) / (jnp.sum(mask) * 3 + 1e-8)

    # Pressure loss
    p_pred = pred[..., 3]
    p_target = target[..., 3]
    p_loss = jnp.sum((p_pred - p_target)**2 * mask) / (jnp.sum(mask) + 1e-8)

    # Divergence penalty: ∂u/∂x + ∂v/∂y + ∂w/∂z ≈ 0 (incompressibility)
    # Central difference: (f[i+1] - f[i-1]) / (2*dx), dx = 1/N (normalized grid)
    ux = v_pred[..., 0]
    uy = v_pred[..., 1]
    uz = v_pred[..., 2]
    N = float(ux.shape[1])  # grid cells per axis
    dx = 1.0 / N
    dudx = (jnp.roll(ux, -1, axis=1) - jnp.roll(ux, 1, axis=1)) / (2.0 * dx)
    dvdy = (jnp.roll(uy, -1, axis=2) - jnp.roll(uy, 1, axis=2)) / (2.0 * dx)
    dwdz = (jnp.roll(uz, -1, axis=3) - jnp.roll(uz, 1, axis=3)) / (2.0 * dx)
    div = dudx + dvdy + dwdz
    div_loss = jnp.sum(div**2 * mask) / (jnp.sum(mask) + 1e-8)

    return 0.5 * v_loss + 0.3 * p_loss + 0.2 * div_loss


def prepare_fluid_input(velocity: jnp.ndarray, pressure: jnp.ndarray,
                         boundary: jnp.ndarray, grid_shape: tuple) -> jnp.ndarray:
    """Build FNO-Fluid input tensor.

    Args:
        velocity: [B, Nx, Ny, Nz, 3]
        pressure: [B, Nx, Ny, Nz, 1]
        boundary: [B, Nx, Ny, Nz, 1]
        grid_shape: (Nx, Ny, Nz) for position encoding
    Returns:
        x: [B, Nx, Ny, Nz, 8]
    """
    Nx, Ny, Nz = grid_shape
    B = velocity.shape[0]

    # Normalized position encoding
    px = jnp.linspace(0, 1, Nx)[None, :, None, None].broadcast_to(B, Nx, Ny, Nz)
    py = jnp.linspace(0, 1, Ny)[None, None, :, None].broadcast_to(B, Nx, Ny, Nz)
    pz = jnp.linspace(0, 1, Nz)[None, None, None, :].broadcast_to(B, Nx, Ny, Nz)

    return jnp.concatenate([
        velocity,                              # [B, Nx, Ny, Nz, 3]
        pressure,                              # [B, Nx, Ny, Nz, 1]
        boundary,                              # [B, Nx, Ny, Nz, 1]
        px[..., None], py[..., None], pz[..., None],  # [B, Nx, Ny, Nz, 3]
    ], axis=-1)
