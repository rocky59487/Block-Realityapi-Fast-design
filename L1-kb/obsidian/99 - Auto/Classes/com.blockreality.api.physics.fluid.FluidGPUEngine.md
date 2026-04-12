---
id: "java_api:com.blockreality.api.physics.fluid.FluidGPUEngine"
type: class
tags: ["java", "api", "fluid"]
---

# 🧩 com.blockreality.api.physics.fluid.FluidGPUEngine

> [!info] 摘要
> 流體 GPU 引擎 — PFSF-Fluid 的主入口和 tick 迴圈協調器。  <p>實作 {@link IFluidManager} SPI，在 ServerTick 中執行流體模擬。 遵循 {@code PFSFEngine} 的 tick 預算管理和非同步管線模式。  <h3>Tick 迴圈結構</h3> <pre> Phase 1: 輪詢 GPU 結果（非阻塞） Phase 2: 迭代髒區域（受 tick 預算限制） 2a: 取得計算幀 2b: 上傳 / 稀疏更新 2c: 記錄 Jacobi dispatch 2d: 記錄壓力 + 邊界提取 2e: 非阻塞提交 Phase 3: 提取邊界壓力給 PFSF（<0.5ms） </pre>

> [!tip] 資訊
> 🔌 Implements: [[IFluidManager]]

## 🔗 Related
- [[FluidGPUEngine]]
- [[IFluidManager]]
- [[PFSFEngine]]
- [[dispatch]]
- [[tick]]
