"""
visualization.py — Reborn 純 CPU 視覺化工具（無需 GPU）

使用 matplotlib 輸出：
  - 密度場三平面切面圖
  - 應力熱圖
  - SDF 等值線圖
  - 應力路徑疊加圖
  - 收斂性曲線
"""
from __future__ import annotations
import numpy as np
from pathlib import Path
from numpy.typing import NDArray

try:
    import matplotlib
    matplotlib.use("Agg")    # 無顯示器模式
    import matplotlib.pyplot as plt
    import matplotlib.colors as mcolors
    _HAS_MPL = True
except ImportError:
    _HAS_MPL = False


def _require_matplotlib():
    if not _HAS_MPL:
        raise ImportError("matplotlib 未安裝，請執行：pip install matplotlib")


# ---------------------------------------------------------------------------
# 密度場視覺化
# ---------------------------------------------------------------------------

def plot_density_slices(
    density: NDArray,
    title: str = "density",
    output_path: str | Path | None = None,
    cmap: str = "gray_r",
    threshold: float | None = 0.5,
) -> None:
    """
    繪製密度場的三個中間平面切面。

    Args:
        density:      float32[Lx,Ly,Lz] — 密度場 ∈ [0,1]
        title:        圖形標題
        output_path:  輸出路徑（None = 使用 plt.show()）
        cmap:         顏色映射（預設 gray_r：黑=密實，白=空氣）
        threshold:    若指定，疊加等值線
    """
    _require_matplotlib()
    Lx, Ly, Lz = density.shape
    fig, axes = plt.subplots(1, 3, figsize=(13, 4))

    slices = [
        (density[:, Ly // 2, :], "XZ（Y 中間）", "X", "Z"),
        (density[Lx // 2, :, :], "YZ（X 中間）", "Y", "Z"),
        (density[:, :, Lz // 2], "XY（Z 中間）", "X", "Y"),
    ]
    for ax, (sl, sub_title, xlabel, ylabel) in zip(axes, slices):
        im = ax.imshow(sl.T, origin="lower", cmap=cmap, vmin=0, vmax=1)
        if threshold is not None:
            ax.contour(sl.T, levels=[threshold], colors="red", linewidths=0.8)
        ax.set_title(sub_title)
        ax.set_xlabel(xlabel)
        ax.set_ylabel(ylabel)
        plt.colorbar(im, ax=ax, fraction=0.046, pad=0.04)

    fig.suptitle(title, fontsize=13, y=1.01)
    plt.tight_layout()
    _save_or_show(fig, output_path)


def plot_density_3d(
    density: NDArray,
    threshold: float = 0.5,
    title: str = "topology 3D",
    output_path: str | Path | None = None,
) -> None:
    """
    使用 mpl_toolkits.mplot3d 繪製密度場等值面（輕量 3D）。

    注意：大於 16³ 的網格建議改用 plot_density_slices 以節省時間。
    """
    _require_matplotlib()
    from mpl_toolkits.mplot3d import Axes3D  # noqa: F401
    fig = plt.figure(figsize=(7, 6))
    ax = fig.add_subplot(111, projection="3d")

    solid = density >= threshold
    xs, ys, zs = np.where(solid)
    ax.scatter(xs, ys, zs, c=density[solid], cmap="hot", s=4, alpha=0.6)
    ax.set_xlabel("X")
    ax.set_ylabel("Y")
    ax.set_zlabel("Z")
    ax.set_title(title)
    plt.tight_layout()
    _save_or_show(fig, output_path)


# ---------------------------------------------------------------------------
# 應力熱圖
# ---------------------------------------------------------------------------

def plot_stress_heatmap(
    sigma_vm: NDArray,
    density: NDArray | None = None,
    title: str = "von Mises stress",
    output_path: str | Path | None = None,
    axis: int = 1,
) -> None:
    """
    繪製馮米塞斯等效應力熱圖（中間平面切面）。

    Args:
        sigma_vm: float32[Lx,Ly,Lz] — 馮米塞斯應力
        density:  float32[Lx,Ly,Lz] — 若提供，低密度區域遮罩
        axis:     切面法線方向（0=X, 1=Y, 2=Z）
        output_path: 輸出路徑
    """
    _require_matplotlib()
    mid = sigma_vm.shape[axis] // 2
    sl = np.take(sigma_vm, mid, axis=axis)

    fig, ax = plt.subplots(figsize=(7, 5))
    im = ax.imshow(
        sl.T, origin="lower",
        cmap="RdYlBu_r",
        vmin=0, vmax=np.percentile(sigma_vm[sigma_vm > 0], 99) if np.any(sigma_vm > 0) else 1,
    )
    if density is not None:
        mask_sl = np.take(density, mid, axis=axis) < 0.3
        ax.contourf(mask_sl.T, levels=[0.5, 1.5], colors=["white"], alpha=0.7)

    plt.colorbar(im, ax=ax, label="σ_VM (歸一化)")
    ax.set_title(title)
    ax.set_xlabel(["Y/Z", "X/Z", "X/Y"][axis])
    plt.tight_layout()
    _save_or_show(fig, output_path)


# ---------------------------------------------------------------------------
# SDF 等值線圖
# ---------------------------------------------------------------------------

def plot_sdf_contours(
    sdf: NDArray,
    title: str = "SDF",
    output_path: str | Path | None = None,
    n_levels: int = 10,
    axis: int = 1,
) -> None:
    """
    繪製 SDF 等值線圖（中間平面切面）。

    SDF < 0（內部）用暖色，SDF > 0（外部）用冷色，等值面 SDF=0 用粗黑線。
    """
    _require_matplotlib()
    mid = sdf.shape[axis] // 2
    sl = np.take(sdf, mid, axis=axis)

    fig, ax = plt.subplots(figsize=(7, 5))
    vmax = np.percentile(np.abs(sdf), 90)
    norm = mcolors.TwoSlopeNorm(vmin=-vmax, vcenter=0, vmax=vmax)
    im = ax.imshow(sl.T, origin="lower", cmap="RdBu", norm=norm)
    ax.contour(sl.T, levels=[0.0], colors="black", linewidths=1.5)

    levels_neg = np.linspace(-vmax, 0, n_levels + 1)[1:]
    levels_pos = np.linspace(0, vmax, n_levels + 1)[1:]
    ax.contour(sl.T, levels=levels_neg, colors="tomato", linewidths=0.4, alpha=0.6)
    ax.contour(sl.T, levels=levels_pos, colors="steelblue", linewidths=0.4, alpha=0.6)

    plt.colorbar(im, ax=ax, label="SDF 值（體素）")
    ax.set_title(title)
    plt.tight_layout()
    _save_or_show(fig, output_path)


# ---------------------------------------------------------------------------
# 應力路徑疊加圖
# ---------------------------------------------------------------------------

def plot_stress_paths(
    density: NDArray,
    paths: list[NDArray],
    title: str = "principal stress paths",
    output_path: str | Path | None = None,
    axis: int = 1,
) -> None:
    """
    在密度場切面上疊加主應力路徑軌跡。

    Args:
        density: float32[Lx,Ly,Lz]
        paths:   list of (N,3) arrays — 主應力路徑座標（體素）
        axis:    投影平面法線
    """
    _require_matplotlib()
    mid = density.shape[axis] // 2
    sl = np.take(density, mid, axis=axis)

    fig, ax = plt.subplots(figsize=(8, 6))
    ax.imshow(sl.T, origin="lower", cmap="gray_r", vmin=0, vmax=1, alpha=0.8)

    # 投影路徑到 2D 平面
    dim_a, dim_b = [(1, 2), (0, 2), (0, 1)][axis]
    cmap_path = plt.get_cmap("plasma")
    for i, path in enumerate(paths):
        color = cmap_path(i / max(len(paths), 1))
        xs = path[:, dim_a]
        ys = path[:, dim_b]
        ax.plot(xs, ys, color=color, linewidth=1.2, alpha=0.85)
        ax.scatter(xs[0], ys[0], color=color, s=20, zorder=5)

    ax.set_title(f"{title}（{len(paths)} 條路徑，軸={axis}）")
    ax.set_xlabel(["Y/Z", "X/Z", "X/Y"][axis])
    plt.tight_layout()
    _save_or_show(fig, output_path)


# ---------------------------------------------------------------------------
# 收斂性曲線
# ---------------------------------------------------------------------------

def plot_convergence(
    compliance_history: list[float],
    vol_frac_history: list[float] | None = None,
    title: str = "SIMP Convergence",
    output_path: str | Path | None = None,
) -> None:
    """
    繪製 SIMP 最佳化收斂性曲線。

    左軸：合規性（compliance，越低越好）
    右軸：體積分率（vol frac，應收斂至目標值）
    """
    _require_matplotlib()
    fig, ax1 = plt.subplots(figsize=(8, 4))

    iters = list(range(1, len(compliance_history) + 1))
    ax1.semilogy(iters, compliance_history, "b-o", markersize=3, linewidth=1.5, label="合規性")
    ax1.set_xlabel("迭代次數")
    ax1.set_ylabel("合規性 C（對數尺度）", color="blue")
    ax1.tick_params(axis="y", labelcolor="blue")

    if vol_frac_history is not None:
        ax2 = ax1.twinx()
        ax2.plot(iters, vol_frac_history, "r--s", markersize=3, linewidth=1.2, label="體積分率")
        ax2.set_ylabel("體積分率", color="red")
        ax2.tick_params(axis="y", labelcolor="red")
        ax2.set_ylim(0, 1)

    ax1.set_title(title)
    ax1.grid(True, alpha=0.3)
    ax1.legend(loc="upper right")
    plt.tight_layout()
    _save_or_show(fig, output_path)


def plot_simp_comparison(
    results: dict[str, dict],
    output_path: str | Path | None = None,
) -> None:
    """
    多方法 SIMP 比較圖（用於 exp_002）。

    Args:
        results: {"FEM-SIMP": {"compliance": [...], "time_s": 120.0}, ...}
    """
    _require_matplotlib()
    fig, axes = plt.subplots(1, 2, figsize=(12, 4))

    for name, data in results.items():
        axes[0].semilogy(data["compliance"], label=name)

    axes[0].set_xlabel("迭代次數")
    axes[0].set_ylabel("合規性（對數）")
    axes[0].set_title("收斂性比較")
    axes[0].legend()
    axes[0].grid(True, alpha=0.3)

    # 最終合規性與執行時間
    methods = list(results.keys())
    final_c = [results[m]["compliance"][-1] for m in methods]
    times = [results[m].get("time_s", 0.0) for m in methods]

    x_pos = np.arange(len(methods))
    axes[1].bar(x_pos, final_c, color=["steelblue", "tomato", "green"][:len(methods)])
    ax_t = axes[1].twinx()
    ax_t.plot(x_pos, times, "k^--", markersize=8, label="時間 (s)")
    axes[1].set_xticks(x_pos)
    axes[1].set_xticklabels(methods, rotation=15)
    axes[1].set_ylabel("最終合規性")
    ax_t.set_ylabel("執行時間 (s)")
    axes[1].set_title("效能比較")

    plt.suptitle("SIMP 方法比較", fontsize=13)
    plt.tight_layout()
    _save_or_show(fig, output_path)


# ---------------------------------------------------------------------------
# 輔助函式
# ---------------------------------------------------------------------------

def _save_or_show(fig: "plt.Figure", output_path: str | Path | None) -> None:
    """儲存至檔案或顯示（取決於 output_path）"""
    if output_path is not None:
        output_path = Path(output_path)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        fig.savefig(str(output_path), dpi=150, bbox_inches="tight")
    else:
        plt.show()
    plt.close(fig)


def save_density_npz(
    density: NDArray,
    output_path: str | Path,
    metadata: dict | None = None,
) -> None:
    """
    儲存密度場為 .npz 格式（快速載入）。

    包含可選的元數據（超參數、收斂狀態等）。
    """
    output_path = Path(output_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    kwargs = {"density": density.astype(np.float32)}
    if metadata is not None:
        for k, v in metadata.items():
            kwargs[f"meta_{k}"] = np.array(v) if not isinstance(v, np.ndarray) else v
    np.savez_compressed(str(output_path), **kwargs)


def load_density_npz(path: str | Path) -> tuple[NDArray, dict]:
    """載入 save_density_npz 儲存的密度場"""
    data = np.load(str(path))
    density = data["density"]
    meta = {k[5:]: data[k].item() if data[k].ndim == 0 else data[k]
            for k in data.files if k.startswith("meta_")}
    return density, meta
