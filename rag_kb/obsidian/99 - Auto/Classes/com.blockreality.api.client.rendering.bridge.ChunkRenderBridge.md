---
id: "java_api:com.blockreality.api.client.rendering.bridge.ChunkRenderBridge"
type: class
tags: ["java", "api", "bridge", "client-only"]
---

# 🧩 com.blockreality.api.client.rendering.bridge.ChunkRenderBridge

> [!info] 摘要
> Chunk Render Bridge — 從 Minecraft 世界讀取方塊資料，供 LOD 系統使用。  <p>實作 {@link LODChunkManager.BlockDataProvider}， 透過 {@code ClientLevel} 讀取 16×16×16 section 的方塊 ID。  <p>同時提供靜態方法供 {@link ForgeRenderEventBridge} 呼叫， 將 chunk/section 事件轉發至 {@link BRVoxelLODManager}。  @author Block Reality Team

> [!tip] 資訊
> 🔌 Implements: [[BlockDataProvider]]

## 🔗 Related
- [[BRVoxelLODManager]]
- [[BlockDataProvider]]
- [[ChunkRenderBridge]]
- [[ForgeRenderEventBridge]]
- [[LODChunkManager]]
- [[author]]
- [[render]]
- [[ring]]
