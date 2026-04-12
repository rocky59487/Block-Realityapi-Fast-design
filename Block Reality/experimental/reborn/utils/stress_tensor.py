"""
stress_tensor.py — 應力張量工具函式庫

涵蓋：
  - Voigt 記法 (6 分量) ↔ 完整 3×3 對稱張量
  - 馮米塞斯等效應力
  - 主應力與主方向（特徵分解）
  - 主應力軌跡提取（應力路徑 StreamLines）

參考文獻：
  Voigt, W. (1910). Lehrbuch der Kristallphysik.
  Mises, R. (1913). Mechanik der festen Körper im plastisch-deformablen Zustand.
"""
from __future__ import annotations
import numpy as np
from numpy.typing import NDArray


# ---------------------------------------------------------------------------
# Voigt 記法轉換
# ---------------------------------------------------------------------------
# Voigt 排列：[σ_xx, σ_yy, σ_zz, σ_yz, σ_xz, σ_xy]  (索引 0–5)
# 完整張量：3×3 對稱矩陣

def voigt_to_tensor(s: NDArray) -> NDArray:
    """
    將 Voigt 6 分量應力向量轉換為 3×3 對稱張量。

    Args:
        s: shape (..., 6) — Voigt 記法 [σxx,σyy,σzz,σyz,σxz,σxy]

    Returns:
        shape (..., 3, 3) — 完整對稱應力張量
    """
    shape = s.shape[:-1]
    T = np.zeros(shape + (3, 3), dtype=s.dtype)
    T[..., 0, 0] = s[..., 0]   # σxx
    T[..., 1, 1] = s[..., 1]   # σyy
    T[..., 2, 2] = s[..., 2]   # σzz
    T[..., 1, 2] = T[..., 2, 1] = s[..., 3]   # σyz
    T[..., 0, 2] = T[..., 2, 0] = s[..., 4]   # σxz
    T[..., 0, 1] = T[..., 1, 0] = s[..., 5]   # σxy
    return T


def tensor_to_voigt(T: NDArray) -> NDArray:
    """
    將 3×3 對稱應力張量轉換為 Voigt 6 分量。

    Args:
        T: shape (..., 3, 3)

    Returns:
        shape (..., 6) — [σxx,σyy,σzz,σyz,σxz,σxy]
    """
    s = np.zeros(T.shape[:-2] + (6,), dtype=T.dtype)
    s[..., 0] = T[..., 0, 0]
    s[..., 1] = T[..., 1, 1]
    s[..., 2] = T[..., 2, 2]
    s[..., 3] = T[..., 1, 2]
    s[..., 4] = T[..., 0, 2]
    s[..., 5] = T[..., 0, 1]
    return s


# ---------------------------------------------------------------------------
# 馮米塞斯等效應力
# ---------------------------------------------------------------------------

def von_mises(s: NDArray) -> NDArray:
    """
    計算馮米塞斯等效應力（純量場）。

    公式（Voigt 輸入）：
        σ_vm = sqrt(½[(σxx-σyy)² + (σyy-σzz)² + (σzz-σxx)²
                    + 6(σyz² + σxz² + σxy²)])

    Args:
        s: shape (..., 6) — Voigt 應力 [σxx,σyy,σzz,σyz,σxz,σxy]

    Returns:
        shape (...) — 馮米塞斯等效應力（非負純量）
    """
    sx, sy, sz = s[..., 0], s[..., 1], s[..., 2]
    tyz, txz, txy = s[..., 3], s[..., 4], s[..., 5]
    vm2 = 0.5 * ((sx - sy)**2 + (sy - sz)**2 + (sz - sx)**2) \
        + 3.0 * (tyz**2 + txz**2 + txy**2)
    return np.sqrt(np.maximum(vm2, 0.0))


def hydrostatic(s: NDArray) -> NDArray:
    """靜水壓力 p = (σxx+σyy+σzz)/3"""
    return (s[..., 0] + s[..., 1] + s[..., 2]) / 3.0


def deviatoric(s: NDArray) -> NDArray:
    """偏差應力張量（Voigt）：s' = s - p·I"""
    p = hydrostatic(s)
    sd = s.copy()
    sd[..., 0] -= p
    sd[..., 1] -= p
    sd[..., 2] -= p
    return sd


# ---------------------------------------------------------------------------
# 主應力與主方向
# ---------------------------------------------------------------------------

def principal_stresses(s: NDArray) -> tuple[NDArray, NDArray]:
    """
    計算主應力（特徵值）與主方向（特徵向量）。

    對大型場使用 np.linalg.eigh（對稱矩陣特化，速度快 2-3×）。

    Args:
        s: shape (..., 6) — Voigt 應力

    Returns:
        eigenvalues:  shape (..., 3) — 主應力 σ1 ≤ σ2 ≤ σ3（MPa）
        eigenvectors: shape (..., 3, 3) — 每欄為主方向單位向量
    """
    T = voigt_to_tensor(s)
    # eigh 回傳升序特徵值
    eigenvalues, eigenvectors = np.linalg.eigh(T)
    return eigenvalues, eigenvectors


def max_principal(s: NDArray) -> tuple[NDArray, NDArray]:
    """最大主應力（σ3，最大拉伸）與對應主方向"""
    evals, evecs = principal_stresses(s)
    return evals[..., 2], evecs[..., :, 2]


def min_principal(s: NDArray) -> tuple[NDArray, NDArray]:
    """最小主應力（σ1，最大壓縮）與對應主方向"""
    evals, evecs = principal_stresses(s)
    return evals[..., 0], evecs[..., :, 0]


# ---------------------------------------------------------------------------
# 應力狀態分類
# ---------------------------------------------------------------------------

def stress_triaxiality(s: NDArray) -> NDArray:
    """
    應力三軸度：η = p / σ_vm
    η > 0：拉伸主導；η < 0：壓縮主導；|η| 越大越危險
    """
    p = hydrostatic(s)
    vm = von_mises(s)
    return np.where(vm > 1e-12, p / vm, 0.0)


def is_tension_dominated(s: NDArray, thresh: float = 0.0) -> NDArray:
    """回傳布林陣列：最大主應力 > thresh（拉伸主導體素）"""
    sigma3, _ = max_principal(s)
    return sigma3 > thresh


def is_compression_dominated(s: NDArray, thresh: float = 0.0) -> NDArray:
    """回傳布林陣列：最小主應力 < thresh（壓縮主導體素）"""
    sigma1, _ = min_principal(s)
    return sigma1 < thresh


# ---------------------------------------------------------------------------
# 體積分量提取
# ---------------------------------------------------------------------------

def stress_invariants(s: NDArray) -> tuple[NDArray, NDArray, NDArray]:
    """
    計算應力不變量 I1, J2, J3。

    Returns:
        I1: shape (...) — 第一不變量（跡）= σxx+σyy+σzz
        J2: shape (...) — 偏差應力第二不變量
        J3: shape (...) — 偏差應力第三不變量（行列式）
    """
    I1 = s[..., 0] + s[..., 1] + s[..., 2]
    sd = deviatoric(s)
    T_dev = voigt_to_tensor(sd)
    # J2 = ½ tr(s'²)
    J2 = 0.5 * np.sum(T_dev**2, axis=(-2, -1))
    # J3 = det(s')
    J3 = np.linalg.det(T_dev)
    return I1, J2, J3
