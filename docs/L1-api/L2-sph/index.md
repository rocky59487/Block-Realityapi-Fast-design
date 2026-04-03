# SPH 應力引擎

> 所屬：[L1-api](../index.md)

## 概述

SPH（Smoothed Particle Hydrodynamics）應力引擎為 Block Reality 提供爆炸衝擊波的應力傳播模擬。
採用 **Monaghan (1992) 立方樣條核心函數**，搭配 **Teschner (2003) 空間雜湊鄰域搜索**，
以真實的 SPH 粒子方法計算爆炸對結構方塊造成的應力損傷。

## 演算法

1. **密度求和**：ρᵢ = Σⱼ mⱼ W(|rᵢ - rⱼ|, h) — 使用 `SPHKernel.cubicSpline()`
2. **狀態方程**：Pᵢ = k (ρᵢ - ρ₀) + 爆炸衝量 — 簡化 Tait EOS
3. **壓力梯度力**：fᵢ = -Σⱼ mⱼ (Pᵢ/ρᵢ² + Pⱼ/ρⱼ²) ∇W — 使用 `SPHKernel.cubicSplineGradient()`
4. **材料正規化**：stressLevel = |fᵢ| × materialFactor / Rcomp → [0, 2]

## 觸發條件

Forge `ExplosionEvent.Start` 且爆炸半徑超過設定閾值（預設 5 格）。

## 配置參數

| 參數 | 預設值 | 範圍 | 說明 |
|------|--------|------|------|
| `sph_base_pressure` | 10.0 | 0.1-100.0 | 爆炸基礎壓力常數 |
| `sph_smoothing_length` | 2.5 | 1.0-5.0 | 平滑長度 h（核心支撐半徑 = 2h） |
| `sph_rest_density` | 1.0 | 0.1-5.0 | 靜止密度 ρ₀ |
| `sph_max_particles` | 200 | 10-2000 | 最大粒子數上限 |
| `sph_trigger_radius` | 5 | 1-32 | 觸發半徑（格數） |

## 子文檔

| 文檔 | 說明 |
|------|------|
| [L3-particle-system](L3-particle-system.md) | SPHStressEngine、SPHKernel、SpatialHashGrid 詳細文檔 |
