---
id: "java_api:com.blockreality.api.spi.IThermalManager"
type: class
tags: ["java", "api", "spi"]
---

# 🧩 com.blockreality.api.spi.IThermalManager

> [!info] 摘要
> Thermal Simulation SPI — 熱傳導模擬管理介面。  <p>管理基於通用擴散求解器的熱傳導系統。溫度場透過 Jacobi/RBGS 迭代擴散， 並與結構引擎耦合（熱應力 → PFSF source term）。  <p>系統預設關閉，由 {@code BRConfig.isThermalEnabled()} 控制。  @since 1.1.0

## 🔗 Related
- [[BRConfig]]
- [[Config]]
- [[IThermalManager]]
- [[isThermalEnabled]]
