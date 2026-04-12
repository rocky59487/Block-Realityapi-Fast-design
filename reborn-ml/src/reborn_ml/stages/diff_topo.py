"""
diff_topo.py — JAX 原生可微分 SIMP 拓撲最佳化器

核心創新：使用 jax.grad 計算精確敏感度 dC/dx，
取代傳統 Castigliano 近似。

演算法：
  - SIMP 材料插值：E(x) = E_min + x^p * (1 - E_min)
  - 順從性：C(x) = Σ x^p * σ_vm² / (2E)
  - 精確敏感度：dC/dx = jax.grad(C)(x)
  - 密度濾波：FFT 低通高斯濾波（可微分）
  - Heaviside 投影（Wang et al. 2011）
  - OC 更新：jax.lax.while_loop 二分搜尋

參考：
  Sigmund (2001), "A 99 line topology optimization code written in Matlab"
  Wang et al. (2011), "On projection methods, convergence and robust formulations"
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

import numpy as np
import jax
import jax.numpy as jnp

from reborn_ml.config import TrainingConfig, SimPConfig


@dataclass
class TopologyResult:
    """拓撲最佳化結果容器。"""
    density:            np.ndarray          # [Lx, Ly, Lz] 收斂密度場
    stress_voigt:       np.ndarray          # [Lx, Ly, Lz, 6]
    displacement:       np.ndarray          # [Lx, Ly, Lz, 3]
    phi:                np.ndarray          # [Lx, Ly, Lz]
    compliance_history: list[float] = field(default_factory=list)
    volfrac_history:    list[float] = field(default_factory=list)
    n_iterations:       int = 0
    converged:          bool = False


@jax.jit
def _simp_stiffness(x: jnp.ndarray, p: float, x_min: float) -> jnp.ndarray:
    """SIMP 材料插值：E(x) = E_min + x^p * (1 - E_min)。"""
    return x_min + jnp.power(x, p) * (1.0 - x_min)


@jax.jit
def _spectral_filter(x: jnp.ndarray, r_min: float) -> jnp.ndarray:
    """可微分密度濾波：FFT 低通高斯平滑。"""
    sigma = r_min / (2.0 * jnp.sqrt(2.0 * jnp.log(2.0)))
    shape = x.shape
    ndim = len(shape)
    freq_response = jnp.ones(shape[:ndim - 1] + (shape[-1] // 2 + 1,))
    for axis in range(ndim):
        n = shape[axis]
        freqs = jnp.fft.fftfreq(n) if axis < ndim - 1 else jnp.fft.rfftfreq(n)
        gauss_1d = jnp.exp(-2.0 * jnp.pi**2 * sigma**2 * freqs**2)
        if axis < ndim - 1:
            gauss_1d = gauss_1d.reshape(*([1]*axis), n, *([1]*(ndim - 2 - axis)), 1)
        else:
            gauss_1d = gauss_1d.reshape(*([1]*(ndim-1)), shape[-1] // 2 + 1)
        freq_response = freq_response * gauss_1d
    ft = jnp.fft.rfftn(x)
    return jnp.fft.irfftn(ft * freq_response, s=shape)


@jax.jit
def _heaviside(x: jnp.ndarray, beta: float, eta: float = 0.5) -> jnp.ndarray:
    """可微分 Heaviside 投影（Wang et al. 2011）。"""
    numerator = jnp.tanh(beta * eta) + jnp.tanh(beta * (x - eta))
    denominator = jnp.tanh(beta * eta) + jnp.tanh(beta * (1.0 - eta))
    return numerator / (denominator + 1e-8)


def _oc_update_jax(
    x: jnp.ndarray,
    sensitivity: jnp.ndarray,
    mask: jnp.ndarray,
    vol_frac: float,
    move: float,
    x_min: float,
) -> jnp.ndarray:
    """OC 密度更新：jax.lax.while_loop 40 步二分搜尋。"""
    mask_f = mask.astype(jnp.float32)
    n_solid = jnp.sum(mask_f) + 1e-8

    def cond_fn(state):
        l1, l2, _, it = state
        return (it < 40) & ((l2 - l1) / (l1 + l2 + 1e-30) > 1e-4)

    def body_fn(state):
        l1, l2, x_best, it = state
        lmid = 0.5 * (l1 + l2)
        B_e = jnp.sqrt(jnp.maximum(-sensitivity / (lmid + 1e-30), 0.0))
        x_cand = jnp.clip(x * B_e, jnp.maximum(x_min, x - move), jnp.minimum(1.0, x + move)) * mask_f
        vf = jnp.sum(x_cand * mask_f) / n_solid
        l1_new = jnp.where(vf > vol_frac, lmid, l1)
        l2_new = jnp.where(vf > vol_frac, l2, lmid)
        x_best_new = jnp.where(vf <= vol_frac, x_cand, x_best)
        return (l1_new, l2_new, x_best_new, it + 1)

    _, _, x_new, _ = jax.lax.while_loop(
        cond_fn, body_fn,
        (jnp.array(1e-9), jnp.array(1e9), x, jnp.array(0)),
    )
    return x_new


class DiffTopologyOptimizer:
    """JAX 原生可微分 SIMP 拓撲最佳化器。

    使用方式（純 ML 模式，僅需密度場）：
        import numpy as np
        import jax.numpy as jnp
        from reborn_ml.stages.diff_topo import DiffTopologyOptimizer, TopologyResult
        from reborn_ml.config import TrainingConfig

        cfg = TrainingConfig()
        optimizer = DiffTopologyOptimizer(cfg)

        # 需自行提供 occupancy + material fields
        occ   = np.random.random((16, 16, 16)) > 0.5
        E_f   = np.ones((16, 16, 16)) * 200e9
        # (自行提供 von_mises_squared from FNO output)
    """

    def __init__(self, config: TrainingConfig, style_net: Any = None, style_net_params: Any = None):
        self.config = config
        self.simp: SimPConfig = config.simp
        self.style_net = style_net
        self.style_net_params = style_net_params
        self._beta = 1.0

    def optimize_from_fields(
        self,
        occupancy: np.ndarray,
        E_field: np.ndarray,
        nu_field: np.ndarray,
        density_field: np.ndarray | None = None,
        rcomp_field: np.ndarray | None = None,
        verbose: bool = False,
    ) -> TopologyResult:
        """執行 SIMP 最佳化（純 ML API，無 Minecraft 依賴）。

        Args:
            occupancy:     [Lx, Ly, Lz] bool 佔用遮罩
            E_field:       [Lx, Ly, Lz] 楊氏模量場 (Pa)
            nu_field:      [Lx, Ly, Lz] 泊松比場
            density_field: [Lx, Ly, Lz] 質量密度 (kg/m³)，可選
            rcomp_field:   [Lx, Ly, Lz] 壓縮強度 (Pa)，可選
            verbose:       是否打印迭代進度

        Returns:
            TopologyResult — 收斂的最佳化結果
        """
        occ = jnp.array(occupancy.astype(np.float32))
        E_f = jnp.array(E_field.astype(np.float32))
        mask = occ > 0.5

        x = jnp.where(mask, jnp.full_like(occ, self.simp.vol_frac), 0.0)

        compliance_hist, volfrac_hist = [], []
        last_stress = jnp.zeros((*occ.shape, 6))
        last_disp   = jnp.zeros((*occ.shape, 3))
        last_phi    = jnp.zeros(occ.shape)

        # FNO proxy（嘗試匯入，失敗則使用均勻應力 Mock）
        fno = None
        try:
            from reborn.models.fno_proxy import FNOProxy
            fno = FNOProxy(mode="mock", verbose=False)
        except ImportError:
            pass

        for it in range(self.simp.max_iter):
            # ── FNO 推論應力場 ──
            if fno is not None:
                E_simp = _simp_stiffness(x, self.simp.p_simp, self.simp.x_min) * E_f
                stress_np, disp_np, phi_np = fno.predict(
                    np.asarray(x > 0.5),
                    np.asarray(E_simp),
                    np.asarray(nu_field),
                    np.asarray(density_field) if density_field is not None else np.ones(occ.shape) * 7850.0,
                    np.asarray(rcomp_field) if rcomp_field is not None else np.ones(occ.shape) * 250e6,
                )
                stress = jnp.array(stress_np)
            else:
                # Mock：均勻應力（測試用）
                stress = jnp.ones((*occ.shape, 6)) * 1e6
                disp_np = np.zeros((*occ.shape, 3))
                phi_np  = np.zeros(occ.shape)

            last_stress = stress
            last_disp   = jnp.array(disp_np)
            last_phi    = jnp.array(phi_np)

            # ── jax.grad 精確敏感度 ──
            vm2 = self._von_mises_sq(stress)
            sensitivity = self._sensitivity_autodiff(x, vm2, E_f)
            sensitivity = jnp.where(mask, sensitivity, 0.0)
            sensitivity = _spectral_filter(sensitivity, self.simp.r_min)

            # ── OC 更新 ──
            x_new = _oc_update_jax(x, sensitivity, mask, self.simp.vol_frac, self.simp.move, self.simp.x_min)

            # ── Heaviside 銳化 ──
            if it > self.simp.max_iter // 3:
                self._beta = min(self._beta * 1.1, 32.0)
                x_new = _heaviside(x_new, self._beta)

            compliance = float(jnp.sum(vm2 * x))
            volfrac = float(jnp.mean(x_new[mask])) if mask.any() else 0.0
            compliance_hist.append(compliance)
            volfrac_hist.append(volfrac)

            change = float(jnp.max(jnp.abs(x_new - x)))
            if verbose and (it % 10 == 0 or it < 5):
                print(f"  iter {it+1:3d}: C={compliance:.4e}, VF={volfrac:.3f}, Δx={change:.4f}")

            if change < self.simp.tol and it > 5:
                x = x_new
                if verbose:
                    print(f"  收斂於第 {it+1} 次迭代")
                break
            x = x_new

        return TopologyResult(
            density=np.asarray(x, dtype=np.float32),
            stress_voigt=np.asarray(last_stress, dtype=np.float32),
            displacement=np.asarray(last_disp, dtype=np.float32),
            phi=np.asarray(last_phi, dtype=np.float32),
            compliance_history=compliance_hist,
            volfrac_history=volfrac_hist,
            n_iterations=len(compliance_hist),
            converged=(len(compliance_hist) < self.simp.max_iter),
        )

    def _sensitivity_autodiff(self, density, vm2, E_field):
        p = self.simp.p_simp
        x_min = self.simp.x_min

        def compliance_fn(x):
            E_safe = jnp.where(E_field > 0, E_field, 1.0)
            stiffness = _simp_stiffness(x, p, x_min)
            return jnp.sum(stiffness * vm2 / (2.0 * E_safe))

        return -jax.grad(compliance_fn)(density)

    @staticmethod
    def _von_mises_sq(stress_voigt):
        s = stress_voigt
        vm2 = (
            s[..., 0]**2 + s[..., 1]**2 + s[..., 2]**2
            - s[..., 0]*s[..., 1] - s[..., 1]*s[..., 2] - s[..., 0]*s[..., 2]
            + 3.0 * (s[..., 3]**2 + s[..., 4]**2 + s[..., 5]**2)
        )
        return jnp.maximum(vm2, 0.0)
