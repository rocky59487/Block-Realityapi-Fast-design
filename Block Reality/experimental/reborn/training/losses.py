"""
losses.py — Reborn v2 風格條件化損失函數

包含：
  1. BR-NeXT 損失函數（轉匯出）：
     - freq_align_loss      頻譜對齊損失
     - physics_residual_loss 物理殘差損失
     - hybrid_task_loss      混合任務損失（應力/位移/φ/一致性）
     - huber_loss            Huber 損失

  2. HYBR 穩定性工具（轉匯出）：
     - spectral_norm_penalty 頻譜範數正則化

  3. Reborn 專用損失函數（新增）：
     - style_consistency_loss  風格一致性損失（遮罩 L1）
     - spectral_style_fid      頻譜風格 FID（FFT 幅度空間 Fréchet 距離）
     - adversarial_loss        對抗損失（hinge 模式）
     - compliance_ratio        體積加權 von Mises 柔度
     - reborn_total_loss       7 任務不確定性加權總損失

所有新增函數均為 JAX 純函數，支援 jit 編譯。

參考：
  Kendall et al. (2018), "Multi-Task Learning Using Uncertainty to Weigh Losses"
  Miyato et al. (2018), "Spectral Normalization for Generative Adversarial Networks"
  Ambati et al. (2015), "A review on phase-field models of brittle fracture"
"""
from __future__ import annotations

import sys
from pathlib import Path

# ---------------------------------------------------------------------------
# 設定依賴套件路徑（HYBR / BR-NeXT / brml）
# ---------------------------------------------------------------------------
_REPO_ROOT = Path(__file__).resolve().parent.parent.parent.parent.parent
for _pkg in ("ml/HYBR", "ml/BR-NeXT", "ml/brml"):
    _p = str(_REPO_ROOT / _pkg)
    if _p not in sys.path:
        sys.path.insert(0, _p)

import jax
import jax.numpy as jnp

# ---------------------------------------------------------------------------
# 轉匯出 BR-NeXT 損失函數
# ---------------------------------------------------------------------------
from brnext.models.losses import (
    freq_align_loss,
    physics_residual_loss,
    hybrid_task_loss,
    huber_loss,
)

# ---------------------------------------------------------------------------
# 轉匯出 HYBR 穩定性工具
# ---------------------------------------------------------------------------
from hybr.training.stability_utils import spectral_norm_penalty


# ═══════════════════════════════════════════════════════════════════════════
# Reborn 專用損失函數
# ═══════════════════════════════════════════════════════════════════════════


@jax.jit
def style_consistency_loss(
    style_sdf_pred: jnp.ndarray,
    style_sdf_teacher: jnp.ndarray,
    mask: jnp.ndarray,
) -> jnp.ndarray:
    """
    風格一致性損失 — 預測 SDF 與教師 SDF 之間的遮罩 L1 距離。

    用於第二階段（風格蒸餾）：將解析風格模型（GaudiStyle / ZahaStyle）的
    SDF 輸出作為教師信號，監督神經網路的 SDF 通道。

    Args:
        style_sdf_pred:    float32[B, L, L, L] — 模型預測的風格 SDF
        style_sdf_teacher: float32[B, L, L, L] — 教師（解析模型）的風格 SDF
        mask:              float32[B, L, L, L] — 有效區域遮罩（1=有效, 0=忽略）

    Returns:
        scalar — 遮罩 L1 損失
    """
    diff = jnp.abs(style_sdf_pred - style_sdf_teacher)
    # 只計算遮罩內的體素
    return jnp.sum(diff * mask) / (jnp.sum(mask) + 1e-8)


@jax.jit
def spectral_style_fid(
    pred_sdf: jnp.ndarray,
    teacher_sdf: jnp.ndarray,
) -> jnp.ndarray:
    """
    頻譜風格 FID — FFT 幅度空間中的 Fréchet 距離。

    衡量預測 SDF 與教師 SDF 在頻率域中的分佈差異，
    對高頻細節（風格紋理）和低頻結構（全局形態）同時敏感。

    計算方法：
      F_pred = |rfftn(pred)|,  F_teacher = |rfftn(teacher)|
      mu_p, mu_t = 各自均值
      var_p, var_t = 各自方差
      FID = mean((mu_p - mu_t)^2) + mean((sqrt(var_p) - sqrt(var_t))^2)

    Args:
        pred_sdf:    float32[B, L, L, L] — 模型預測的風格 SDF
        teacher_sdf: float32[B, L, L, L] — 教師的風格 SDF

    Returns:
        scalar — 頻譜 Fréchet 距離
    """
    # 3D 實數 FFT，取幅度
    F_pred = jnp.abs(jnp.fft.rfftn(pred_sdf, axes=(1, 2, 3)))
    F_teacher = jnp.abs(jnp.fft.rfftn(teacher_sdf, axes=(1, 2, 3)))

    # 沿 batch 維度計算統計量
    mu_p = jnp.mean(F_pred, axis=0)
    mu_t = jnp.mean(F_teacher, axis=0)
    var_p = jnp.var(F_pred, axis=0)
    var_t = jnp.var(F_teacher, axis=0)

    # Fréchet 距離（高斯近似）
    mean_diff = jnp.mean((mu_p - mu_t) ** 2)
    std_diff = jnp.mean((jnp.sqrt(var_p + 1e-8) - jnp.sqrt(var_t + 1e-8)) ** 2)

    return mean_diff + std_diff


@jax.jit
def adversarial_loss(
    disc_real: jnp.ndarray,
    disc_fake: jnp.ndarray,
    mode: str = "hinge",
) -> tuple[jnp.ndarray, jnp.ndarray]:
    """
    對抗損失（GAN）— 用於第四階段對抗精修。

    支援 hinge 模式（Miyato et al. 2018）：
      D_loss = E[relu(1 - real)] + E[relu(1 + fake)]
      G_loss = -E[fake]

    Args:
        disc_real: float32[B, ...] — 判別器對真實樣本的輸出
        disc_fake: float32[B, ...] — 判別器對假樣本的輸出
        mode:      損失模式（目前僅支援 "hinge"）

    Returns:
        (d_loss, g_loss) — 判別器損失和生成器損失
    """
    # Hinge 損失
    d_loss = (
        jnp.mean(jax.nn.relu(1.0 - disc_real))
        + jnp.mean(jax.nn.relu(1.0 + disc_fake))
    )
    g_loss = -jnp.mean(disc_fake)
    return d_loss, g_loss


@jax.jit
def compliance_ratio(
    density: jnp.ndarray,
    stress_voigt: jnp.ndarray,
    target_vf: float,
) -> jnp.ndarray:
    """
    體積加權 von Mises 柔度比 — 拓撲最佳化品質指標。

    衡量在給定目標體積分率下，結構的應力分佈效率。
    理想結構的 von Mises 應力在所有體素上均勻分佈。

    計算方法：
      vm = sqrt(σ_xx² + σ_yy² + σ_zz² - σ_xx·σ_yy - σ_yy·σ_zz - σ_xx·σ_zz
                + 3(τ_xy² + τ_yz² + τ_xz²))
      compliance = sum(density * vm) / (sum(density) + eps)
      ratio = compliance / target_vf

    Args:
        density:      float32[B, L, L, L] — 材料密度場 ∈ [0, 1]
        stress_voigt: float32[B, L, L, L, 6] — Voigt 應力 [σxx, σyy, σzz, τxy, τyz, τxz]
        target_vf:    目標體積分率

    Returns:
        scalar — 柔度比
    """
    sxx = stress_voigt[..., 0]
    syy = stress_voigt[..., 1]
    szz = stress_voigt[..., 2]
    txy = stress_voigt[..., 3]
    tyz = stress_voigt[..., 4]
    txz = stress_voigt[..., 5]

    # von Mises 等效應力
    vm = jnp.sqrt(
        jnp.maximum(
            sxx ** 2 + syy ** 2 + szz ** 2
            - sxx * syy - syy * szz - sxx * szz
            + 3.0 * (txy ** 2 + tyz ** 2 + txz ** 2),
            1e-8,
        )
    )

    # 體積加權柔度
    weighted_compliance = jnp.sum(density * vm)
    total_density = jnp.sum(density) + 1e-8
    compliance = weighted_compliance / total_density

    return compliance / (target_vf + 1e-8)


def reborn_total_loss(
    pred: jnp.ndarray,
    target: jnp.ndarray,
    mask: jnp.ndarray,
    style_sdf_pred: jnp.ndarray,
    style_sdf_teacher: jnp.ndarray,
    E: jnp.ndarray,
    nu: jnp.ndarray,
    rho: jnp.ndarray,
    fem_trust: jnp.ndarray,
    log_sigma: jnp.ndarray,
    disc_fake: jnp.ndarray | None = None,
    adv_weight: float = 0.0,
) -> tuple[jnp.ndarray, dict]:
    """
    Reborn 7 任務不確定性加權總損失。

    採用 Kendall et al. (2018) 的同質不確定性加權：
      L_total = Σ_i  L_i * exp(-2·σ_i) / 2  +  σ_i
    其中 σ_i = log_sigma[i] 為可學習的對數標準差。

    7 個任務：
      0. stress     — 應力 Huber 損失（hybrid_task_loss）
      1. disp       — 位移 Huber 損失（hybrid_task_loss）
      2. phi        — 勢場 Huber 損失（hybrid_task_loss）
      3. consistency — von Mises 一致性（hybrid_task_loss）
      4. physics    — 物理殘差損失（equilibrium + compatibility）
      5. style      — 風格 SDF 一致性（遮罩 L1）
      6. spectral   — 頻譜風格 FID

    可選第 8 個任務（不走不確定性加權，直接加權）：
      - adversarial — 對抗生成器損失

    Args:
        pred:               float32[B, L, L, L, 11] — 模型預測 (σ6+u3+φ1+style_sdf1)
        target:             float32[B, L, L, L, 10] — FEM 教師目標
        mask:               float32[B, L, L, L]     — 有效區域遮罩
        style_sdf_pred:     float32[B, L, L, L]     — 模型預測風格 SDF
        style_sdf_teacher:  float32[B, L, L, L]     — 教師風格 SDF
        E:                  float32[B, L, L, L]     — 楊氏模量場 (Pa)
        nu:                 float32[B, L, L, L]     — 泊松比場
        rho:                float32[B, L, L, L]     — 密度場 (kg/m³)
        fem_trust:          float32[B, L, L, L]     — FEM 可信度
        log_sigma:          float32[7]              — 7 個可學習的對數不確定性
        disc_fake:          可選 — 判別器對假樣本的輸出（用於對抗損失）
        adv_weight:         對抗損失權重（0.0 = 關閉）

    Returns:
        (total_loss, metrics_dict) — 總損失和各任務損失字典
    """
    # ── 分拆預測通道 ──
    pred_stress = pred[..., :6]      # [B, L, L, L, 6]
    pred_disp = pred[..., 6:9]       # [B, L, L, L, 3]
    pred_phi = pred[..., 9:10]       # [B, L, L, L, 1]

    target_stress = target[..., :6]
    target_disp = target[..., 6:9]
    target_phi = target[..., 9]      # [B, L, L, L]

    # ── 任務 0–3：hybrid_task_loss（應力/位移/φ/一致性）──
    htl = hybrid_task_loss(
        pred_stress, pred_disp, pred_phi,
        target_stress, target_disp, target_phi,
        mask, fem_trust,
    )

    # ── 任務 4：物理殘差損失 ──
    l_physics = physics_residual_loss(
        pred_stress, pred_disp[..., :3],
        E, nu, mask, rho_field=rho,
    )

    # ── 任務 5：風格 SDF 一致性 ──
    l_style = style_consistency_loss(style_sdf_pred, style_sdf_teacher, mask)

    # ── 任務 6：頻譜風格 FID ──
    l_spectral = spectral_style_fid(style_sdf_pred, style_sdf_teacher)

    # ── 收集 7 個任務損失 ──
    task_losses = jnp.array([
        htl["stress"],       # 0
        htl["disp"],         # 1
        htl["phi"],          # 2
        htl["consistency"],  # 3
        l_physics,           # 4
        l_style,             # 5
        l_spectral,          # 6
    ])

    # ── 不確定性加權：L_total = Σ L_i * exp(-2σ_i)/2 + σ_i ──
    precision = jnp.exp(-2.0 * log_sigma)
    weighted = task_losses * precision * 0.5 + log_sigma
    total = jnp.sum(weighted)

    # ── 可選對抗損失（不走不確定性加權）──
    l_adv = jnp.float32(0.0)
    if disc_fake is not None and adv_weight > 0.0:
        l_adv = -jnp.mean(disc_fake)
        total = total + adv_weight * l_adv

    # ── 組裝指標字典 ──
    metrics = {
        "loss/stress": task_losses[0],
        "loss/disp": task_losses[1],
        "loss/phi": task_losses[2],
        "loss/consistency": task_losses[3],
        "loss/physics": task_losses[4],
        "loss/style": task_losses[5],
        "loss/spectral": task_losses[6],
        "loss/adversarial": l_adv,
        "loss/total": total,
        # 不確定性監控
        "sigma/stress": jnp.exp(log_sigma[0]),
        "sigma/disp": jnp.exp(log_sigma[1]),
        "sigma/phi": jnp.exp(log_sigma[2]),
        "sigma/consistency": jnp.exp(log_sigma[3]),
        "sigma/physics": jnp.exp(log_sigma[4]),
        "sigma/style": jnp.exp(log_sigma[5]),
        "sigma/spectral": jnp.exp(log_sigma[6]),
    }

    return total, metrics
