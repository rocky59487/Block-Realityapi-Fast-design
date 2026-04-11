---
id: "keyclass:ModuleRegistry"
type: key_class
tags: ["key-class", "spi", "registry", "core"]
fqn: "com.blockreality.api.spi.ModuleRegistry"
thread_safety: "read-only after init"
related_test: "com.blockreality.api.spi.ModuleRegistryTest"
---

# 🧩 ModuleRegistry 快速參考

> [!info] Quick Facts
> **FQN**: `com.blockreality.api.spi.ModuleRegistry`
> **Thread Safety**: read-only after init

## 📋 職責
所有 SPI 擴展點的統一註冊中心。透過 ModuleRegistry.get(IxxxManager.class) 取得預設或覆寫後的實作。線程安全：初始化後只讀。主要方法：register(Class<T>, T)、get(Class<T>)、override(Class<T>, T)。修改注意：新增 SPI 接口時必須在此處註冊，否則其他模組無法取得實例。

> [!tip] Metadata
> 🧪 Test: [[ModuleRegistryTest]]

## 🔗 Related Notes
- [[ModuleRegistry]]
- [[override]]
- [[register]]
