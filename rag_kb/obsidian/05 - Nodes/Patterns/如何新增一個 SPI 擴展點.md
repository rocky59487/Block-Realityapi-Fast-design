---
id: "pattern:new-spi"
type: pattern
tags: ["pattern", "spi", "api", "howto"]
---

# 🧩 如何新增一個 SPI 擴展點

## 🪜 步驟
- 1. 在 api/spi/ 下建立接口 IxxxManager（以 I 開頭）。
- 2. 在 api/spi/ 下建立 DefaultxxxManager 預設實作。
- 3. 在 ModuleRegistry 中註冊接口與預設實作的綁定。
- 4. 在 api 的其他模組中使用 ModuleRegistry.get(IxxxManager.class) 取得實例。

> [!tip] 相關資訊
> 📎 Related Files:
>   - `Block Reality/api/src/main/java/com/blockreality/api/spi/ICableManager.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/spi/DefaultCableManager.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/spi/ModuleRegistry.java`

## 🔗 Related Notes
- [[DefaultCableManager]]
- [[ICableManager]]
- [[ModuleRegistry]]
