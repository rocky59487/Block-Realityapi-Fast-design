"""
stress_path.py — 主應力路徑提取（應力軌跡追蹤）

演算法：
  1. 從高馮米塞斯應力區域選取種子點
  2. 沿主應力特徵向量場使用 RK4 積分追蹤路徑
  3. 停止條件：離開域、密度過低、超過最大步數

參考：
  de Berg et al., "Computational Geometry" — 向量場積分方法
  Delmarcelle & Hesselink (1993) — "Visualizing Second-Order Tensor Fields"
"""
from __future__ import annotations
import numpy as np
from numpy.typing import NDArray
from scipy.ndimage import map_coordinates

from ..utils.stress_tensor import von_mises, principal_stresses


def extract_principal_stress_paths(
    stress_voigt: NDArray,
    density:     NDArray,
    n_seeds:     int = 48,
    step_size:   float = 0.5,
    max_steps:   int = 200,
    min_density: float = 0.15,
    vm_thresh_pct: float = 0.5,
    stress_type: str = "max",
    seed: int = 42,
) -> list[NDArray]:
    """
    從應力場追蹤主應力路徑。

    Args:
        stress_voigt:  float32[Lx,Ly,Lz,6] — Voigt 應力場
        density:       float32[Lx,Ly,Lz]   — 密度場 ∈ [0,1]
        n_seeds:       最大種子點數
        step_size:     RK4 步長（體素）
        max_steps:     每條路徑最大步數
        min_density:   低於此密度時停止追蹤
        vm_thresh_pct: 種子點馮米塞斯百分比閾值（0.5 = 前 50% 應力最大區）
        stress_type:   "max" = 主壓縮（高第拱）/ "min" = 主張拉（札哈流）/ "mid"
        seed:          隨機種子

    Returns:
        list of (N,3) float32 arrays — 路徑座標（體素空間）
    """
    Lx, Ly, Lz = density.shape
    vm = von_mises(stress_voigt)

    # 計算主應力方向場
    _, eigvecs = principal_stresses(stress_voigt)   # [Lx,Ly,Lz,3,3]
    # 特徵向量按特徵值升序排列：索引 0=最小主應力, 2=最大主應力
    stress_idx = {"min": 0, "mid": 1, "max": 2}[stress_type]
    direction_field = eigvecs[..., :, stress_idx]   # [Lx,Ly,Lz,3]

    # 確保方向場連續（防止 pi-翻轉不連續）
    direction_field = _smooth_direction_field(direction_field)

    # 選取種子點：高 vm 且高密度
    vm_thresh = np.percentile(vm[density > 0.4], vm_thresh_pct * 100) if np.any(density > 0.4) else 0
    seed_mask = (vm > vm_thresh) & (density > 0.4)
    seed_pts = np.argwhere(seed_mask).astype(np.float32)

    if len(seed_pts) == 0:
        return []

    rng = np.random.default_rng(seed)
    if len(seed_pts) > n_seeds:
        idx = rng.choice(len(seed_pts), n_seeds, replace=False)
        seed_pts = seed_pts[idx]

    # 為每個種子追蹤路徑（正向 + 反向）
    paths = []
    for s in seed_pts:
        path_fwd = _rk4_trace(s, direction_field, density, step_size, max_steps,
                               min_density, Lx, Ly, Lz, reverse=False)
        path_rev = _rk4_trace(s, direction_field, density, step_size, max_steps,
                               min_density, Lx, Ly, Lz, reverse=True)
        # 合併：反向路徑倒序 + 正向路徑（去除重複的種子點）
        if len(path_rev) > 1:
            full_path = np.concatenate([path_rev[::-1], path_fwd[1:]], axis=0)
        else:
            full_path = path_fwd
        if len(full_path) >= 3:
            paths.append(full_path.astype(np.float32))

    return paths


def _rk4_trace(
    start:     NDArray,
    dir_field: NDArray,
    density:   NDArray,
    step:      float,
    max_steps: int,
    min_den:   float,
    Lx: int, Ly: int, Lz: int,
    reverse:   bool,
) -> NDArray:
    """
    RK4 積分追蹤單條路徑（正向或反向）。

    使用三線性插值取得非整數點的方向向量。
    """
    sign = -1.0 if reverse else 1.0
    pts = [start.copy()]
    cur = start.copy()

    for _ in range(max_steps):
        # 檢查邊界與密度
        if not _in_domain(cur, Lx, Ly, Lz):
            break
        den = _trilinear_scalar(density, cur)
        if den < min_den:
            break

        # RK4 步驟
        k1 = _trilinear_vec(dir_field, cur)
        k2 = _trilinear_vec(dir_field, cur + 0.5 * step * sign * k1)
        k3 = _trilinear_vec(dir_field, cur + 0.5 * step * sign * k2)
        k4 = _trilinear_vec(dir_field, cur + step * sign * k3)

        delta = (step / 6.0) * sign * (k1 + 2 * k2 + 2 * k3 + k4)
        if np.linalg.norm(delta) < 1e-8:
            break
        cur = cur + delta
        pts.append(cur.copy())

    return np.array(pts)


def _trilinear_vec(field: NDArray, pt: NDArray) -> NDArray:
    """三線性插值向量場（field:[Lx,Ly,Lz,3]）at point pt:[3]"""
    coords = np.array([
        np.clip(pt[0], 0, field.shape[0] - 1),
        np.clip(pt[1], 0, field.shape[1] - 1),
        np.clip(pt[2], 0, field.shape[2] - 1),
    ])
    v = np.array([
        map_coordinates(field[..., c], coords[:, None], order=1, mode="nearest")[0]
        for c in range(3)
    ], dtype=np.float32)
    nrm = np.linalg.norm(v)
    return v / (nrm + 1e-8)


def _trilinear_scalar(field: NDArray, pt: NDArray) -> float:
    """三線性插值純量場（field:[Lx,Ly,Lz]）at point pt:[3]"""
    coords = np.array([[
        np.clip(pt[0], 0, field.shape[0] - 1),
        np.clip(pt[1], 0, field.shape[1] - 1),
        np.clip(pt[2], 0, field.shape[2] - 1),
    ]]).T
    return float(map_coordinates(field, coords, order=1, mode="nearest")[0])


def _in_domain(pt: NDArray, Lx: int, Ly: int, Lz: int) -> bool:
    return (0 <= pt[0] < Lx) and (0 <= pt[1] < Ly) and (0 <= pt[2] < Lz)


def _smooth_direction_field(dirs: NDArray, window: int = 3) -> NDArray:
    """
    平滑方向場以消除 pi-翻轉不連續。

    局部一致性修正：若相鄰向量點積 < 0，翻轉方向。
    使用逐層掃描（X 方向優先）。
    """
    smoothed = dirs.copy()
    Lx, Ly, Lz, _ = dirs.shape
    for x in range(1, Lx):
        dot = np.sum(smoothed[x] * smoothed[x - 1], axis=-1, keepdims=True)
        smoothed[x] = np.where(dot < 0, -smoothed[x], smoothed[x])
    return smoothed


def classify_path_morphology(path: NDArray) -> str:
    """
    判斷路徑的形態學類型。

    Returns:
        "arch":       路徑呈弧形（中間高，兩端低）
        "column":     路徑近似垂直線（Y 方向主導）
        "horizontal": 路徑近似水平線（X/Z 方向主導）
        "curve":      其他曲線形式
    """
    if len(path) < 3:
        return "curve"

    y = path[:, 1]
    peak_idx = np.argmax(y)
    y_range = y.max() - y.min()

    # 拱形：中間隆起，兩端較低
    if (0.15 * len(path) < peak_idx < 0.85 * len(path)
            and y[peak_idx] > y[0] + 0.5 * y_range
            and y[peak_idx] > y[-1] + 0.5 * y_range):
        return "arch"

    # 垂直柱：Y 方向位移 > 水平位移
    dy = abs(path[-1, 1] - path[0, 1])
    dx = np.linalg.norm(path[-1, [0, 2]] - path[0, [0, 2]])
    if dy > 2 * dx:
        return "column"
    if dx > 2 * dy:
        return "horizontal"
    return "curve"


def filter_arch_paths(paths: list[NDArray]) -> list[NDArray]:
    """過濾出弧形路徑（用於 GaudiStyle）"""
    return [p for p in paths if classify_path_morphology(p) == "arch"]


def filter_flow_paths(paths: list[NDArray]) -> list[NDArray]:
    """過濾出水平流動路徑（用於 ZahaStyle）"""
    return [p for p in paths if classify_path_morphology(p) in ("horizontal", "curve")]
