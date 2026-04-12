---
id: "spi:IFluidManager"
type: spi
tags: ["spi", "fluid", "gpu", "physics"]
---

# 🔌 IFluidManager — 流體模擬管理

## 📝 說明
流體模擬的中央管理器。預設實作為 FluidGPUEngine。流體系統預設關閉（BRConfig.isFluidEnabled() == false）。

> [!info] Interface
> `com.blockreality.api.spi.IFluidManager`

> [!info] 預設實作
> `com.blockreality.api.physics.fluid.FluidGPUEngine`

> [!tip] 相關資訊
> 📎 Related Files:
>   - `Block Reality/api/src/main/java/com/blockreality/api/spi/IFluidManager.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/physics/fluid/FluidGPUEngine.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/config/BRConfig.java`

## 🔗 Related Notes
- [[BRConfig]]
- [[Config]]
- [[FluidGPUEngine]]
- [[IFluidManager]]
- [[isFluidEnabled]]
