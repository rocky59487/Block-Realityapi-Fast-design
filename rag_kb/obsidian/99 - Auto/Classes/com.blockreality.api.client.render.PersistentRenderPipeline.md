---
id: "java_api:com.blockreality.api.client.render.PersistentRenderPipeline"
type: class
tags: ["java", "api", "render", "client-only"]
---

# 🧩 com.blockreality.api.client.render.PersistentRenderPipeline

> [!info] 摘要
> 持久化渲染管線 — 取代即時模式 BufferBuilder。  參考來源：Embeddium/Sodium 的 Section-based VBO 架構。  核心設計： - 每個 VoxelSection 編譯為一個持久化 VAO/VBO - 只在 Section 內容變更時重建該 VBO - 每幀只呼叫 glDrawArrays() 繪製可見 Section（不重建 buffer） - 視錐體剔除 + 距離剔除排除不可見 Section  頂點格式：POSITION(3f) + COLOR(4B) = 16 bytes/vertex 每方塊 6 面 × 4 頂點 = 24 頂點（Greedy Meshing 後可減少 60-95%）  記憶體管理： - GPU buffer pool 上限可配置 - 超出視距的 Section VBO 被回收 - 使用 glBufferSubData 

## 🔗 Related
- [[Builder]]
- [[PersistentRenderPipeline]]
- [[VoxelSection]]
- [[line]]
- [[render]]
- [[vertex]]
