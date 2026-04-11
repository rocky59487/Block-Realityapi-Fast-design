---
id: "java_test:com.blockreality.api.client.render.rt.BRClusterBVHTest"
type: test
tags: ["java", "test", "junit"]
---

# 🧩 com.blockreality.api.client.render.rt.BRClusterBVHTest

> [!info] 摘要
> BRClusterBVHTest — 驗證 Cluster BVH 管理器的核心邏輯。  <p>所有測試均在 JUnit 5 環境中執行，不依賴 Vulkan / Forge runtime。 測試對象為 BRClusterBVH 的純 Java 邏輯： <ul> <li>Cluster key 計算正確性</li> <li>Section → Cluster 座標映射</li> <li>4×4 section 打包邏輯</li> <li>AABB 合併展開</li> <li>邊界/負座標處理（地下 section）</li> </ul>  <p>注意：{@link BRClusterBVH#onSectionUpdated} 等方法需要 {@link com.blockreality.api.client.rendering.vulkan.BRAdaRTConfig#isBlackwel

## 🔗 Related
- [[AABB]]
- [[BRAdaRTConfig]]
- [[BRClusterBVH]]
- [[BRClusterBVHTest]]
- [[Config]]
- [[com.blockreality.api.client.render.rt.BRClusterBVH]]
- [[com.blockreality.api.client.rendering.vulkan.BRAdaRTConfig]]
- [[onSectionUpdated]]
- [[render]]
- [[ring]]
