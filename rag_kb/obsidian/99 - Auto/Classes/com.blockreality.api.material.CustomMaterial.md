---
id: "java_api:com.blockreality.api.material.CustomMaterial"
type: class
tags: ["java", "api", "material"]
---

# 🧩 com.blockreality.api.material.CustomMaterial

> [!info] 摘要
> 自訂材料 — v3fix 合規的 Builder Pattern 實現。  特色： - Builder Pattern 提供流暢的 API：CustomMaterial.builder("my_mat") .rcomp(50).rtens(25).rshear(15).density(2400).build() - 私有建構子，確保只能透過 Builder 建立 - 完整的參數驗證（非負數、合理範圍檢查） - 不可變（immutable），線程安全  用途： - 取代 DynamicMaterial.ofCustom() 的更優雅方式 - CLI 指令 /br_material create 使用 - 動態材料融合計算結果的包裝

> [!tip] 資訊
> 🔌 Implements: [[RMaterial]]

## 🔗 Related
- [[Builder]]
- [[CustomMaterial]]
- [[DynamicMaterial]]
- [[RMaterial]]
- [[build]]
- [[builder]]
- [[create]]
- [[density]]
- [[material]]
- [[ofCustom]]
- [[rcomp]]
- [[rshear]]
- [[rtens]]
