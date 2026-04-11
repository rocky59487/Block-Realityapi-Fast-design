---
id: "spi:IVS2Bridge"
type: spi
tags: ["spi", "vs2", "valkyrien-skies", "mod-compat"]
---

# 🔌 IVS2Bridge — Valkyrien Skies 2 橋接

## 📝 說明
與 Valkyrien Skies 2 模組的橋接接口。預設為 NoOpVS2Bridge（未安裝 VS2 時無操作）。若偵測到 VS2，會切換為 VS2ShipBridge。

> [!info] Interface
> `com.blockreality.api.spi.IVS2Bridge`

> [!info] 預設實作
> `com.blockreality.api.spi.NoOpVS2Bridge`

> [!tip] 相關資訊
> 📎 Related Files:
>   - `Block Reality/api/src/main/java/com/blockreality/api/spi/IVS2Bridge.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/spi/NoOpVS2Bridge.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/vs2/VS2ShipBridge.java`

## 🔗 Related Notes
- [[IVS2Bridge]]
- [[NoOpVS2Bridge]]
- [[VS2ShipBridge]]
