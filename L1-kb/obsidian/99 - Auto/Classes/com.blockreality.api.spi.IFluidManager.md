---
id: "java_api:com.blockreality.api.spi.IFluidManager"
type: class
tags: ["java", "api", "spi"]
---

# 🧩 com.blockreality.api.spi.IFluidManager

> [!info] 摘要
> Fluid Simulation SPI — 流體模擬管理介面。  <p>管理基於勢場的流體擴散系統（PFSF-Fluid），每個方塊儲存流體勢能、 類型與壓力值，水從高勢能流向低勢能（Jacobi 迭代）。  <p>流體系統預設關閉，由 {@code BRConfig.isFluidEnabled()} 控制。 啟用後透過 {@link ModuleRegistry#setFluidManager(IFluidManager)} 註冊實作。  @since 1.0.0

## 🔗 Related
- [[BRConfig]]
- [[Config]]
- [[IFluidManager]]
- [[ModuleRegistry]]
- [[ModuleRegistry#setFluidManager]]
- [[isFluidEnabled]]
- [[setFluidManager]]
