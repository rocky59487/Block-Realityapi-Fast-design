"""
density_to_sdf.py — 密度場轉換為符號距離場（SDF）工具

支援兩種方式：
  1. 閾值 + 距離轉換（scipy.ndimage，純 CPU）
  2. 平滑等值面（RBF 插值，較慢但連續）

輸出 SDF 慣例：
  - SDF < 0：物件內部（密實材料）
  - SDF > 0：物件外部（空氣）
  - SDF = 0：等值面（表面）
"""
from __future__ import annotations
import numpy as np
from numpy.typing import NDArray
from scipy.ndimage import distance_transform_edt, gaussian_filter


def density_to_sdf_threshold(
    density: NDArray,
    iso: float = 0.5,
    smooth_sigma: float = 0.5,
) -> NDArray:
    """
    最快路徑：閾值切割 + 距離轉換。

    適用於 topology optimizer 輸出的密度場。
    與 NurbsExporter 的 `smoothing=0.0`（Greedy Mesh）相容。

    Args:
        density: float32[L,L,L] — 密度場 ∈ [0,1]
        iso:     等值面閾值（預設 0.5）
        smooth_sigma: 前處理高斯平滑 sigma（0.0 = 不平滑）

    Returns:
        float32[L,L,L] — SDF（負值 = 內部）
    """
    d = density.astype(np.float32)
    if smooth_sigma > 0.0:
        d = gaussian_filter(d, sigma=smooth_sigma)

    inside = d >= iso   # True = 材料，False = 空氣

    # 計算到「另一側」的距離
    dist_inside = distance_transform_edt(inside)    # 到空氣的距離
    dist_outside = distance_transform_edt(~inside)  # 到材料的距離

    # SDF：內部為負，外部為正
    sdf = dist_outside - dist_inside
    return sdf.astype(np.float32)


def density_to_sdf_smooth(
    density: NDArray,
    iso: float = 0.5,
    smooth_sigma: float = 1.0,
    offset: float = 0.0,
) -> NDArray:
    """
    平滑路徑：先高斯平滑再轉距離場。

    與 NurbsExporter 的 `smoothing > 0`（Dual Contouring）相容，
    提供更平滑的等值面法向量。

    Args:
        density:      float32[L,L,L]
        iso:          等值面閾值
        smooth_sigma: 高斯平滑程度（越大越平滑，推薦 0.8–2.0）
        offset:       SDF 偏移量（正值膨脹，負值收縮）

    Returns:
        float32[L,L,L] — 平滑 SDF
    """
    d = gaussian_filter(density.astype(np.float64), sigma=smooth_sigma)
    sdf = density_to_sdf_threshold(d.astype(np.float32), iso=iso)
    if offset != 0.0:
        sdf -= offset
    return sdf


def sdf_smooth_union(sdf_a: NDArray, sdf_b: NDArray, k: float = 0.3) -> NDArray:
    """
    平滑聯集（smooth union）SDF 操作。

    公式（Inigo Quilez）：
        h = clamp(0.5 + 0.5*(b-a)/k, 0, 1)
        d = mix(b, a, h) - k*h*(1-h)

    Args:
        sdf_a, sdf_b: shape (...) — 兩個 SDF 場
        k:            平滑半徑（越大越平滑）

    Returns:
        shape (...) — 聯集 SDF
    """
    h = np.clip(0.5 + 0.5 * (sdf_b - sdf_a) / k, 0.0, 1.0)
    return sdf_a * (1.0 - h) + sdf_b * h - k * h * (1.0 - h)


def sdf_smooth_subtraction(sdf_a: NDArray, sdf_b: NDArray, k: float = 0.3) -> NDArray:
    """
    平滑差集（smooth subtraction）SDF 操作。
    從 a 中挖去 b。

    Returns:
        shape (...) — 差集 SDF
    """
    h = np.clip(0.5 - 0.5 * (sdf_a + sdf_b) / k, 0.0, 1.0)
    return sdf_a * (1.0 - h) - sdf_b * h + k * h * (1.0 - h)


def sdf_smooth_intersection(sdf_a: NDArray, sdf_b: NDArray, k: float = 0.3) -> NDArray:
    """
    平滑交集（smooth intersection）SDF 操作。
    保留 a 與 b 的共同部分。
    """
    h = np.clip(0.5 - 0.5 * (sdf_b - sdf_a) / k, 0.0, 1.0)
    return sdf_a * (1.0 - h) + sdf_b * h + k * h * (1.0 - h)


def sdf_sphere(grid_shape: tuple[int, int, int], center: NDArray, radius: float) -> NDArray:
    """
    建立球體 SDF。

    Args:
        grid_shape: (Lx,Ly,Lz)
        center:     (3,) — 球心座標（體素）
        radius:     球半徑（體素）

    Returns:
        float32[Lx,Ly,Lz]
    """
    Lx, Ly, Lz = grid_shape
    xx, yy, zz = np.mgrid[0:Lx, 0:Ly, 0:Lz].astype(np.float32)
    cx, cy, cz = float(center[0]), float(center[1]), float(center[2])
    return np.sqrt((xx - cx)**2 + (yy - cy)**2 + (zz - cz)**2) - radius


def sdf_cylinder(
    grid_shape: tuple[int, int, int],
    axis_start: NDArray,
    axis_end: NDArray,
    radius: float,
) -> NDArray:
    """
    建立無限長圓柱 SDF（沿給定軸線）。
    用於高第雙曲面柱的基礎原始體。
    """
    Lx, Ly, Lz = grid_shape
    pts = np.stack(np.mgrid[0:Lx, 0:Ly, 0:Lz], axis=-1).astype(np.float32)  # [Lx,Ly,Lz,3]
    p0 = axis_start.astype(np.float32)
    axis = (axis_end - axis_start).astype(np.float32)
    axis_len = np.linalg.norm(axis)
    if axis_len < 1e-8:
        return np.full(grid_shape, 1e6, dtype=np.float32)
    axis_unit = axis / axis_len
    v = pts - p0                          # [Lx,Ly,Lz,3]
    proj = (v * axis_unit).sum(axis=-1, keepdims=True) * axis_unit  # 在軸上的投影
    perp = v - proj                       # 垂直於軸的分量
    dist = np.linalg.norm(perp, axis=-1) - radius
    return dist.astype(np.float32)


def sdf_hyperboloid(
    grid_shape: tuple[int, int, int],
    center: NDArray,
    a: float,
    c: float,
) -> NDArray:
    """
    單葉雙曲面 SDF 近似：H(x,y,z) = x² + y² - z²/c² - a²

    Args:
        center: (3,) — 中心座標（體素）
        a:      腰部半徑（體素）
        c:      高度參數（體素）

    Returns:
        float32[Lx,Ly,Lz] — 近似 SDF（注意：非精確距離，用於 smin 混合）
    """
    Lx, Ly, Lz = grid_shape
    xx, yy, zz = np.mgrid[0:Lx, 0:Ly, 0:Lz].astype(np.float32)
    cx, cy, cz = float(center[0]), float(center[1]), float(center[2])
    x = xx - cx
    y = yy - cy
    z = zz - cz
    # 隱式方程式作為近似 SDF
    return x**2 + y**2 - (z**2) / (c**2 + 1e-6) - a**2


def sdf_catenary_arch(
    grid_shape: tuple[int, int, int],
    p0: NDArray,
    p1: NDArray,
    a_param: float = 5.0,
    thickness: float = 1.5,
) -> NDArray:
    """
    懸鏈線拱形 SDF（2.5D，沿 XZ 平面）。

    懸鏈線方程：y = a * cosh(x/a)

    Args:
        p0, p1:     拱形起點與終點（體素座標）
        a_param:    懸鏈線曲率參數（越小越陡）
        thickness:  拱形截面半徑（體素）

    Returns:
        float32[Lx,Ly,Lz]
    """
    Lx, Ly, Lz = grid_shape
    pts = np.stack(np.mgrid[0:Lx, 0:Ly, 0:Lz], axis=-1).astype(np.float64)

    # 建立局部座標系（p0→p1 為 x 軸，垂直為 y 軸）
    axis = (p1 - p0).astype(np.float64)
    span = np.linalg.norm(axis[::[0, 2]])   # 水平跨距（忽略 y）
    if span < 1e-6:
        return np.full(grid_shape, 1e6, dtype=np.float32)

    # 計算每個體素到懸鏈線的最近距離（使用參數化投影）
    # 簡化：在弧所在的 XZ 平面計算 2D 距離
    mid = (p0 + p1) / 2.0
    x_local = (pts - mid)[..., 0]
    z_local = (pts - mid)[..., 2]
    y_local = (pts - mid)[..., 1]

    # 懸鏈線值（在水平跨度範圍內）
    t = np.linspace(-span / 2, span / 2, max(int(span * 4), 32))
    cat_x = t
    cat_z = a_param * np.cosh(t / a_param) - a_param   # 最低點在 z=0

    # 對每個體素找最近懸鏈線點
    cat_pts = np.stack([cat_x, cat_z], axis=-1)         # [N,2]
    query = np.stack([x_local.ravel(), z_local.ravel()], axis=-1)  # [M,2]

    # 向量化最近鄰（batch norm）
    diff = query[:, None, :] - cat_pts[None, :, :]       # [M,N,2]
    dist_2d = np.min(np.linalg.norm(diff, axis=-1), axis=-1)   # [M]
    dist_2d = dist_2d.reshape(grid_shape)

    # 加入 y 方向距離（拱形沿 y 方向有厚度）
    dist_3d = np.sqrt(dist_2d**2 + y_local**2).astype(np.float32)
    return dist_3d - thickness
