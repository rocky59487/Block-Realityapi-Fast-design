---
id: "java_api:com.blockreality.api.event.FluidBarrierBreachEvent"
type: class
tags: ["java", "api", "event"]
---

# 🧩 com.blockreality.api.event.FluidBarrierBreachEvent

> [!info] 摘要
> 流體屏障突破事件 — 結構崩塌開啟流體邊界時觸發。  <p>當 {@code CollapseManager} 處理 {@code HYDROSTATIC_PRESSURE} 類型的崩塌後， 在 FORGE event bus 上 post 此事件。{@code FluidGPUEngine} 監聽此事件， 將崩塌的固體體素轉為 AIR，讓流體可以湧入新開放的空間。  <h3>事件流程</h3> <pre> 1. 水壓累積 → PFSF failure_scan 偵測 HYDROSTATIC_PRESSURE 2. CollapseManager.triggerPFSFCollapse() 移除牆面 3. CollapseManager post FluidBarrierBreachEvent 4. FluidGPUEngine 監聽 → 將崩塌體素轉為 AIR 5. 下一 tick: 水

> [!tip] 資訊
> 🔼 Extends: Event

## 🔗 Related
- [[CollapseManager]]
- [[FluidBarrierBreachEvent]]
- [[FluidGPUEngine]]
- [[fail]]
- [[tick]]
- [[triggerPFSFCollapse]]
