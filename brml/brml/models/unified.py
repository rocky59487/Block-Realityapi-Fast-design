"""
PFSF-native Fourier Neural Operator — learns to skip PFSF iterations.

Architecture: Li et al. 2021 FNO, hardened for production:
  - LayerNorm after each spectral block (numerical stability)
  - Huber loss instead of MSE (outlier resistance)
  - 1-channel output: φ field (PFSF-native, zero translation)

Input:  [B, L, L, L, C_in]  geometry + material features
Output: [B, L, L, L, 1]     predicted converged φ field

Training data comes FROM PFSF itself (not FEM):
  - Every PFSF solve produces (input, φ_converged) pairs for free
  - Online learning: collect while playing, train in background
"""
from __future__ import annotations

import jax
import jax.numpy as jnp
import flax.linen as nn


class SpectralConv3D(nn.Module):
    """3D spectral convolution — frequency-domain learned kernel."""

    out_channels: int
    modes: int = 8

    @nn.compact
    def __call__(self, x: jnp.ndarray) -> jnp.ndarray:
        B, Lx, Ly, Lz, C_in = x.shape
        C_out = self.out_channels

        # rFFT halves last axis
        mx = min(self.modes, Lx)
        my = min(self.modes, Ly)
        mz = min(self.modes, Lz // 2 + 1)

        scale = 1.0 / (C_in * C_out)
        wr = self.param("wr", nn.initializers.normal(stddev=scale), (C_in, C_out, mx, my, mz))
        wi = self.param("wi", nn.initializers.normal(stddev=scale), (C_in, C_out, mx, my, mz))
        W = wr + 1j * wi

        x_ft = jnp.fft.rfftn(x, axes=(1, 2, 3))
        x_modes = x_ft[:, :mx, :my, :mz, :]
        out_modes = jnp.einsum("bxyzi,ioxyz->bxyzo", x_modes, W)

        out_ft = jnp.zeros((B, Lx, Ly, Lz // 2 + 1, C_out), dtype=jnp.complex64)
        out_ft = out_ft.at[:, :mx, :my, :mz, :].set(out_modes)
        return jnp.fft.irfftn(out_ft, s=(Lx, Ly, Lz), axes=(1, 2, 3)).real


class FNOBlock(nn.Module):
    """Spectral conv + linear bypass + LayerNorm + GELU."""

    channels: int
    modes: int = 8

    @nn.compact
    def __call__(self, x: jnp.ndarray) -> jnp.ndarray:
        residual = x
        h = SpectralConv3D(self.channels, self.modes)(x) + nn.Dense(self.channels)(x)
        h = nn.LayerNorm()(h + residual)
        return nn.gelu(h)


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


class FluidSurrogate(nn.Module):
    """PFSF-Fluid surrogate — predicts next-step velocity + pressure.

    Input (8ch): velocity(3) + pressure(1) + boundary(1) + position(3)
    Output (4ch): velocity_next(3) + pressure_next(1)
    """

    hidden: int = 48
    layers: int = 4
    modes: int = 10

    @nn.compact
    def __call__(self, x: jnp.ndarray) -> jnp.ndarray:
        h = nn.Dense(self.hidden)(x)
        for _ in range(self.layers):
            h = FNOBlock(self.hidden, self.modes)(h)
        v = nn.Dense(3)(nn.gelu(nn.Dense(48)(h)))
        p = nn.Dense(1)(nn.gelu(nn.Dense(48)(h)))
        out = jnp.concatenate([v, p], axis=-1)
        return out * x[..., 4:5]  # mask by boundary


class CollapsePredictor(nn.Module):
    """MPNN collapse predictor — node-level failure classification.

    Input: node_features[N, 8], edge_index[2, E], edge_features[E, 4]
    Output: class_logits[N, 5], collapse_prob[N]
    """

    hidden: int = 128
    mp_steps: int = 6

    @nn.compact
    def __call__(self, node_feats, edge_index, edge_feats):
        N, _ = node_feats.shape
        H = self.hidden
        h = nn.Dense(H)(node_feats)

        src, dst = edge_index[0], edge_index[1]
        for _ in range(self.mp_steps):
            # Message
            msg_in = jnp.concatenate([h[src], h[dst], edge_feats], axis=-1)
            msg = nn.relu(nn.Dense(H)(msg_in))
            msg = nn.Dense(H)(msg)

            agg = jnp.zeros((N, H)).at[dst].add(msg)

            # GRU update
            gate_in = jnp.concatenate([h, agg], axis=-1)
            z = jax.nn.sigmoid(nn.Dense(H)(gate_in))
            r = jax.nn.sigmoid(nn.Dense(H)(gate_in))
            h_new = jnp.tanh(nn.Dense(H)(jnp.concatenate([r * h, agg], axis=-1)))
            h = nn.LayerNorm()((1 - z) * h + z * h_new)

        cls = nn.Dense(5)(nn.relu(nn.Dense(64)(h)))
        prob = jax.nn.sigmoid(nn.Dense(1)(nn.relu(nn.Dense(64)(h)))).squeeze(-1)
        return cls, prob


# ═══ Loss Functions (Huber-based for robustness) ═══

def huber_loss(pred, target, delta=1.0):
    """Huber loss — smooth L1, robust to outliers."""
    diff = jnp.abs(pred - target)
    return jnp.where(diff < delta, 0.5 * diff**2, delta * (diff - 0.5 * delta))


def surrogate_loss(pred, target, mask, delta=1.0):
    """Masked Huber loss for φ field prediction."""
    loss = huber_loss(pred.squeeze(-1), target, delta) * mask
    return jnp.sum(loss) / (jnp.sum(mask) + 1e-8)


def fluid_loss(pred, target, boundary, delta=1.0):
    """Velocity + pressure Huber + divergence penalty."""
    mask = boundary[..., 0]
    v_loss = jnp.sum(huber_loss(pred[..., :3], target[..., :3], delta) * mask[..., None]) / (jnp.sum(mask) * 3 + 1e-8)
    p_loss = jnp.sum(huber_loss(pred[..., 3], target[..., 3], delta) * mask) / (jnp.sum(mask) + 1e-8)

    # Divergence penalty (incompressibility)
    N = float(pred.shape[1])
    dx = 1.0 / N
    ux, uy, uz = pred[..., 0], pred[..., 1], pred[..., 2]
    div = ((jnp.roll(ux,-1,1)-jnp.roll(ux,1,1)) + (jnp.roll(uy,-1,2)-jnp.roll(uy,1,2)) +
           (jnp.roll(uz,-1,3)-jnp.roll(uz,1,3))) / (2*dx)
    div_loss = jnp.sum(div**2 * mask) / (jnp.sum(mask) + 1e-8)

    return 0.5 * v_loss + 0.3 * p_loss + 0.2 * div_loss


def collapse_loss(cls_logits, labels, prob):
    """Classification CE + regression Huber for collapse prediction."""
    ce = -jnp.sum(jax.nn.one_hot(labels, 5) * jax.nn.log_softmax(cls_logits), axis=-1)
    cls_loss = jnp.mean(ce)
    target_p = (labels > 0).astype(jnp.float32)
    p = jnp.clip(prob, 1e-7, 1 - 1e-7)
    bce = -jnp.mean(target_p * jnp.log(p) + (1 - target_p) * jnp.log(1 - p))
    return cls_loss + 0.5 * bce
