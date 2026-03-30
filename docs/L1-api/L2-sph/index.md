# SPH 粒子系統

> 所屬：[L1-api](../index.md)

## 概述

SPH（Smoothed Particle Hydrodynamics）粒子系統為 Block Reality 提供爆炸衝擊波的應力傳播模擬。
採用降級版距離衰減壓力模型（非完整 SPH 核心函數），以非同步方式計算爆炸對結構方塊造成的應力損傷，
並將結果回寫到主線程進行崩塌判定。

## 設計定位

此模組為 CTO 雙軌戰略中 Java 端的近似模型，在保持效能的前提下提供合理的物理行為。
觸發條件為 Forge `ExplosionEvent.Start` 且爆炸半徑超過設定閾值（預設 5 格）。

## 子文檔

| 文檔 | 說明 |
|------|------|
| [L3-particle-system](L3-particle-system.md) | SPHStressEngine 距離衰減應力引擎詳細文檔 |
