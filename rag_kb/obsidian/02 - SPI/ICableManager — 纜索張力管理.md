---
id: "spi:ICableManager"
type: spi
tags: ["spi", "physics", "cable", "tension"]
---

# 🔌 ICableManager — 纜索張力管理

## 📝 說明
管理纜索（cable）元素的張力狀態與更新。預設實作為 DefaultCableManager。相關事件為 CableTensionEvent。

> [!info] Interface
> `com.blockreality.api.spi.ICableManager`

> [!info] 預設實作
> `com.blockreality.api.spi.DefaultCableManager`

> [!tip] 相關資訊
> 📎 Related Files:
>   - `Block Reality/api/src/main/java/com/blockreality/api/spi/ICableManager.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/spi/DefaultCableManager.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/event/CableTensionEvent.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/physics/CableElement.java`

## 🔗 Related Notes
- [[CableElement]]
- [[CableTensionEvent]]
- [[DefaultCableManager]]
- [[ICableManager]]
- [[onEvent]]
