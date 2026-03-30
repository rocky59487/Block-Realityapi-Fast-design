# 渲染管線系統

> 所屬：[L1-api](../index.md)

## 概述

Block Reality 渲染管線融合 Iris/Radiance 的多 Pass 延遲渲染架構、Sodium/Embeddium 的
Greedy Meshing 優化以及 GeckoLib 風格的骨骼動畫系統，提供從 Shadow Map 到 Final Composite
的完整固化渲染管線。支援四級 GPU 硬體自適應（Tier 0~3），最高可啟用 Vulkan 光線追蹤。

## 子文檔

| 文檔 | 說明 |
|------|------|
| [L3-pipeline](L3-pipeline.md) | RenderPipeline、FramebufferManager、ViewportManager |
| [L3-mesher](L3-mesher.md) | GreedyMesher、LOD 引擎 |
| [L3-animation](L3-animation.md) | AnimationEngine、BoneHierarchy、AnimationClip |
| [L3-postfx](L3-postfx.md) | 後製特效（GI、動態模糊、SSS、色彩分級等） |
| [L3-vulkan-rt](L3-vulkan-rt.md) | Vulkan 光線追蹤管線 |
