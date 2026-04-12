"""Loss functions for CMFD training."""
from __future__ import annotations

import jax
import jax.numpy as jnp


def freq_align_loss(pred: jnp.ndarray, target: jnp.ndarray,
                    mask: jnp.ndarray, band: str = "low") -> jnp.ndarray:
    """Spectral-domain distillation loss.

    Args:
        pred:   [B, L, L, L, C]
        target: [B, L, L, L, C]
        mask:   [B, L, L, L]
        band:   "low" | "midhigh" | "all"
    """
    # FFT per channel
    F_pred = jnp.fft.rfftn(pred, axes=(1, 2, 3))
    F_targ = jnp.fft.rfftn(target, axes=(1, 2, 3))

    Lx, Ly, Lz = pred.shape[1], pred.shape[2], pred.shape[3]
    mx, my, mz = F_pred.shape[1], F_pred.shape[2], F_pred.shape[3]

    if band == "low":
        # Keep center 1/3 of modes
        cx, cy, cz = max(1, mx // 3), max(1, my // 3), max(1, mz // 3)
        F_pred = F_pred[:, :cx, :cy, :cz, :]
        F_targ = F_targ[:, :cx, :cy, :cz, :]
    elif band == "midhigh":
        cx, cy, cz = max(1, mx // 3), max(1, my // 3), max(1, mz // 3)
        F_pred = F_pred[:, cx:, cy:, cz:, :]
        F_targ = F_targ[:, cx:, cy:, cz:, :]
    # "all" uses full spectrum

    diff = jnp.abs(F_pred - F_targ)
    return jnp.mean(diff ** 2)


def physics_residual_loss(
    stress: jnp.ndarray,
    disp: jnp.ndarray,
    E_field: jnp.ndarray,
    nu_field: jnp.ndarray,
    mask: jnp.ndarray,
    rho_field: jnp.ndarray | None = None,
    g: float = 9.81,
) -> jnp.ndarray:
    """Weak-form physics residual for linear elasticity.

    Args:
        stress: [B, L, L, L, 6] Voigt
        disp:   [B, L, L, L, 3]
        E_field: [B, L, L, L] Young's modulus (Pa)
        nu_field: [B, L, L, L] Poisson ratio
        mask:   [B, L, L, L]
        rho_field: optional density for body-force residual
        g: gravity
    """
    # ── Equilibrium: div(sigma) + rho*g ≈ 0 ──
    # Central differences for divergence
    def _roll_diff(f, axis):
        return (jnp.roll(f, -1, axis) - jnp.roll(f, 1, axis)) / 2.0

    sxx, syy, szz = stress[..., 0], stress[..., 1], stress[..., 2]
    txy, tyz, txz = stress[..., 3], stress[..., 4], stress[..., 5]

    # div_sigma_x = d(sxx)/dx + d(txy)/dy + d(txz)/dz
    div_x = _roll_diff(sxx, 1) + _roll_diff(txy, 2) + _roll_diff(txz, 3)
    div_y = _roll_diff(txy, 1) + _roll_diff(syy, 2) + _roll_diff(tyz, 3)
    div_z = _roll_diff(txz, 1) + _roll_diff(tyz, 2) + _roll_diff(szz, 3)

    if rho_field is not None:
        body_y = rho_field * g
        div_y = div_y + body_y

    div_mag = div_x ** 2 + div_y ** 2 + div_z ** 2
    loss_eq = jnp.sum(div_mag * mask) / (jnp.sum(mask) + 1e-8)

    # ── Compatibility: strain from disp ≈ strain from stress ──
    ux, uy, uz = disp[..., 0], disp[..., 1], disp[..., 2]

    exx = _roll_diff(ux, 1)
    eyy = _roll_diff(uy, 2)
    ezz = _roll_diff(uz, 3)
    gxy = _roll_diff(ux, 2) + _roll_diff(uy, 1)
    gyz = _roll_diff(uy, 3) + _roll_diff(uz, 2)
    gxz = _roll_diff(ux, 3) + _roll_diff(uz, 1)

    # Isotropic compliance: epsilon = (1/E) * [(1+nu)*sigma - nu*trace(sigma)*I]
    # Avoid division by zero in air voxels (masked out later)
    E = jnp.where(E_field > 0, E_field, 1.0)
    nu = nu_field
    trace = sxx + syy + szz

    exx_s = (sxx - nu * trace) / E
    eyy_s = (syy - nu * trace) / E
    ezz_s = (szz - nu * trace) / E
    gxy_s = (1 + nu) * txy / E
    gyz_s = (1 + nu) * tyz / E
    gxz_s = (1 + nu) * txz / E

    comp_err = (
        (exx - exx_s) ** 2 +
        (eyy - eyy_s) ** 2 +
        (ezz - ezz_s) ** 2 +
        (gxy - gxy_s) ** 2 +
        (gyz - gyz_s) ** 2 +
        (gxz - gxz_s) ** 2
    )
    loss_comp = jnp.sum(comp_err * mask) / (jnp.sum(mask) + 1e-8)

    return loss_eq + loss_comp


def huber_loss(pred, target, delta=1.0):
    diff = jnp.abs(pred - target)
    return jnp.where(diff < delta, 0.5 * diff ** 2, delta * (diff - 0.5 * delta))


def hybrid_task_loss(
    pred_stress, pred_disp, pred_phi,
    target_stress, target_disp, target_phi,
    mask, fem_trust,
) -> dict:
    """Per-task losses for CMFD Stage 3."""
    mask = mask[..., None]
    ft3 = fem_trust[..., None]
    n_trust = jnp.sum(fem_trust) + 1e-8
    n_mask = jnp.sum(mask) + 1e-8

    loss_stress = jnp.sum(huber_loss(pred_stress, target_stress) * ft3) / (n_trust * 6.0)
    loss_disp = jnp.sum(huber_loss(pred_disp, target_disp) * ft3) / (n_trust * 3.0)
    loss_phi = jnp.sum(huber_loss(pred_phi.squeeze(-1), target_phi) * mask.squeeze(-1)) / n_mask

    # Consistency: von Mises from stress ≈ phi
    vm_pred = jnp.sqrt(
        jnp.maximum(
            pred_stress[..., 0] ** 2 + pred_stress[..., 1] ** 2 + pred_stress[..., 2] ** 2
            - pred_stress[..., 0] * pred_stress[..., 1]
            - pred_stress[..., 1] * pred_stress[..., 2]
            - pred_stress[..., 0] * pred_stress[..., 2]
            + 3.0 * (
                pred_stress[..., 3] ** 2
                + pred_stress[..., 4] ** 2
                + pred_stress[..., 5] ** 2
            ),
            1e-8,
        )
    )
    loss_cons = jnp.sum((vm_pred - pred_phi.squeeze(-1)) ** 2 * mask.squeeze(-1)) / n_mask

    return {
        "stress": loss_stress,
        "disp": loss_disp,
        "phi": loss_phi,
        "consistency": loss_cons,
    }
