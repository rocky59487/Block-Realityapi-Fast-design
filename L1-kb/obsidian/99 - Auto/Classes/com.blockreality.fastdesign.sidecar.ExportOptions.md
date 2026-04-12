---
id: "java_fd:com.blockreality.fastdesign.sidecar.ExportOptions"
type: class
tags: ["java", "fastdesign", "sidecar"]
---

# 🧩 com.blockreality.fastdesign.sidecar.ExportOptions

> [!info] 摘要
> 控制匯出幾何的品質與風格。  @param smoothing  0.0 = 完全體素（Greedy Mesh，最快，無平滑） 0.01~1.0 = SDF + Dual Contouring，數值越大曲面越平滑 @param resolution SDF 子體素解析度倍率（1~4）。越高越細緻，指數級增加記憶體與時間。 @param outputPath STEP 輸出路徑，null 代表自動生成時間戳路徑

## 🔗 Related
- [[ExportOptions]]
- [[ring]]
- [[smooth]]
