"""
diff_sdf_ops.py — JAX 可微分 SDF 基元操作

對應 utils/density_to_sdf.py 中的 numpy 版本，但完全以 JAX 實作，
支援 jax.grad 反向傳播。用於可微分風格管線與訓練。

約定：SDF 負值 = 內部，正值 = 外部。

參考：
  Inigo Quilez (2013), "Smooth Minimum" — 平滑聯集公式
  Hart (1996), "Sphere Tracing" — SDF 基本理論
"""
from __future__ import annotations

import jax
import jax.numpy as jnp


# ---------------------------------------------------------------------------
# 密度場 → SDF（可微分近似）
# ---------------------------------------------------------------------------

def density_to_sdf_diff(
    density: jnp.ndarray,
    iso: float = 0.5,
    sigma: float = 1.0,
) -> jnp.ndarray:
    """
    可微分的密度場到 SDF 轉換。

    由於 scipy.ndimage.distance_transform_edt 不可微分，
    此處使用高斯平滑 + 水平集近似：
        SDF ≈ -tanh(k * (density_smooth - iso))

    其中 k 控制等值面銳度。結果為近似 SDF，
    在梯度流通方面足以用於訓練。

    Args:
        density: [*spatial] 密度場，值域 [0, 1]
        iso:     等值面閾值
        sigma:   高斯平滑 sigma（體素）

    Returns:
        [*spatial] 近似 SDF（負=內部，正=外部）
    """
    # 頻域高斯平滑（可微分）
    if sigma > 0:
        smoothed = _gaussian_smooth_fft(density, sigma)
    else:
        smoothed = density

    # 水平集：負值 = 內部（density > iso），正值 = 外部
    k = 4.0 / (sigma + 0.5)  # 銳度與平滑度反比
    sdf = -jnp.tanh(k * (smoothed - iso))
    return sdf


def _gaussian_smooth_fft(
    field: jnp.ndarray,
    sigma: float,
) -> jnp.ndarray:
    """頻域高斯平滑（3D rFFT），完全可微分。"""
    shape = field.shape
    ndim = len(shape)

    # 建立高斯核心的頻率響應
    freq_response = jnp.ones(shape[:ndim-1] + (shape[-1] // 2 + 1,))
    for axis in range(ndim):
        n = shape[axis]
        if axis < ndim - 1:
            freqs = jnp.fft.fftfreq(n)
        else:
            freqs = jnp.fft.rfftfreq(n)

        # 高斯在頻域的響應：exp(-2π²σ²f²)
        gauss_1d = jnp.exp(-2.0 * jnp.pi**2 * sigma**2 * freqs**2)

        # 擴展維度以便廣播
        expand_shape = [1] * ndim
        expand_shape[axis] = -1
        if axis == ndim - 1:
            expand_shape[-1] = -1
        else:
            expand_shape[-1] = 1  # rfft 軸
        gauss_1d = gauss_1d.reshape(expand_shape[:ndim-1] + [expand_shape[-1]])

        # 對 rFFT 輸出形狀廣播
        if axis < ndim - 1:
            gauss_1d = gauss_1d.reshape(
                *([1]*axis), n, *([1]*(ndim - 2 - axis)), 1
            )
        else:
            gauss_1d = gauss_1d.reshape(
                *([1]*(ndim-1)), shape[-1] // 2 + 1
            )
        freq_response = freq_response * gauss_1d

    # 應用濾波
    ft = jnp.fft.rfftn(field)
    filtered = jnp.fft.irfftn(ft * freq_response, s=shape)
    return filtered


# ---------------------------------------------------------------------------
# SDF 布爾操作（Inigo Quilez 公式）
# ---------------------------------------------------------------------------

def smooth_union(
    a: jnp.ndarray,
    b: jnp.ndarray,
    k: float = 0.3,
) -> jnp.ndarray:
    """
    平滑聯集（Smooth Union / smin）。

    公式：h = clip(0.5 + 0.5*(b-a)/k, 0, 1)
           d = a*(1-h) + b*h - k*h*(1-h)

    完全可微分。k 越大 → 混合越平滑。
    """
    h = jnp.clip(0.5 + 0.5 * (b - a) / (k + 1e-8), 0.0, 1.0)
    return a * (1.0 - h) + b * h - k * h * (1.0 - h)


def smooth_subtraction(
    a: jnp.ndarray,
    b: jnp.ndarray,
    k: float = 0.3,
) -> jnp.ndarray:
    """平滑差集：A 減去 B。"""
    return -smooth_union(-a, b, k)


def smooth_intersection(
    a: jnp.ndarray,
    b: jnp.ndarray,
    k: float = 0.3,
) -> jnp.ndarray:
    """平滑交集。"""
    return -smooth_union(-a, -b, k)


# ---------------------------------------------------------------------------
# SDF 幾何基元
# ---------------------------------------------------------------------------

def sdf_sphere(
    shape: tuple[int, ...],
    center: jnp.ndarray,
    radius: float,
) -> jnp.ndarray:
    """球體 SDF：d = |p - c| - r"""
    coords = _make_grid(shape)  # [*shape, 3]
    dist = jnp.linalg.norm(coords - center, axis=-1)
    return dist - radius


def sdf_cylinder(
    shape: tuple[int, ...],
    center_xz: jnp.ndarray,
    radius: float,
    y_range: tuple[float, float] = (0.0, 1e6),
) -> jnp.ndarray:
    """圓柱體 SDF（沿 Y 軸）。"""
    coords = _make_grid(shape)
    xz = coords[..., [0, 2]]
    dist_xz = jnp.linalg.norm(xz - center_xz, axis=-1) - radius
    y = coords[..., 1]
    dist_y = jnp.maximum(y_range[0] - y, y - y_range[1])
    return jnp.maximum(dist_xz, dist_y)


def sdf_hyperboloid(
    shape: tuple[int, ...],
    center: jnp.ndarray,
    a: float,
    c: float,
) -> jnp.ndarray:
    """
    單葉雙曲面 SDF（高第柱子形式）。

    隱式方程：x² + z² - (y/c)² - a² = 0
    近似 SDF：f(p) = sqrt(x² + z²) - sqrt(a² + y²/c²)
    """
    coords = _make_grid(shape)
    dx = coords[..., 0] - center[0]
    dy = coords[..., 1] - center[1]
    dz = coords[..., 2] - center[2]
    r_xz = jnp.sqrt(dx**2 + dz**2 + 1e-8)
    r_hyp = jnp.sqrt(a**2 + dy**2 / (c**2 + 1e-8))
    return r_xz - r_hyp


def sdf_catenary_arch(
    shape: tuple[int, ...],
    p0: jnp.ndarray,
    p1: jnp.ndarray,
    a_param: float,
    thickness: float,
) -> jnp.ndarray:
    """
    懸鏈線拱 SDF。

    懸鏈線方程：y = a·cosh(x/a) - a
    SDF = |最近點距離| - thickness

    在 2D 垂直平面上計算（XZ → 水平跨距，Y → 高度）。
    """
    coords = _make_grid(shape)  # [*shape, 3]

    # 水平方向
    horiz = p1 - p0
    horiz = horiz.at[1].set(0.0)  # 僅 XZ 平面
    span = jnp.linalg.norm(horiz) + 1e-8
    horiz_unit = horiz / span

    # 局部座標
    delta = coords - p0
    u = jnp.sum(delta * horiz_unit, axis=-1)  # 沿水平方向
    v = delta[..., 1]  # 高度

    # 懸鏈線曲線（中心化）
    u_centered = u - span / 2.0
    a = jnp.maximum(a_param, 0.5)
    catenary_y = a * (jnp.cosh(u_centered / a) - 1.0)

    # 距離（簡化：只考慮 y 方向）
    dist_to_curve = jnp.sqrt((v - catenary_y)**2 + 1e-4)

    # 管狀 SDF（在拱內為負）
    sdf = dist_to_curve - thickness

    # 只在跨距範圍內有效
    in_span = (u >= -0.5) & (u <= span + 0.5)
    sdf = jnp.where(in_span, sdf, sdf + 100.0)

    return sdf


# ---------------------------------------------------------------------------
# 工具函式
# ---------------------------------------------------------------------------

def _make_grid(shape: tuple[int, ...]) -> jnp.ndarray:
    """建立 3D 座標網格 [Lx, Ly, Lz, 3]。"""
    ranges = [jnp.arange(s, dtype=jnp.float32) for s in shape[:3]]
    grids = jnp.meshgrid(*ranges, indexing="ij")
    return jnp.stack(grids, axis=-1)


def blend_sdf(
    sdf_a: jnp.ndarray,
    sdf_b: jnp.ndarray,
    alpha: float = 0.5,
) -> jnp.ndarray:
    """線性混合兩個 SDF 場。alpha=0 → 純 A，alpha=1 → 純 B。"""
    return (1.0 - alpha) * sdf_a + alpha * sdf_b
