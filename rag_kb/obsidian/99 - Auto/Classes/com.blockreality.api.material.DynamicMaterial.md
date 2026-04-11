---
id: "java_api:com.blockreality.api.material.DynamicMaterial"
type: class
tags: ["java", "api", "material"]
---

# 🧩 com.blockreality.api.material.DynamicMaterial

> [!info] 摘要
> 動態材料 — 在 runtime 以公式計算出的材料參數。  主要用途：RC 融合節點（RCFusionDetector） 每個 RC 節點的強度由相鄰的鋼筋+混凝土規格決定， 無法預先寫死在 enum 裡，必須動態建立。  不可變 record — 線程安全，可跨線程傳遞。  ★ review-fix #13: 存取欄位有兩種方式（結果相同）： - record canonical accessor: rcomp(), rtens(), rshear(), density(), materialId() - RMaterial interface: getRcomp(), getRtens(), getRshear(), getDensity(), getMaterialId() 兩者回傳相同值。建議透過 RMaterial 介面存取以保持一致性。  工廠方法： DynamicMater

## 🔗 Related
- [[DynamicMaterial]]
- [[RCFusionDetector]]
- [[RMaterial]]
- [[accessor]]
- [[canonical]]
- [[density]]
- [[getDensity]]
- [[getMaterial]]
- [[getMaterialId]]
- [[getRcomp]]
- [[getRshear]]
- [[getRtens]]
- [[material]]
- [[materialId]]
- [[rcomp]]
- [[record]]
- [[rshear]]
- [[rtens]]
