---
id: "java_fd:com.blockreality.fastdesign.client.node.binding.FluidBinder"
type: class
tags: ["java", "fastdesign", "binding", "client-only", "node"]
---

# 🧩 com.blockreality.fastdesign.client.node.binding.FluidBinder

> [!info] 摘要
> 流體節點綁定器 — 將節點編輯器的流體參數綁定到運行時配置。  <p>實作 IBinder 模式：掃描節點圖中的流體節點， 將參數值推送到 {@link BRConfig} 和 {@link FluidConstants}。  <h3>綁定映射</h3> <ul> <li>FluidSimNode.enabled → BRConfig.setFluidEnabled()</li> <li>FluidSimNode.regionSize → BRConfig.setFluidMaxRegionSize()</li> <li>FluidSimNode.tickBudgetMs → BRConfig.setFluidTickBudgetMs()</li> <li>FluidPressureNode.couplingFactor → FluidConstants.PRESSURE_COUPLING

## 🔗 Related
- [[BRConfig]]
- [[Config]]
- [[FluidBinder]]
- [[FluidConstants]]
- [[FluidPressureNode]]
- [[FluidSimNode]]
- [[IBinder]]
- [[bind]]
- [[setFluidEnabled]]
- [[setFluidMaxRegionSize]]
- [[setFluidTickBudgetMs]]
- [[tick]]
