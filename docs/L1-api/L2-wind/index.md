# L2-wind — 風場引擎

> 所屬：L1-api > L2-wind

## 概述

混合架構風場模擬：獨立 advection 步（Stam 1999）+ 共用壓力 Poisson（DiffusionSolver）。
風壓透過 q=½ρv²Cd 耦合到 PFSF 結構引擎。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `WindEngine` | `api.physics.wind` | IWindManager 實作，Chorin 分裂法 tick |
| `WindTranslator` | `api.physics.wind` | 壓力投射步的 DomainTranslator |
| `WindAdvector` | `api.physics.wind` | 半隱式 Lagrangian 回溯法 + divergence + project |
| `WindConstants` | `api.physics.wind` | 空氣密度/黏度/拖曳係數/CFL |
| `IWindManager` | `api.spi` | SPI 介面 |

## Tick 流程（Chorin 分裂法）

1. `WindAdvector.advect()` — 對流步（三線性插值回溯）
2. `WindAdvector.computeDivergence()` — ∇·u* → source[]
3. `DiffusionSolver.rbgsSolve()` — 壓力 Poisson（共用！）
4. `WindAdvector.projectVelocity()` — u = u* - ∇p
