# L3: PFSF-Fluid 流體模擬系統

## 概述
PFSF-Fluid 是建構於 Particle Field Simulation Framework (PFSF) 之上的流體物理引擎。透過勢場擴散 (Potential Field Diffusion) 計算壓力，並提供 GPU 加速 (Jacobi) 與 CPU 回退 (RBGS) 的雙軌求解器。

## 關鍵類別

| 類別 | 套件 | 說明 |
| --- | --- | --- |
| `FluidGPUEngine` | `com.blockreality.api.physics.fluid` | 實作 `IFluidManager` 的流體主引擎，負責排程與 GPU 資源管理 |
| `FluidCPUSolver` | `com.blockreality.api.physics.fluid` | CPU 端流體勢能求解器，採用 Red-Black Gauss-Seidel 迭代 |
| `FluidStructureCoupler`| `com.blockreality.api.physics.fluid`| 將流體邊界壓力匯出至結構分析引擎 |
| `FluidBarrierBreachEvent`| `com.blockreality.api.event` | 當崩塌發生時觸發，讓流體湧入被破壞的空間 |

## 核心方法

| 方法 | 參數 | 回傳值 | 說明 |
| --- | --- | --- | --- |
| `tick` | `ServerLevel, int` | `void` | 每 tick 推進流體模擬 |
| `notifyBarrierBreach`| `BlockPos` | `void` | 結構破壞時呼叫此方法，將特定方塊轉為空氣以容許流體通過 |
| `getFluidPressureAt`| `BlockPos` | `float` | 查詢特定位置的流體壓力 |

## 關聯接口
- `com.blockreality.api.spi.IFluidManager`
