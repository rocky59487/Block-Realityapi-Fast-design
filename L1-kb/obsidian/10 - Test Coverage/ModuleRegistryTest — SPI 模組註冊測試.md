---
id: "test:ModuleRegistryTest"
type: test_coverage
tags: ["test", "spi", "registry", "test_coverage"]
command: "./gradlew :api:test --tests 'com.blockreality.api.spi.ModuleRegistryTest'"
---

# 🧪 ModuleRegistryTest — SPI 模組註冊測試

## 📝 適用場景
測試 ModuleRegistry 對各 SPI 接口（ICableManager、ICuringManager 等）的註冊與替換。新增 SPI 接口或修改 ModuleRegistry 時必須執行。

> [!tip] 相關資訊
> ⌨️ Command: `./gradlew :api:test --tests 'com.blockreality.api.spi.ModuleRegistryTest'`
> 📎 Related Source:
>   - [[ModuleRegistry]]
>   - [[ICableManager]]
>   - [[ICuringManager]]

## 🔗 Related Notes
- [[ICableManager]]
- [[ICuringManager]]
- [[ModuleRegistry]]
- [[ModuleRegistryTest]]
- [[ring]]
