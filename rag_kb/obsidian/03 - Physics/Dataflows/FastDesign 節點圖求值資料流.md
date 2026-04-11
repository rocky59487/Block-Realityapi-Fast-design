---
id: "dataflow:node-evaluation"
type: dataflow
tags: ["dataflow", "node", "fastdesign", "client-only"]
---

# 🌊 FastDesign 節點圖求值資料流

## 📝 概述
玩家編輯節點圖（NodeCanvasScreen）→ Wire 連線變更觸發 NodeGraph 拓撲排序（Kahn 算法）→ EvaluateScheduler 依拓撲序呼叫每個 BRNode.evaluate() → InputPort 從上游 Wire 讀取值 → 節點計算後寫入 OutputPort → 終端節點（如 ConfigExportNode）將結果綁定到實際遊戲對象（透過 MaterialBinder、RenderConfigBinder 等）→ NodeGraphIO 自動序列化儲存。

## 🔄 資料流階段
1. [[NodeCanvasScreen]]
2. [[NodeGraph]]
3. [[EvaluateScheduler]]
4. BRNode.evaluate
5. [[InputPort]]
6. [[OutputPort]]
7. [[MaterialBinder]]
8. [[NodeGraphIO]]

> [!tip] 相關資訊
> 🔄 Pipeline Stages:
>   - [[NodeCanvasScreen]]
>   - [[NodeGraph]]
>   - [[EvaluateScheduler]]
>   - BRNode.evaluate
>   - [[InputPort]]
>   - [[OutputPort]]
>   - [[MaterialBinder]]
>   - [[NodeGraphIO]]
> 📎 Related Files:
>   - `Block Reality/fastdesign/src/main/java/com/blockreality/fastdesign/client/node/canvas/NodeCanvasScreen.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/node/NodeGraph.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/node/EvaluateScheduler.java`
>   - `Block Reality/fastdesign/src/main/java/com/blockreality/fastdesign/client/node/BRNode.java`
>   - `Block Reality/fastdesign/src/main/java/com/blockreality/fastdesign/client/node/binding/MaterialBinder.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/node/NodeGraphIO.java`

## 🔗 Related Notes
- [[BRNode]]
- [[Config]]
- [[ConfigExportNode]]
- [[EvaluateScheduler]]
- [[InputPort]]
- [[MaterialBinder]]
- [[NodeCanvasScreen]]
- [[NodeGraph]]
- [[NodeGraphIO]]
- [[OutputPort]]
- [[RenderConfigBinder]]
- [[Wire]]
- [[evaluate]]
