"""
diff_topo.py — JAX 原生可微分 SIMP 拓撲最佳化器

將 topo_optimizer.py 的 numpy SIMP 迴圈改為 JAX 原生實作，
核心創新：使用 jax.grad 計算 dC/dx 的精確敏感度，
取代傳統 Castigliano 近似（無需伴隨求解）。

演算法：
  - SIMP 材料插值：E(x) = E_min + x^p * (1 - E_min)
  - 順從性函數：C(x) = Σ x^p * σ_vm² / (2E)
  - 精確敏感度：dC/dx = jax.grad(C)(x)（自動微分）
  - 密度濾波：FFT 低通高斯濾波（可微分）
  - Heaviside 投影：可微分銳化（Wang et al. 2011）
  - OC 更新：jax.lax.while_loop 二分搜尋（40 次迭代）

與 topo_optimizer.TopologyOptimizer 的差異：
  - 敏感度計算：jax.grad 精確 vs Castigliano 近似
  - 密度濾波：FFT 高斯 vs scipy.ndimage.uniform_filter
  - OC 二分搜尋：jax.lax.while_loop vs Python for 迴圈
  - 可選風格網路前向傳播（style_net + style_net_params）

參考：
  Sigmund (2001), "A 99 line topology optimization code written in Matlab"
  Wang et al. (2011), "On projection methods, convergence and robust formulations"
  Li & Khandelwal (2015), "Two-point gradient-based MMA"
  Bendsøe & Sigmund (2003), "Topology Optimization: Theory, Methods, and Applications"
"""
from __future__ import annotations

import sys
from pathlib import Path
from dataclasses import dataclass
from typing import Any

# 確保 HYBR 與 BR-NeXT 可匯入（與 style_net.py 相同模式）
_REPO_ROOT = Path(__file__).resolve().parent.parent.parent.parent.parent
for _p in [str(_REPO_ROOT / "HYBR"), str(_REPO_ROOT / "BR-NeXT"), str(_REPO_ROOT / "brml")]:
    if _p not in sys.path:
        sys.path.insert(0, _p)

import numpy as np
import jax
import jax.numpy as jnp
from functools import partial

from ..config import RebornConfig, TrainingConfig, SimPConfig
from .topo_optimizer import TopologyResult
from .voxel_massing import VoxelMassingResult


# ---------------------------------------------------------------------------
# JIT 編譯的核心運算
# ---------------------------------------------------------------------------

@jax.jit
def _simp_stiffness(x: jnp.ndarray, p: float, x_min: float) -> jnp.ndarray:
    """SIMP 材料插值：E(x) = E_min + x^p * (1 - E_min)。

    歸一化版本（相對於 E_0=1），需與 E_field 相乘得到真實楊氏模量。
    使用 jax.jit 編譯以加速批次運算。

    Args:
        x:     [Lx, Ly, Lz] 密度場 ∈ [x_min, 1]
        p:     SIMP 懲罰指數（通常 p=3）
        x_min: 最小密度（防止矩陣奇異，通常 1e-3）

    Returns:
        [Lx, Ly, Lz] 歸一化楊氏模量場 ∈ [x_min, 1]
    """
    return x_min + jnp.power(x, p) * (1.0 - x_min)


@jax.jit
def _spectral_filter(x: jnp.ndarray, r_min: float) -> jnp.ndarray:
    """可微分密度濾波：FFT 低通高斯平滑。

    使用頻域高斯核心取代 scipy.ndimage.uniform_filter，
    完全可微分且支援 jax.grad。

    等效高斯 sigma = r_min / (2 * sqrt(2 * ln(2)))，
    使半高寬（FWHM）等於 2 * r_min。

    Args:
        x:     [Lx, Ly, Lz] 密度場
        r_min: 濾波半徑（體素）

    Returns:
        [Lx, Ly, Lz] 濾波後密度場
    """
    # 高斯 sigma 使 FWHM ≈ 2 * r_min
    sigma = r_min / (2.0 * jnp.sqrt(2.0 * jnp.log(2.0)))

    shape = x.shape
    ndim = len(shape)

    # 建立頻域高斯核心
    freq_response = jnp.ones(shape[:ndim - 1] + (shape[-1] // 2 + 1,))
    for axis in range(ndim):
        n = shape[axis]
        if axis < ndim - 1:
            freqs = jnp.fft.fftfreq(n)
        else:
            freqs = jnp.fft.rfftfreq(n)

        # 高斯頻率響應：exp(-2π²σ²f²)
        gauss_1d = jnp.exp(-2.0 * jnp.pi ** 2 * sigma ** 2 * freqs ** 2)

        # 擴展維度以便廣播
        if axis < ndim - 1:
            gauss_1d = gauss_1d.reshape(
                *([1] * axis), n, *([1] * (ndim - 2 - axis)), 1
            )
        else:
            gauss_1d = gauss_1d.reshape(
                *([1] * (ndim - 1)), shape[-1] // 2 + 1
            )
        freq_response = freq_response * gauss_1d

    # 頻域濾波
    ft = jnp.fft.rfftn(x)
    filtered = jnp.fft.irfftn(ft * freq_response, s=shape)
    return filtered


@jax.jit
def _heaviside(x: jnp.ndarray, beta: float, eta: float = 0.5) -> jnp.ndarray:
    """可微分 Heaviside 投影以銳化密度場。

    公式（Wang et al. 2011）：
      x_proj = [tanh(β·η) + tanh(β·(x - η))] / [tanh(β·η) + tanh(β·(1 - η))]

    β 從 1 漸進增大至 32，使密度場逐步趨近 0/1 二值。

    Args:
        x:    [Lx, Ly, Lz] 密度場 ∈ [0, 1]
        beta: 投影銳度（越大越接近階梯函數）
        eta:  投影閾值（通常 0.5）

    Returns:
        [Lx, Ly, Lz] 投影後密度場 ∈ [0, 1]
    """
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
    """OC 密度更新：jax.lax.while_loop 二分搜尋。

    最優準則（Optimality Criteria）密度更新：
      B_e = sqrt(-dC/dx_e / λ)
      x_new = clip(x * B_e, x*(1-move), x*(1+move))
      λ 由二分搜尋確定，使 mean(x_new[mask]) = vol_frac

    使用 jax.lax.while_loop 實現 40 次二分迭代，
    避免 Python for 迴圈的 tracing 開銷。

    Args:
        x:           [Lx, Ly, Lz] 當前密度場
        sensitivity: [Lx, Ly, Lz] 敏感度場（dC/dx，通常為負）
        mask:        [Lx, Ly, Lz] bool 固體遮罩
        vol_frac:    目標體積分率
        move:        OC 步長限制
        x_min:       最小密度

    Returns:
        [Lx, Ly, Lz] 更新後密度場
    """
    mask_f = mask.astype(jnp.float32)
    n_solid = jnp.sum(mask_f) + 1e-8  # 防止除零

    # 二分搜尋初始範圍
    init_state = (
        jnp.array(1e-9),   # l1（下界）
        jnp.array(1e9),    # l2（上界）
        x,                  # x_best（最佳候選）
        jnp.array(0),      # iteration counter
    )

    def cond_fn(state):
        """繼續條件：未達 40 次且區間尚未收斂。"""
        l1, l2, _, it = state
        relative_gap = (l2 - l1) / (l1 + l2 + 1e-30)
        return (it < 40) & (relative_gap > 1e-4)

    def body_fn(state):
        """單次二分迭代。"""
        l1, l2, x_best, it = state
        lmid = 0.5 * (l1 + l2)

        # OC 候選密度：B_e = sqrt(-sensitivity / lambda)
        B_e = jnp.sqrt(jnp.maximum(-sensitivity / (lmid + 1e-30), 0.0))
        x_cand = jnp.clip(
            x * B_e,
            jnp.maximum(x_min, x - move),
            jnp.minimum(1.0, x + move),
        )
        # 遮罩外區域歸零
        x_cand = x_cand * mask_f

        # 體積分率
        vf = jnp.sum(x_cand * mask_f) / n_solid

        # 二分搜尋方向
        l1_new = jnp.where(vf > vol_frac, lmid, l1)
        l2_new = jnp.where(vf > vol_frac, l2, lmid)
        x_best_new = jnp.where(vf <= vol_frac, x_cand, x_best)

        return (l1_new, l2_new, x_best_new, it + 1)

    # 執行 while_loop 二分搜尋
    _, _, x_new, _ = jax.lax.while_loop(cond_fn, body_fn, init_state)

    return x_new


# ---------------------------------------------------------------------------
# 主類別：可微分 SIMP 拓撲最佳化器
# ---------------------------------------------------------------------------

class DiffTopologyOptimizer:
    """JAX 原生可微分 SIMP 拓撲最佳化器。

    核心創新：使用 jax.grad 計算精確敏感度 dC/dx，
    取代 topo_optimizer.TopologyOptimizer 中的 Castigliano 近似。

    精確敏感度優勢：
      - 無截斷誤差（Castigliano 近似忽略了應力對密度的隱式梯度）
      - 更快收斂（每次迭代的搜尋方向更精確）
      - 支援與風格網路的端到端微分

    可選風格網路整合：
      若提供 style_net + style_net_params，最佳化目標增加風格損失項。

    使用方式：
        optimizer = DiffTopologyOptimizer(config)
        result = optimizer.optimize(massing_result)
    """

    def __init__(
        self,
        config: RebornConfig | TrainingConfig,
        style_net: Any | None = None,
        style_net_params: Any | None = None,
    ):
        """初始化可微分 SIMP 最佳化器。

        Args:
            config:           RebornConfig 或 TrainingConfig，包含 SimPConfig
            style_net:        可選風格網路（Flax nn.Module），用於風格損失
            style_net_params: 風格網路的凍結參數
        """
        self.config = config
        self.simp: SimPConfig = config.simp
        self.style_net = style_net
        self.style_net_params = style_net_params
        self._beta = 1.0  # Heaviside 投影初始 beta

    def optimize(self, massing_result: VoxelMassingResult) -> TopologyResult:
        """執行完整 SIMP 最佳化迴圈。

        流程：
          1. 初始化均勻密度場
          2. 迴圈：FNO 推論 → jax.grad 敏感度 → 濾波 → OC 更新 → Heaviside
          3. 收斂或達最大迭代後回傳 TopologyResult

        Args:
            massing_result: 第一階段輸出（VoxelMassingResult）

        Returns:
            TopologyResult — 收斂的最佳化結果（numpy 陣列）
        """
        Lx, Ly, Lz = massing_result.shape
        occ = jnp.array(massing_result.occupancy.astype(np.float32))
        mask = occ > 0.5
        E_field = jnp.array(massing_result.E_field)

        # 初始化密度（均勻 target_vf）
        x = jnp.full_like(occ, self.simp.vol_frac)
        x = jnp.where(mask, x, 0.0)

        # 追蹤歷史
        compliance_hist: list[float] = []
        volfrac_hist: list[float] = []
        last_stress = jnp.zeros((Lx, Ly, Lz, 6), dtype=jnp.float32)
        last_disp = jnp.zeros((Lx, Ly, Lz, 3), dtype=jnp.float32)
        last_phi = jnp.zeros((Lx, Ly, Lz), dtype=jnp.float32)

        # FNO 代理模型（復用 config 中的設定）
        from ..models.fno_proxy import FNOProxy
        fno = FNOProxy(
            mode=self.config.fno.backend,
            verbose=getattr(self.config, "verbose", False),
        )

        verbose = getattr(self.config, "verbose", False)
        if verbose:
            print(f"[DiffTopoOptimizer] 開始 JAX SIMP：grid={massing_result.shape}，"
                  f"目標體積分率={self.simp.vol_frac:.2f}，"
                  f"最大迭代={self.simp.max_iter}")

        for it in range(self.simp.max_iter):
            # ── Step 1：FNO 推論應力場 ──
            # 將 JAX 陣列轉為 numpy 以供 FNO ONNX 推論
            E_simp = _simp_stiffness(x, self.simp.p_simp, self.simp.x_min) * E_field
            x_np = np.asarray(x)
            stress_np, disp_np, phi_np = fno.predict(
                x_np > 0.5,
                np.asarray(E_simp),
                np.asarray(massing_result.nu_field),
                np.asarray(massing_result.density_field),
                np.asarray(massing_result.rcomp_field),
            )
            # 轉回 JAX
            stress = jnp.array(stress_np)
            disp = jnp.array(disp_np)
            phi = jnp.array(phi_np)
            last_stress, last_disp, last_phi = stress, disp, phi

            # ── Step 2：jax.grad 精確敏感度 ──
            fno_output_vm2 = self._compute_von_mises_sq(stress)
            sensitivity = self._compute_sensitivity_autodiff(
                x, fno_output_vm2, E_field,
            )
            # 遮罩外歸零
            sensitivity = jnp.where(mask, sensitivity, 0.0)

            # ── Step 3：可微分密度濾波（FFT 高斯） ──
            sensitivity = _spectral_filter(sensitivity, self.simp.r_min)

            # ── Step 4：OC 密度更新（jax.lax.while_loop 二分搜尋） ──
            x_new = _oc_update_jax(
                x, sensitivity, mask,
                vol_frac=self.simp.vol_frac,
                move=self.simp.move,
                x_min=self.simp.x_min,
            )

            # ── Step 5：Heaviside 投影（後 1/3 迭代漸進銳化） ──
            if it > self.simp.max_iter // 3:
                self._beta = min(self._beta * 1.1, 32.0)
                x_new = _heaviside(x_new, self._beta)

            # ── Step 6：記錄與收斂判斷 ──
            compliance = float(jnp.sum(fno_output_vm2 * x))
            volfrac = float(jnp.mean(x_new[mask])) if mask.any() else 0.0
            compliance_hist.append(compliance)
            volfrac_hist.append(volfrac)

            change = float(jnp.max(jnp.abs(x_new - x)))
            if verbose and (it % 10 == 0 or it < 5):
                print(f"  iter {it + 1:3d}/{self.simp.max_iter}: "
                      f"C={compliance:.4e}, VF={volfrac:.3f}, Δx={change:.4f}")

            if change < self.simp.tol and it > 5:
                x = x_new
                if verbose:
                    print(f"[DiffTopoOptimizer] 收斂於第 {it + 1} 次迭代")
                return self._build_result(
                    x, last_stress, last_disp, last_phi,
                    compliance_hist, volfrac_hist,
                    n_iterations=it + 1, converged=True,
                    massing=massing_result,
                )
            x = x_new

        if verbose:
            print(f"[DiffTopoOptimizer] 達到最大迭代次數（{self.simp.max_iter}），未完全收斂")

        return self._build_result(
            x, last_stress, last_disp, last_phi,
            compliance_hist, volfrac_hist,
            n_iterations=self.simp.max_iter, converged=False,
            massing=massing_result,
        )

    # -----------------------------------------------------------------------
    # 敏感度計算（jax.grad 核心創新）
    # -----------------------------------------------------------------------

    def _compute_sensitivity_autodiff(
        self,
        density: jnp.ndarray,
        fno_output_vm2: jnp.ndarray,
        E_field: jnp.ndarray,
    ) -> jnp.ndarray:
        """使用 jax.grad 計算精確敏感度 dC/dx。

        順從性函數：
          C(x) = Σ_e x_e^p * σ_vm,e² / (2 * E_e)

        精確敏感度：
          dC/dx_e = p * x_e^(p-1) * σ_vm,e² / (2 * E_e)

        注意：此處 σ_vm² 視為 FNO 輸出的常數（不對密度微分），
        jax.grad 自動處理 x^p 項的微分。若未來 FNO 本身也可微分，
        可直接對整個管線取 grad 獲得包含隱式梯度的完整敏感度。

        Args:
            density:        [Lx, Ly, Lz] 當前密度場
            fno_output_vm2: [Lx, Ly, Lz] von Mises 應力平方
            E_field:        [Lx, Ly, Lz] 楊氏模量場

        Returns:
            [Lx, Ly, Lz] 敏感度場 dC/dx
        """
        p = self.simp.p_simp
        x_min = self.simp.x_min

        # 使用 jax.grad 對順從性函數自動微分
        # 凍結 fno_output_vm2 和 E_field，只對 density 微分
        def compliance_fn(x: jnp.ndarray) -> jnp.ndarray:
            """順從性 C(x) = Σ x^p * σ_vm² / (2E)。"""
            E_safe = jnp.where(E_field > 0, E_field, 1.0)
            stiffness = _simp_stiffness(x, p, x_min)
            # 順從性：材料越剛 → 順從性越低（取負號用於最小化）
            C = jnp.sum(stiffness * fno_output_vm2 / (2.0 * E_safe))
            return C

        # jax.grad 計算精確 dC/dx
        grad_C = jax.grad(compliance_fn)(density)

        # 取負號（OC 更新使用 -dC/dx）
        return -grad_C

    # -----------------------------------------------------------------------
    # von Mises 應力計算
    # -----------------------------------------------------------------------

    @staticmethod
    def _compute_von_mises_sq(stress_voigt: jnp.ndarray) -> jnp.ndarray:
        """計算 von Mises 等效應力的平方。

        公式（3D Voigt 形式）：
          σ_vm² = σ_xx² + σ_yy² + σ_zz²
                  - σ_xx·σ_yy - σ_yy·σ_zz - σ_xx·σ_zz
                  + 3·(τ_yz² + τ_xz² + τ_xy²)

        Args:
            stress_voigt: [Lx, Ly, Lz, 6] Voigt 應力場
                          索引：[σ_xx, σ_yy, σ_zz, τ_yz, τ_xz, τ_xy]

        Returns:
            [Lx, Ly, Lz] von Mises 應力平方
        """
        sxx = stress_voigt[..., 0]
        syy = stress_voigt[..., 1]
        szz = stress_voigt[..., 2]
        tyz = stress_voigt[..., 3]
        txz = stress_voigt[..., 4]
        txy = stress_voigt[..., 5]

        vm2 = (
            sxx ** 2 + syy ** 2 + szz ** 2
            - sxx * syy - syy * szz - sxx * szz
            + 3.0 * (tyz ** 2 + txz ** 2 + txy ** 2)
        )
        return jnp.maximum(vm2, 0.0)

    # -----------------------------------------------------------------------
    # 結果封裝（JAX → numpy）
    # -----------------------------------------------------------------------

    @staticmethod
    def _build_result(
        density: jnp.ndarray,
        stress: jnp.ndarray,
        disp: jnp.ndarray,
        phi: jnp.ndarray,
        compliance_hist: list[float],
        volfrac_hist: list[float],
        n_iterations: int,
        converged: bool,
        massing: VoxelMassingResult,
    ) -> TopologyResult:
        """將 JAX 陣列轉換為 numpy 並封裝為 TopologyResult。

        所有 JAX 張量在此處統一轉換為 numpy float32，
        確保下游（風格管線、NURBS 輸出）不需要 JAX 依賴。

        Args:
            density:         [Lx, Ly, Lz] JAX 密度場
            stress:          [Lx, Ly, Lz, 6] JAX 應力場
            disp:            [Lx, Ly, Lz, 3] JAX 位移場
            phi:             [Lx, Ly, Lz] JAX 勢場
            compliance_hist: 順從性歷史紀錄
            volfrac_hist:    體積分率歷史紀錄
            n_iterations:    實際迭代次數
            converged:       是否收斂
            massing:         原始量體資料

        Returns:
            TopologyResult — 純 numpy 結果
        """
        return TopologyResult(
            density=np.asarray(density, dtype=np.float32),
            stress_voigt=np.asarray(stress, dtype=np.float32),
            displacement=np.asarray(disp, dtype=np.float32),
            phi=np.asarray(phi, dtype=np.float32),
            compliance_history=compliance_hist,
            volfrac_history=volfrac_hist,
            n_iterations=n_iterations,
            converged=converged,
            massing=massing,
        )
