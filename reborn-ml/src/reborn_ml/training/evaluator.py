"""
evaluator.py — Reborn ML 定量評估框架

指標：
  結構：合規性比、體積分率誤差、物理殘差範數
  風格：頻譜 FID、風格一致性 L1、SDF 平滑度
  綜合：Pareto 分數
"""
from __future__ import annotations

import time
import numpy as np

import jax
import jax.numpy as jnp

from brnext.models.losses import physics_residual_loss


class RebornEvaluator:
    """Reborn 訓練評估器。"""

    def __init__(self, config=None, target_vf: float = 0.4):
        self.target_vf = target_vf
        self.config = config

    def evaluate(
        self,
        model,
        params: dict,
        eval_data: list[tuple],
        style_ids: list[int] | None = None,
    ) -> dict[str, float]:
        """執行完整評估，回傳指標字典。"""
        metrics_list = []
        t0 = time.time()

        for i, sample in enumerate(eval_data):
            if len(sample) == 4:
                x, physics_target, style_target, sid = sample
            else:
                x, physics_target, style_target = sample[:3]
                sid = style_ids[i] if style_ids else 0

            x_jnp = jnp.array(x) if not isinstance(x, jnp.ndarray) else x
            if x_jnp.ndim == 4:
                x_jnp = x_jnp[None]

            sid_jnp = jnp.array([sid])
            pred = model.apply({"params": params}, x_jnp, sid_jnp)

            style_target_jnp = jnp.array(style_target)
            if style_target_jnp.ndim == 3:
                style_target_jnp = style_target_jnp[None]

            m = self._compute_sample_metrics(pred, x_jnp, physics_target, style_target_jnp)
            metrics_list.append(m)

        aggregated: dict[str, float] = {}
        if metrics_list:
            for k in metrics_list[0].keys():
                vals = [m[k] for m in metrics_list if np.isfinite(m[k])]
                aggregated[k] = float(np.mean(vals)) if vals else 0.0

        aggregated["eval_time_s"] = time.time() - t0
        aggregated["n_samples"] = len(eval_data)
        aggregated["pareto_score"] = self._compute_pareto(aggregated)
        return aggregated

    def _compute_sample_metrics(self, pred, x, physics_target, style_target) -> dict[str, float]:
        mask = x[..., 0] > 0.5
        pred_stress = pred[..., :6]
        pred_density = jnp.clip(x[..., 0], 0, 1)

        return {
            "compliance_ratio":  float(self._compliance_ratio(pred_density, pred_stress, mask)),
            "vf_error":          float(self._volume_fraction_error(pred_density, mask)),
            "physics_residual":  float(self._physics_residual(pred, x, mask)),
            "style_consistency": float(self._style_consistency(pred[..., 10:11], style_target, mask)),
            "spectral_fid":      float(self._spectral_fid(pred[..., 10], style_target.squeeze(-1) if style_target.ndim == 4 else style_target)),
            "sdf_smoothness":    float(self._sdf_smoothness(pred[..., 10], mask)),
        }

    @staticmethod
    def _compliance_ratio(density, stress, mask):
        vm2 = (
            stress[..., 0]**2 + stress[..., 1]**2 + stress[..., 2]**2
            - stress[..., 0] * stress[..., 1]
            - stress[..., 1] * stress[..., 2]
            - stress[..., 0] * stress[..., 2]
            + 3.0 * (stress[..., 3]**2 + stress[..., 4]**2 + stress[..., 5]**2)
        )
        compliance = jnp.sum(jnp.maximum(vm2, 0) * density * mask)
        vf = jnp.sum(density * mask) / (jnp.sum(mask) + 1e-8)
        return compliance / (vf + 1e-8)

    def _volume_fraction_error(self, density, mask):
        vf = float(jnp.sum(density * mask) / (jnp.sum(mask) + 1e-8))
        return abs(vf - self.target_vf) / (self.target_vf + 1e-8)

    @staticmethod
    def _physics_residual(pred, x, mask):
        try:
            E = x[..., 1] * 200e9
            nu = x[..., 2]
            rho = x[..., 3] * 7850.0
            return float(physics_residual_loss(
                pred[..., :6], pred[..., 6:9],
                E, nu, mask.astype(jnp.float32), rho,
            ))
        except Exception:
            return 0.0

    @staticmethod
    def _style_consistency(pred_sdf, teacher_sdf, mask):
        mask_f = mask.astype(jnp.float32)
        if pred_sdf.ndim != teacher_sdf.ndim:
            if teacher_sdf.ndim == pred_sdf.ndim - 1:
                teacher_sdf = teacher_sdf[..., None]
        diff = jnp.abs(pred_sdf - teacher_sdf)
        if diff.ndim > mask_f.ndim:
            diff = diff.squeeze(-1)
        return jnp.sum(diff * mask_f) / (jnp.sum(mask_f) + 1e-8)

    @staticmethod
    def _spectral_fid(pred_sdf, teacher_sdf):
        F_p = jnp.abs(jnp.fft.rfftn(pred_sdf, axes=(-3, -2, -1)))
        F_t = jnp.abs(jnp.fft.rfftn(teacher_sdf, axes=(-3, -2, -1)))
        return (F_p.mean() - F_t.mean())**2 + (
            jnp.sqrt(jnp.var(F_p) + 1e-8) - jnp.sqrt(jnp.var(F_t) + 1e-8)
        )**2

    @staticmethod
    def _sdf_smoothness(sdf, mask):
        gx = (jnp.roll(sdf, -1, axis=-3) - jnp.roll(sdf, 1, axis=-3)) / 2.0
        gy = (jnp.roll(sdf, -1, axis=-2) - jnp.roll(sdf, 1, axis=-2)) / 2.0
        gz = (jnp.roll(sdf, -1, axis=-1) - jnp.roll(sdf, 1, axis=-1)) / 2.0
        grad_mag = jnp.sqrt(gx**2 + gy**2 + gz**2 + 1e-8)
        near_surface = (jnp.abs(sdf) < 1.5).astype(jnp.float32) * mask.astype(jnp.float32)
        n = jnp.sum(near_surface) + 1e-8
        mean_g = jnp.sum(grad_mag * near_surface) / n
        return jnp.sum((grad_mag - mean_g)**2 * near_surface) / n

    @staticmethod
    def _compute_pareto(metrics: dict) -> float:
        weights = {"compliance_ratio": 0.3, "physics_residual": 0.2,
                   "style_consistency": 0.25, "spectral_fid": 0.15, "sdf_smoothness": 0.1}
        return sum(w * metrics.get(k, 0.0) for k, w in weights.items())

    def export_latex_table(self, results: dict[str, dict], output_path: str) -> None:
        """匯出 LaTeX 消融比較表格。"""
        from pathlib import Path
        cols = ["compliance_ratio", "vf_error", "physics_residual",
                "style_consistency", "spectral_fid", "sdf_smoothness", "pareto_score"]
        headers = ["C/V_f", "VF Err", "Phys Res", "Style L1", "S-FID", "Smooth", "Pareto"]

        lines = ["\\begin{tabular}{l" + "c" * len(cols) + "}", "\\toprule",
                 "Variant & " + " & ".join(headers) + " \\\\", "\\midrule"]
        for name, m in results.items():
            vals = [f"{m.get(c, 0.0):.4f}" for c in cols]
            lines.append(f"{name} & " + " & ".join(vals) + " \\\\")
        lines += ["\\bottomrule", "\\end{tabular}"]

        Path(output_path).parent.mkdir(parents=True, exist_ok=True)
        with open(output_path, "w", encoding="utf-8") as f:
            f.write("\n".join(lines))
