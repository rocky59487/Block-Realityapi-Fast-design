---
id: "java_api:com.blockreality.api.client.render.pipeline.RTRenderPass"
type: class
tags: ["java", "api", "pipeline", "client-only"]
---

# 🧩 com.blockreality.api.client.render.pipeline.RTRenderPass

> [!info] 摘要
> Phase 8 渲染 Pass 枚舉 — RTX 光追路徑專屬 Pass 識別符。  <h3>三條 Pass 路徑（對應 TASK_MANUAL § Phase 8）</h3>  <b>Blackwell（RTX 50xx，SM 10.x）：</b> <pre> GBUFFER → CLUSTER_BVH_UPDATE → RESTIR_DI → RESTIR_GI → NRD → DLSS_SR → DLSS_MFG → TONEMAP → UI </pre>  <b>Ada（RTX 40xx，SM 8.9）：</b> <pre> GBUFFER → BLAS_TLAS_UPDATE → RT_SHADOW_AO → DDGI_UPDATE → DDGI_SAMPLE → NRD → DLSS_SR → DLSS_FG → TONEMAP → UI </pre>  <b>Legacy（R

## 🔗 Related
- [[RTRenderPass]]
- [[RenderPass]]
- [[line]]
- [[render]]
