---
id: "java_api:com.blockreality.api.physics.sparse.RegionConnectivityEngine"
type: class
tags: ["java", "api", "sparse"]
---

# 🧩 com.blockreality.api.physics.sparse.RegionConnectivityEngine

> [!info] 摘要
> Region 級連通性引擎 — 階層式物理 Layer 3。  參考來源：Valkyrien Skies 2 的 Physics World 分離架構。  核心思想： 每個 VoxelSection (16³) 視為一個「超級節點」， Section 之間的邊由邊界面的連續方塊決定。 在 Section 級做 Union-Find，複雜度 O(Section 數量) ≈ O(107K)。  用途： 1. 快速判斷大型結構是否仍連接地面（< 10ms） 2. 識別「結構島」(structural islands) 做並行分割 3. 倒塌事件：整個 island 脫離 → 觸發 Layer 2 (CoarseFEM) 分析  執行頻率：每 5 秒（100 ticks）執行一次全域掃描。  @since v3.0 Phase 2

## 🔗 Related
- [[RegionConnectivityEngine]]
- [[VoxelSection]]
- [[tick]]
