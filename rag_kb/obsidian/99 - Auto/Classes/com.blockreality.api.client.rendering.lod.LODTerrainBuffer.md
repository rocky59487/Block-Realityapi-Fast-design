---
id: "java_api:com.blockreality.api.client.rendering.lod.LODTerrainBuffer"
type: class
tags: ["java", "api", "lod", "client-only"]
---

# 🧩 com.blockreality.api.client.rendering.lod.LODTerrainBuffer

> [!info] 摘要
> LOD Terrain Buffer — 管理 LOD section 的 GPU VAO/VBO/IBO 資源。  <p>所有 OpenGL 呼叫必須在主執行緒（GL context 執行緒）執行。 Worker 執行緒透過 {@link #queueUpload} 將網格資料放入佇列， 主執行緒在每幀開始時呼叫 {@link #processUploadQueue} 完成 GPU 上傳。  <p>頂點格式（interleaved）： <pre> [ x, y, z,  nx, ny, nz,  u, v,  matId ] × vertexCount stride = 9 floats = 36 bytes </pre>  @author Block Reality Team

## 🔗 Related
- [[LODTerrainBuffer]]
- [[author]]
- [[load]]
- [[processUploadQueue]]
- [[queue]]
- [[queueUpload]]
- [[render]]
- [[ring]]
- [[vertex]]
