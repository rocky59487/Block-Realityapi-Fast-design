---
id: "java_api:com.blockreality.api.sph.SpatialHashGrid"
type: class
tags: ["java", "api", "sph"]
---

# 🧩 com.blockreality.api.sph.SpatialHashGrid

> [!info] 摘要
> 空間雜湊格子 — SPH 鄰域搜索的 O(1) 查詢結構。  <p>SPH 核心函數具有緊支撐性（compact support），即距離超過 2h 的粒子 不會互相影響。利用這一性質，將空間劃分為邊長 = 2h 的格子， 查詢鄰居時只需搜索周圍 3³ = 27 個格子。  <p>使用 Teschner et al. (2003) 的空間雜湊方法，以三個大質數 將格子座標映射到一維雜湊值，避免顯式儲存 3D 陣列。  <h3>時間複雜度</h3> <ul> <li>插入：O(1) amortized</li> <li>鄰域查詢：O(k)，k 為返回的鄰居數量</li> <li>建構：O(N)，N 為粒子總數</li> </ul>  <h3>參考文獻</h3> <p>Teschner, M. et al. (2003). "Optimized Spatial Hashing for Coll

## 🔗 Related
- [[SpatialHashGrid]]
- [[compact]]
