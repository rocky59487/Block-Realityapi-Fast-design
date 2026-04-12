---
id: "java_api:com.blockreality.api.spi.SPIVersion"
type: class
tags: ["java", "api", "spi"]
---

# 🧩 com.blockreality.api.spi.SPIVersion

> [!info] 摘要
> ★ Audit fix (API 設計師): SPI 介面版本標記。  <p>標記 SPI 介面的合約版本。{@link ModuleRegistry} 在註冊實作時 驗證版本相容性，防止舊版實作在新版介面上產生 AbstractMethodError。  <h3>版本規則</h3> <ul> <li>新增方法（帶 default 實作）：minor version bump (1.0 → 1.1)</li> <li>移除/修改既有方法簽名：major version bump (1.x → 2.0)</li> <li>實作者標記的版本必須 ≥ 介面的 major version</li> </ul>  <h3>使用範例</h3> <pre>{@code @SPIVersion(major = 1, minor = 1) public interface ICableManager { v

## 🔗 Related
- [[ICableManager]]
- [[ModuleRegistry]]
- [[SPIVersion]]
