# L3-diffusion-solver — 通用擴散求解器

> 所屬：L1-api > L2-physics > L3-diffusion-solver

## 概述

所有物理域（Fluid/Thermal/Wind/EM）共用的 ∇·(σ∇φ) = f Poisson/擴散求解器。
透過 `DomainTranslator` 介面將各域物理量映射到通用 SoA 陣列。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `DiffusionSolver` | `api.physics.solver` | 通用 Jacobi/RBGS + Ghost Cell BC + 可變重力 |
| `DiffusionRegion` | `api.physics.solver` | 通用 SoA 容器（phi/conductivity/source/type） |
| `DiffusionRegionRegistry` | `api.physics.solver` | 通用區域管理（ConcurrentHashMap，每域一實例） |
| `DomainTranslator` | `api.physics.solver` | 域轉譯介面：populateRegion/interpretResults |

## 核心方法

### DiffusionSolver

| 方法 | 參數 | 回傳 | 說明 |
|------|------|------|------|
| `jacobiStep(region, rate, gravityWeight)` | DiffusionRegion, float, float | float (maxDelta) | 單步 Jacobi 迭代 |
| `rbgsStep(region, rate, gravityWeight)` | 同上 | float | Red-Black GS（~2× 收斂） |
| `solve(region, maxIter, rate, gravityWeight)` | +int maxIter | int (iterations) | Jacobi 收斂求解 |
| `rbgsSolve(region, maxIter, rate, gravityWeight)` | 同上 | int | RBGS 收斂求解 |
| `computeMaxResidual(region, gravityWeight)` | DiffusionRegion, float | float | 計算最大殘差 |

### DomainTranslator

| 方法 | 說明 |
|------|------|
| `populateRegion(region, level)` | 域 → 求解器：將物理量映射到 σ[], f[], φ[] |
| `interpretResults(region)` | 求解器 → 域：從 φ[] 導出域物理量 |
| `getGravityWeight()` | 0.0（Thermal/EM/Wind）或 1.0（Fluid） |
| `getDomainId()` | "fluid" / "thermal" / "wind" / "em" |
| `getDefaultDiffusionRate()` | 各域不同（Fluid 0.25, Thermal 0.35, EM 0.4） |

## 域映射表

| 域 | σ (conductivity) | f (source) | φ (phi) | gravity |
|---|---|---|---|---|
| Fluid | ρ-based | ρgh | 流體勢能 | 1.0 |
| Thermal | k/(ρc) | Q/(ρc) | 溫度 T | 0.0 |
| EM | σ_elec | -ρ_charge/ε | 電位 V | 0.0 |
| Wind | 1/ρ_air | ∇·u* | 壓力 p | 0.0 |

## 關聯接口

- 被 [ThermalEngine](../L2-thermal/L3-thermal-engine.md) 呼叫
- 被 [WindEngine](../L2-wind/L3-wind-engine.md) 呼叫
- 被 [EmEngine](../L2-em/L3-em-engine.md) 呼叫
- 設計靈感來自 [PFSF](L3-pfsf.md) 的 Jacobi/RBGS 求解器
