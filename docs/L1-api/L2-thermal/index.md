# L2-thermal — 熱傳導引擎

> 所屬：L1-api > L2-thermal

## 概述

基於通用擴散求解器的熱傳導模擬。溫度場透過 RBGS 迭代擴散，
並與結構引擎耦合（熱應力 σ_th = α_exp × E × ΔT → PFSF source term）。

## 子文件

| 文件 | 類別 | 說明 |
|------|------|------|
| L3-thermal-engine | ThermalEngine, ThermalTranslator | 熱傳導引擎 + 域轉譯層 |
| L3-thermal-stress | ThermalStressCalculator | 熱應力計算 + PFSF 耦合 |

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `ThermalEngine` | `api.physics.thermal` | IThermalManager 實作，委託 DiffusionSolver |
| `ThermalTranslator` | `api.physics.thermal` | T→φ, α→σ, Q→f 映射 |
| `ThermalStressCalculator` | `api.physics.thermal` | σ_th = α_exp × E × ΔT |
| `ThermalConstants` | `api.physics.thermal` | 環境溫度/火焰/熔岩/冰點/沸點 |
| `ThermalProfile` | `api.material` | 12 種材料熱學屬性（k/c/α） |
| `IThermalManager` | `api.spi` | SPI 介面 |

## 系統預設關閉

`BRConfig.isThermalEnabled()` 預設 false，需明確啟用。
