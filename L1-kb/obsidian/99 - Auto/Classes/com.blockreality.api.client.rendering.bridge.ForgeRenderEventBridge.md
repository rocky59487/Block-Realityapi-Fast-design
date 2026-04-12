---
id: "java_api:com.blockreality.api.client.rendering.bridge.ForgeRenderEventBridge"
type: class
tags: ["java", "api", "bridge", "client-only"]
---

# 🧩 com.blockreality.api.client.rendering.bridge.ForgeRenderEventBridge

> [!info] 摘要
> Forge Render Event Bridge — 將 Forge 渲染事件橋接至 LOD 系統。  <p>訂閱： <ul> <li>{@link RenderLevelStageEvent} — 每幀渲染掛點（AFTER_SOLID_BLOCKS 等）</li> <li>{@link ChunkEvent.Load} / {@link ChunkEvent.Unload} — chunk 生命週期</li> <li>{@link BlockEvent.NeighborNotifyEvent} — 方塊更新通知</li> </ul>  <p>Vulkan RT 管線已移除（Phase 4-F），渲染改為單一開/關切換。  @author Block Reality Team

## 🔗 Related
- [[ForgeRenderEventBridge]]
- [[author]]
- [[load]]
- [[render]]
- [[ring]]
