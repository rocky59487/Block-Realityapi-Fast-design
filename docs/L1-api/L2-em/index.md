# L2-em — 電磁場引擎

> 所屬：L1-api > L2-em

## 概述

基於通用擴散求解器的電位場模擬。∇²φ = -ρ_charge/ε 求解電位，
E = -∇φ 計算電場，J = σE 計算電流密度，P = J²/σ 計算 Joule 加熱。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `EmEngine` | `api.physics.em` | IElectromagneticManager 實作 |
| `EmTranslator` | `api.physics.em` | V→φ, σ_elec→σ, ρ_charge→f 映射 |
| `LightningPathfinder` | `api.physics.em` | ∇φ 梯度下降找最短放電路徑 |
| `EmConstants` | `api.physics.em` | 介電常數/閃電參數 |
| `ElectricalProfile` | `api.material` | 12 種材料電導率/擊穿電場/電阻率 |
| `IElectromagneticManager` | `api.spi` | SPI 介面 |
