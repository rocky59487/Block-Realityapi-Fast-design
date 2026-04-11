---
id: "dataflow:render-pipeline"
type: dataflow
tags: ["dataflow", "render", "vulkan", "rt", "client-only"]
---

# 🌊 Vulkan RT 渲染管線資料流

## 📝 概述
Minecraft Chunk Section → SectionDataSource / SectionMeshCompiler 提取幾何 → ChiselMeshBuilder / GreedyMesher 產生 mesh → BRVulkanRT / VkAccelStructBuilder 建立 BLAS/TLAS → raygen.rgen.glsl 發射 primary rays → material.rchit.glsl / shadow.rmiss.glsl 計算著色 → BRReSTIRDI / BRReSTIRGI compute shader 重投影採樣 → BRDDGIProbeSystem 更新漫反射探針 → BRReLAXDenoiser / BRSVGFDenoiser 降噪 → BRRenderPipeline 組合後處理 pass（ColorGrading、MotionBlur、Bloom 等）→ 輸出到 swapchain。

## 🔄 資料流階段
1. [[SectionMeshCompiler]]
2. [[BRVulkanRT]]
3. [[raygen.rgen.glsl]]
4. [[material.rchit.glsl]]
5. [[BRReSTIRDI]]
6. [[BRDDGIProbeSystem]]
7. [[BRReLAXDenoiser]]
8. [[BRRenderPipeline]]

> [!tip] 相關資訊
> 🔄 Pipeline Stages:
>   - [[SectionMeshCompiler]]
>   - [[BRVulkanRT]]
>   - [[raygen.rgen.glsl]]
>   - [[material.rchit.glsl]]
>   - [[BRReSTIRDI]]
>   - [[BRDDGIProbeSystem]]
>   - [[BRReLAXDenoiser]]
>   - [[BRRenderPipeline]]
> 📎 Related Files:
>   - `Block Reality/api/src/main/java/com/blockreality/api/client/render/SectionMeshCompiler.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/client/render/rt/BRVulkanRT.java`
>   - `Block Reality/api/src/main/resources/assets/blockreality/shaders/rt/`
>   - `Block Reality/api/src/main/java/com/blockreality/api/client/render/rt/BRReSTIRDI.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/client/render/rt/BRDDGIProbeSystem.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/client/render/rt/BRReLAXDenoiser.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/client/render/pipeline/BRRenderPipeline.java`

## 🔗 Related Notes
- [[BRDDGIProbeSystem]]
- [[BRReLAXDenoiser]]
- [[BRReSTIRDI]]
- [[BRReSTIRGI]]
- [[BRRenderPipeline]]
- [[BRSVGFDenoiser]]
- [[BRVulkanRT]]
- [[Builder]]
- [[ChiselMeshBuilder]]
- [[GreedyMesher]]
- [[SectionDataSource]]
- [[SectionMesh]]
- [[SectionMeshCompiler]]
- [[VkAccelStructBuilder]]
- [[compute]]
- [[glsl]]
- [[line]]
- [[material]]
- [[material.rchit.glsl]]
- [[mesh]]
- [[raygen.rgen.glsl]]
- [[shadow]]
- [[shadow.rmiss.glsl]]
- [[swap]]
