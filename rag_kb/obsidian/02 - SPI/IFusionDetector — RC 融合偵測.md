---
id: "spi:IFusionDetector"
type: spi
tags: ["spi", "physics", "material", "rc", "fusion"]
---

# 🔌 IFusionDetector — RC 融合偵測

## 📝 說明
偵測混凝土與鋼筋方塊相鄰時是否應融合為 RC（鋼筋混凝土）。預設實作為 RCFusionDetector，比例固定 97% 混凝土 / 3% 鋼筋。

> [!info] Interface
> `com.blockreality.api.spi.IFusionDetector`

> [!info] 預設實作
> `com.blockreality.api.physics.RCFusionDetector`

> [!tip] 相關資訊
> 📎 Related Files:
>   - `Block Reality/api/src/main/java/com/blockreality/api/spi/IFusionDetector.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/physics/RCFusionDetector.java`
>   - `Block Reality/fastdesign/src/main/java/com/blockreality/fastdesign/client/node/impl/material/operation/RCFusionNode.java`

## 🔗 Related Notes
- [[IFusionDetector]]
- [[RCFusionDetector]]
- [[RCFusionNode]]
