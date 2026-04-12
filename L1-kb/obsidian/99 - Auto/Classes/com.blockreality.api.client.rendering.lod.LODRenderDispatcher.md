---
id: "java_api:com.blockreality.api.client.rendering.lod.LODRenderDispatcher"
type: class
tags: ["java", "api", "lod", "client-only"]
---

# 🧩 com.blockreality.api.client.rendering.lod.LODRenderDispatcher

> [!info] 摘要
> LOD Render Dispatcher — 每幀調度 LOD 渲染，進行視錐剔除與距離排序。  <p>職責： <ul> <li>從 {@link LODChunkManager} 收集所有 section</li> <li>使用 {@link FrustumCuller} 剔除視錐外的 section</li> <li>依相機距離決定每個 section 的目標 LOD</li> <li>呼叫 OpenGL draw call 渲染可見 section</li> <li>限制每幀 dirty LOD 重建數量，避免卡頓</li> </ul>  @author Block Reality Team

## 🔗 Related
- [[FrustumCuller]]
- [[LODChunkManager]]
- [[LODRenderDispatcher]]
- [[author]]
- [[render]]
- [[ring]]
