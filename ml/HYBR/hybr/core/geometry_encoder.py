"""Geometry encoders: occupancy grid -> latent code z."""
from __future__ import annotations

import jax
import jax.numpy as jnp
import flax.linen as nn


class SpectralGeometryEncoder(nn.Module):
    """Zero-parameter encoder using 3D FFT low-frequency energy statistics.

    Computes the log-magnitude of low-frequency FFT modes and pools them
    into a compact latent vector.  This is structurally aligned with PFSF
    because it operates in the same Fourier space as the spectral convolutions.
    """

    latent_dim: int = 32
    n_bands: int = 4  # how many radial frequency bands to aggregate

    @nn.compact
    def __call__(self, occupancy: jnp.ndarray) -> jnp.ndarray:
        """
        Args:
            occupancy: [B, L, L, L] bool/float mask
        Returns:
            z: [B, latent_dim]
        """
        # Normalize input to float32
        occ = occupancy.astype(jnp.float32)
        B, L, _, _ = occ.shape

        # 3D real FFT -> [B, L, L, L//2+1] complex
        ft = jnp.fft.rfftn(occ, axes=(1, 2, 3))
        mag = jnp.log1p(jnp.abs(ft))  # compress dynamic range

        # Flatten spatial dims for band-wise pooling
        mag_flat = mag.reshape(B, -1)

        # Global statistics (shape independent of L)
        stats = jnp.concatenate([
            mag_flat.mean(axis=1, keepdims=True),
            mag_flat.std(axis=1, keepdims=True),
            jnp.percentile(mag_flat, jnp.array([25., 50., 75.]), axis=1).T,
        ], axis=1)  # [B, 5]

        # Radial band pooling: divide low-frequency cube into shells
        # Build coordinate grids for frequency magnitude
        fx = jnp.fft.fftfreq(L).reshape(L, 1, 1)
        fy = jnp.fft.fftfreq(L).reshape(1, L, 1)
        fz = jnp.fft.rfftfreq(L).reshape(1, 1, -1)
        freq_mag = jnp.sqrt(fx**2 + fy**2 + fz**2)  # [L, L, L//2+1]

        band_features = []
        band_edges = jnp.linspace(0.0, freq_mag.max(), self.n_bands + 1)
        for i in range(self.n_bands):
            mask = (freq_mag >= band_edges[i]) & (freq_mag < band_edges[i + 1])
            mask = mask.reshape(1, -1)
            band_vals = mag_flat * mask
            band_sum = band_vals.sum(axis=1, keepdims=True)
            band_mean = band_sum / (mask.sum() + 1e-8)
            band_features.append(band_mean)
        band_features = jnp.concatenate(band_features, axis=1)  # [B, n_bands]

        combined = jnp.concatenate([stats, band_features], axis=1)  # [B, 5+n_bands]

        # Project to latent_dim with a small MLP
        h = nn.Dense(self.latent_dim * 2)(combined)
        h = nn.gelu(h)
        z = nn.Dense(self.latent_dim)(h)
        return z


class LightweightCNNEncoder(nn.Module):
    """Tiny 3D CNN encoder (<10k params) for occupancy grids."""

    latent_dim: int = 32

    @nn.compact
    def __call__(self, occupancy: jnp.ndarray) -> jnp.ndarray:
        occ = occupancy.astype(jnp.float32)[..., None]  # [B, L, L, L, 1]
        x = nn.Conv(8, kernel_size=(3, 3, 3))(occ)
        x = nn.relu(x)
        x = nn.avg_pool(x, window_shape=(2, 2, 2), strides=(2, 2, 2), padding='SAME')
        x = nn.Conv(16, kernel_size=(3, 3, 3))(x)
        x = nn.relu(x)
        x = nn.avg_pool(x, window_shape=(2, 2, 2), strides=(2, 2, 2), padding='SAME')
        # Global average pooling over spatial dims
        x = x.mean(axis=(1, 2, 3))  # [B, 16]
        z = nn.Dense(self.latent_dim)(x)
        return z
