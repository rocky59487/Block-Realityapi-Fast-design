---
id: "java_api:com.blockreality.api.client.render.SectionMeshCompiler"
type: class
tags: ["java", "api", "render", "client-only"]
---

# 🧩 com.blockreality.api.client.render.SectionMeshCompiler

> [!info] 摘要
> Section 網格編譯器 — 將 VoxelSection 編譯為 GPU 頂點資料。  包含 Greedy Meshing 演算法，合併相鄰同材質面為大矩形。  Greedy Meshing 效果： 原始：每方塊 6 面 × 4 頂點 = 24 頂點/block 4096 blocks → 98,304 頂點（最差） 優化：典型建築牆面 100 blocks → 1 矩形 4 頂點 頂點減少 60-95%（取決於幾何複雜度）  頂點格式： Position: 3 × float (12 bytes) Color:    4 × ubyte (4 bytes) Total:    16 bytes/vertex  參考： - Mikola Lysenko "Meshing in a Minecraft Game" (0fps.net) - Sodium SectionMeshBuilde

## 🔗 Related
- [[SectionMesh]]
- [[SectionMeshCompiler]]
- [[VoxelSection]]
- [[render]]
- [[vertex]]
