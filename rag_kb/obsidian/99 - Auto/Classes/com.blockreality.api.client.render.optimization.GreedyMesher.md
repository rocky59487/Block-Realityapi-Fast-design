---
id: "java_api:com.blockreality.api.client.render.optimization.GreedyMesher"
type: class
tags: ["java", "api", "optimization", "client-only"]
---

# 🧩 com.blockreality.api.client.render.optimization.GreedyMesher

> [!info] 摘要
> Greedy Meshing 引擎 — Sodium/Embeddium 核心優化技術。  演算法： 對每個軸向面（6 方向），掃描 2D 切片，將相鄰同材質面合併為最大矩形。 參考 Mikola Lysenko (0fps) 的經典 Greedy Meshing 論文。  Sodium 啟發的額外優化： - 每個 16³ section 獨立 mesh（平行化友好） - 面材質 ID 打包（同材質才合併） - 法線壓縮（6 方向 → 3-bit 編碼） - 結果直接寫入 VBO 格式的頂點陣列  效果： 一面 16×16 的同材質牆 → 256 face → 1 face（256× 減少）

## 🔗 Related
- [[GreedyMesher]]
- [[mesh]]
- [[render]]
