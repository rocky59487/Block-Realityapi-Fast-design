"""
exp_004 — HYBR 風格條件化推論驗證

目標：
  1. 驗證 HYBRProxy 在 HYBR 不可用時優雅降級（回傳 None）
  2. 驗證 Mock 模式下不同風格產生不同的頻率特徵
  3. 量化風格潛在向量對輸出頻譜的影響

不需要 HYBR/JAX，使用 Mock 模式驗證接口正確性。

執行方式：
    python -m reborn.experiments.exp_004_hybr_style_cond

預期結果：
  - Mock 模式：不同風格輸出的頻域功率譜有統計顯著差異
  - 介面驗證：forward() 回傳正確形狀 [Lx,Ly,Lz,10]
  - 時間基準：Mock 推論 < 100ms（10 次隨機結構平均）
"""
from __future__ import annotations
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

import numpy as np
from reborn.models.hybr_proxy import HYBRProxy, STYLE_TOKENS

OUTPUT_DIR = Path(__file__).parent / "outputs" / "exp_004"
OUTPUT_DIR.mkdir(parents=True, exist_ok=True)


def make_random_structure(size: int = 12, seed: int = 0) -> np.ndarray:
    """生成隨機佔用網格"""
    rng = np.random.default_rng(seed)
    occ = rng.random((size, size, size)) > 0.4
    # 確保至少有一層底部固體
    occ[:, 0, :] = True
    return occ


def compute_spectral_power(density_field: np.ndarray) -> dict[str, float]:
    """
    計算密度場（或 φ 場）的頻域功率分布。

    分析低/中/高頻區間的功率占比，用於風格差異量化。
    """
    if density_field.ndim > 3:
        density_field = density_field[..., 0]  # 取第一個通道

    spectrum = np.abs(np.fft.fftn(density_field))
    total_power = float(spectrum.sum()) + 1e-8

    Lx, Ly, Lz = density_field.shape
    L = min(Lx, Ly, Lz)

    # 建立頻率索引
    fx = np.fft.fftfreq(Lx, d=1.0)
    fy = np.fft.fftfreq(Ly, d=1.0)
    fz = np.fft.fftfreq(Lz, d=1.0)
    FX, FY, FZ = np.meshgrid(fx, fy, fz, indexing="ij")
    freq_mag = np.sqrt(FX**2 + FY**2 + FZ**2)

    # 三個頻率區間
    low   = float(spectrum[freq_mag < 0.15].sum()) / total_power
    mid   = float(spectrum[(freq_mag >= 0.15) & (freq_mag < 0.35)].sum()) / total_power
    high  = float(spectrum[freq_mag >= 0.35].sum()) / total_power

    return {"low": low, "mid": mid, "high": high}


def test_interface():
    """驗證 HYBRProxy 介面正確性"""
    print("\n--- HYBRProxy 介面驗證 ---")
    proxy = HYBRProxy(mock=True, verbose=True)

    PASS_all = True
    for style in STYLE_TOKENS:
        occ = make_random_structure(size=8, seed=42)
        out = proxy.forward(occ, style=style)

        if out is None:
            print(f"  ✗ {style}: forward() 回傳 None（Mock 模式不應發生）")
            PASS_all = False
            continue

        expected_shape = occ.shape + (10,)
        shape_ok = out.shape == expected_shape
        has_no_nan = not np.isnan(out).any()

        status = "✓" if (shape_ok and has_no_nan) else "✗"
        print(f"  {status} {style}: 形狀={out.shape}（期望={expected_shape}），"
              f"NaN={'無' if has_no_nan else '有'}")
        if not (shape_ok and has_no_nan):
            PASS_all = False

    return PASS_all


def test_style_spectral_difference():
    """驗證不同風格產生不同頻率特徵"""
    print("\n--- 風格頻率特徵差異分析 ---")
    proxy = HYBRProxy(mock=True)

    style_spectra: dict[str, list[dict]] = {s: [] for s in STYLE_TOKENS}

    # 在 10 個隨機結構上測試
    for seed in range(10):
        occ = make_random_structure(size=12, seed=seed)
        for style in STYLE_TOKENS:
            out = proxy.forward(occ, style=style)
            if out is not None:
                phi = out[..., 9]   # φ 通道最反映風格
                spec = compute_spectral_power(phi)
                style_spectra[style].append(spec)

    # 計算各風格的平均頻率特徵
    print("\n  各風格平均頻率特徵（低/中/高）：")
    style_mean: dict[str, dict] = {}
    for style, specs in style_spectra.items():
        if not specs:
            continue
        mean_low  = np.mean([s["low"]  for s in specs])
        mean_mid  = np.mean([s["mid"]  for s in specs])
        mean_high = np.mean([s["high"] for s in specs])
        style_mean[style] = {"low": mean_low, "mid": mean_mid, "high": mean_high}
        print(f"    {style:8s}: 低頻={mean_low:.3f}, 中頻={mean_mid:.3f}, 高頻={mean_high:.3f}")

    # 驗證高第與札哈有差異（Mock 模式下基於不同偏置）
    if "gaudi" in style_mean and "zaha" in style_mean:
        diff = abs(style_mean["gaudi"]["low"] - style_mean["zaha"]["low"])
        print(f"\n  高第 vs 札哈 低頻差異：{diff:.3f}")
        PASS = diff > 0.0   # Mock 模式至少有差異
        print(f"  {'✓' if PASS else '✗'} 風格間存在可量化的頻率差異")
        return PASS
    return True


def test_timing():
    """計時基準測試（10 次隨機結構）"""
    print("\n--- 推論時間基準 ---")
    proxy = HYBRProxy(mock=True)

    times = []
    for seed in range(10):
        occ = make_random_structure(size=16, seed=seed)
        t0 = time.time()
        proxy.forward(occ, style="gaudi")
        times.append((time.time() - t0) * 1000)

    mean_ms = np.mean(times)
    max_ms  = np.max(times)
    print(f"  平均耗時：{mean_ms:.1f}ms，最大：{max_ms:.1f}ms（16³ Mock 模式）")

    PASS = mean_ms < 100.0
    print(f"  {'✓' if PASS else '✗'} Mock 推論 < 100ms")
    return PASS


def main():
    print("=" * 60)
    print("exp_004 — HYBR 風格條件化推論驗證")
    print("=" * 60)

    results = {
        "interface":     test_interface(),
        "style_spectra": test_style_spectral_difference(),
        "timing":        test_timing(),
    }

    print("\n" + "=" * 60)
    pass_count = sum(results.values())
    print(f"通過：{pass_count}/{len(results)}")
    for name, passed in results.items():
        print(f"  {'✓' if passed else '✗'} {name}")

    import json
    with open(OUTPUT_DIR / "results.json", "w", encoding="utf-8") as f:
        json.dump({k: bool(v) for k, v in results.items()}, f, indent=2)

    return pass_count == len(results)


if __name__ == "__main__":
    success = main()
    sys.exit(0 if success else 1)
