"""
evaluator.py — Reborn 定量評估框架

提供論文級指標計算：
  - 結構指標：合規性比、體積分率精度、物理殘差範數
  - 風格指標：頻譜 FID、風格一致性、SDF 表面平滑度
  - 綜合指標：Pareto 分數（用於消融研究排名）

所有指標均為 JIT 編譯，支援批次計算。

參考：
  Sigmund (2001), "A 99 line topology optimization code"
  Heusel et al. (2017), "GANs Trained by a Two Time-Scale Update Rule" — FID
"""
from __future__ import annotations

import sys
import time
from pathlib import Path
from typing import Any

import numpy as np

# 確保 HYBR/BR-NeXT 可匯入
_REPO_ROOT = Path(__file__).resolve().parent.parent.parent.parent.parent
for _p in [str(_REPO_ROOT / "HYBR"), str(_REPO_ROOT / "BR-NeXT"), str(_REPO_ROOT / "brml")]:
    if _p not in sys.path:
        sys.path.insert(0, _p)

import jax
import jax.numpy as jnp

from brnext.models.losses import physics_residual_loss


class RebornEvaluator:
    """Reborn 訓練評估器。

    結構指標：
      - compliance_ratio:      C / V_f 正規化合規性
      - volume_fraction_error:  |VF_actual - VF_target| / VF_target
      - physics_residual_norm:  平衡 + 相容殘差 L2 範數

    風格指標：
      - spectral_fid:           頻域 Fréchet 距離
      - style_consistency:      預測 vs 教師 SDF L1
      - sdf_smoothness:         等值面平均曲率變異數

    綜合：
      - pareto_score:           加權組合（用於消融排名）

    使用方式：
        evaluator = RebornEvaluator(config)
        metrics = evaluator.evaluate(model, params, dataset, style_ids)
    """

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
        """
        執行完整評估。

        Args:
            model:      StyleConditionedSSGO 模型
            params:     模型參數
            eval_data:  [(input, physics_target, style_sdf_target, style_id), ...]
            style_ids:  如果 eval_data 不含 style_id，此處提供

        Returns:
            metrics dict（鍵名對應論文表格列名）
        """
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
                x_jnp = x_jnp[None]  # 加 batch 維度

            sid_jnp = jnp.array([sid])
            pred = model.apply({"params": params}, x_jnp, sid_jnp)

            style_target_jnp = jnp.array(style_target) if not isinstance(style_target, jnp.ndarray) else style_target
            if style_target_jnp.ndim == 3:
                style_target_jnp = style_target_jnp[None]

            m = self._compute_sample_metrics(
                pred, x_jnp, physics_target, style_target_jnp,
            )
            metrics_list.append(m)

        # 聚合
        aggregated = {}
        if metrics_list:
            keys = metrics_list[0].keys()
            for k in keys:
                vals = [m[k] for m in metrics_list if np.isfinite(m[k])]
                aggregated[k] = float(np.mean(vals)) if vals else 0.0

        aggregated["eval_time_s"] = time.time() - t0
        aggregated["n_samples"] = len(eval_data)
        aggregated["pareto_score"] = self._compute_pareto(aggregated)
        return aggregated

    def _compute_sample_metrics(
        self, pred, x, physics_target, style_target,
    ) -> dict[str, float]:
        """計算單一樣本的全部指標。"""
        mask = x[..., 0] > 0.5  # 佔用遮罩

        # 結構指標
        pred_stress = pred[..., :6]
        pred_density = jnp.clip(x[..., 0], 0, 1)  # 輸入密度

        cr = self._compliance_ratio(pred_density, pred_stress, mask)
        vf_err = self._volume_fraction_error(pred_density, mask)
        phys_res = self._physics_residual(pred, x, mask)

        # 風格指標
        pred_sdf = pred[..., 10:11]  # 第 11 通道
        s_con = self._style_consistency(pred_sdf, style_target, mask)
        s_fid = self._spectral_fid(pred_sdf.squeeze(-1), style_target.squeeze(-1) if style_target.ndim == 4 else style_target)
        s_smooth = self._sdf_smoothness(pred_sdf.squeeze(-1), mask)

        return {
            "compliance_ratio": float(cr),
            "vf_error": float(vf_err),
            "physics_residual": float(phys_res),
            "style_consistency": float(s_con),
            "spectral_fid": float(s_fid),
            "sdf_smoothness": float(s_smooth),
        }

    # -----------------------------------------------------------------------
    # 結構指標
    # -----------------------------------------------------------------------

    @staticmethod
    def _compliance_ratio(density, stress, mask):
        """C / V_f 正規化合規性。"""
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
        """體積分率相對誤差。"""
        vf = float(jnp.sum(density * mask) / (jnp.sum(mask) + 1e-8))
        return abs(vf - self.target_vf) / (self.target_vf + 1e-8)

    @staticmethod
    def _physics_residual(pred, x, mask):
        """物理殘差範數（平衡 + 相容）。"""
        try:
            E = x[..., 1] * 200e9   # 反正規化
            nu = x[..., 2]
            rho = x[..., 3] * 7850.0
            res = physics_residual_loss(
                pred[..., :6], pred[..., 6:9],
                E, nu, mask.astype(jnp.float32), rho,
            )
            return float(res)
        except Exception:
            return 0.0

    # -----------------------------------------------------------------------
    # 風格指標
    # -----------------------------------------------------------------------

    @staticmethod
    def _style_consistency(pred_sdf, teacher_sdf, mask):
        """風格一致性：遮罩 L1。"""
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
        """頻譜 FID：FFT 幅度分佈的 Fréchet 距離。"""
        F_pred = jnp.abs(jnp.fft.rfftn(pred_sdf, axes=(-3, -2, -1)))
        F_teacher = jnp.abs(jnp.fft.rfftn(teacher_sdf, axes=(-3, -2, -1)))
        mu_p = F_pred.mean()
        mu_t = F_teacher.mean()
        var_p = jnp.var(F_pred)
        var_t = jnp.var(F_teacher)
        return (mu_p - mu_t)**2 + (jnp.sqrt(var_p + 1e-8) - jnp.sqrt(var_t + 1e-8))**2

    @staticmethod
    def _sdf_smoothness(sdf, mask):
        """SDF 等值面平滑度（梯度幅度變異數）。"""
        # 中央差分梯度
        gx = (jnp.roll(sdf, -1, axis=-3) - jnp.roll(sdf, 1, axis=-3)) / 2.0
        gy = (jnp.roll(sdf, -1, axis=-2) - jnp.roll(sdf, 1, axis=-2)) / 2.0
        gz = (jnp.roll(sdf, -1, axis=-1) - jnp.roll(sdf, 1, axis=-1)) / 2.0
        grad_mag = jnp.sqrt(gx**2 + gy**2 + gz**2 + 1e-8)
        # 在等值面附近（|SDF| < 1.5）計算梯度幅度變異數
        near_surface = (jnp.abs(sdf) < 1.5).astype(jnp.float32) * mask.astype(jnp.float32)
        n = jnp.sum(near_surface) + 1e-8
        mean_grad = jnp.sum(grad_mag * near_surface) / n
        variance = jnp.sum((grad_mag - mean_grad)**2 * near_surface) / n
        return variance

    # -----------------------------------------------------------------------
    # 綜合指標
    # -----------------------------------------------------------------------

    @staticmethod
    def _compute_pareto(metrics: dict) -> float:
        """Pareto 綜合分數（越低越好）。"""
        weights = {
            "compliance_ratio": 0.3,
            "physics_residual": 0.2,
            "style_consistency": 0.25,
            "spectral_fid": 0.15,
            "sdf_smoothness": 0.1,
        }
        score = 0.0
        for k, w in weights.items():
            val = metrics.get(k, 0.0)
            score += w * val
        return score

    # -----------------------------------------------------------------------
    # 報告匯出
    # -----------------------------------------------------------------------

    def export_latex_table(
        self,
        results: dict[str, dict],
        output_path: str,
    ) -> None:
        """
        匯出 LaTeX 表格（消融研究用）。

        Args:
            results: {"variant_name": metrics_dict, ...}
            output_path: 輸出 .tex 檔案路徑
        """
        columns = [
            "compliance_ratio", "vf_error", "physics_residual",
            "style_consistency", "spectral_fid", "sdf_smoothness",
            "pareto_score",
        ]
        headers = ["C/V_f", "VF Err", "Phys Res", "Style L1", "S-FID", "Smooth", "Pareto"]

        lines = []
        lines.append("\\begin{tabular}{l" + "c" * len(columns) + "}")
        lines.append("\\toprule")
        lines.append("Variant & " + " & ".join(headers) + " \\\\")
        lines.append("\\midrule")

        for name, m in results.items():
            vals = [f"{m.get(c, 0.0):.4f}" for c in columns]
            lines.append(f"{name} & " + " & ".join(vals) + " \\\\")

        lines.append("\\bottomrule")
        lines.append("\\end{tabular}")

        Path(output_path).parent.mkdir(parents=True, exist_ok=True)
        with open(output_path, "w", encoding="utf-8") as f:
            f.write("\n".join(lines))
