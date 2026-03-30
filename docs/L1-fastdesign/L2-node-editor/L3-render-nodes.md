# 渲染節點

> 所屬：L1-fastdesign > L2-node-editor

## 概述

渲染節點（Category A）是節點編輯器中數量最多的分類（57+ 個），涵蓋光照、LOD/剔除、渲染管線、後處理特效、水體、天氣及預設六大子分類。

## 光照 (lighting/)

| 類別 | typeId | 說明 |
|------|--------|------|
| `SunLightNode` | render.lighting.SunLight | 太陽光方向與強度 |
| `PointLightNode` | render.lighting.PointLight | 點光源 |
| `AreaLightNode` | render.lighting.AreaLight | 面積光源 |
| `AmbientLightNode` | render.lighting.AmbientLight | 環境光 |
| `EmissiveBlockNode` | render.lighting.EmissiveBlock | 自發光方塊 |
| `LightProbeNode` | render.lighting.LightProbe | 光探針 |
| `CSM_CascadeNode` | render.lighting.CSM_Cascade | 級聯陰影貼圖 |

## LOD 與剔除 (lod/)

| 類別 | typeId | 說明 |
|------|--------|------|
| `LODConfigNode` | render.lod.LODConfig | LOD 全域配置 |
| `LODLevelNode` | render.lod.LODLevel | LOD 層級定義 |
| `GreedyMeshNode` | render.lod.GreedyMesh | 貪婪網格合併 |
| `MeshCacheNode` | render.lod.MeshCache | 網格快取 |
| `BatchRenderNode` | render.lod.BatchRender | 批次渲染 |
| `FrustumCullerNode` | render.lod.FrustumCuller | 視錐剔除 |
| `OcclusionCullerNode` | render.lod.OcclusionCuller | 遮擋剔除 |
| `HiZConfigNode` | render.lod.HiZConfig | Hi-Z 遮擋配置 |
| `IndirectDrawNode` | render.lod.IndirectDraw | 間接繪製 |

## 渲染管線 (pipeline/)

| 類別 | typeId | 說明 |
|------|--------|------|
| `GBufferConfigNode` | render.pipeline.GBufferConfig | G-Buffer 配置 |
| `ShadowConfigNode` | render.pipeline.ShadowConfig | 陰影配置 |
| `FramebufferChainNode` | render.pipeline.FramebufferChain | Framebuffer 鏈 |
| `PipelineOrderNode` | render.pipeline.PipelineOrder | 管線執行順序 |
| `RenderScaleNode` | render.pipeline.RenderScale | 渲染縮放 |
| `VRAMBudgetNode` | render.pipeline.VRAMBudget | VRAM 預算 |
| `VertexFormatNode` | render.pipeline.VertexFormat | 頂點格式 |
| `ViewportLayoutNode` | render.pipeline.ViewportLayout | 視口佈局 |

## 後處理特效 (postfx/)

| 類別 | typeId | 說明 |
|------|--------|------|
| `SSAO_GTAONode` | render.postfx.SSAO_GTAO | 環境遮蔽（SSAO/GTAO） |
| `SSRNode` | render.postfx.SSR | 螢幕空間反射 |
| `SSGINode` | render.postfx.SSGI | 螢幕空間全域照明 |
| `SSSNode` | render.postfx.SSS | 次表面散射 |
| `VCT_GINode` | render.postfx.VCT_GI | 體素錐追蹤 GI |
| `BloomNode` | render.postfx.Bloom | 泛光 |
| `DOFNode` | render.postfx.DOF | 景深 |
| `MotionBlurNode` | render.postfx.MotionBlur | 動態模糊 |
| `TAANode` | render.postfx.TAA | 時間性反鋸齒 |
| `TonemapNode` | render.postfx.Tonemap | 色調映射 |
| `ColorGradingNode` | render.postfx.ColorGrading | 色彩分級 |
| `LensFlareNode` | render.postfx.LensFlare | 鏡頭光暈 |
| `POMNode` | render.postfx.POM | 視差遮擋映射 |
| `ContactShadowNode` | render.postfx.ContactShadow | 接觸陰影 |
| `VolumetricLightNode` | render.postfx.VolumetricLight | 體積光 |
| `CinematicNode` | render.postfx.Cinematic | 電影效果 |
| `WetPBRNode` | render.postfx.WetPBR | 濕潤 PBR |
| `AnisotropicNode` | render.postfx.Anisotropic | 各向異性過濾 |

### SSAO_GTAONode 範例
- **輸入**: enabled, mode(ENUM), kernelSize(4~64), radius(0.1~5), gtaoSlices(1~8), intensity(0~2)...
- **輸出**: aoTexture(TEXTURE), gpuTimeMs(FLOAT)

## 水體 (water/)

| 類別 | typeId | 說明 |
|------|--------|------|
| `WaterSurfaceNode` | render.water.WaterSurface | 水面渲染 |
| `WaterCausticsNode` | render.water.WaterCaustics | 水焦散 |
| `WaterFoamNode` | render.water.WaterFoam | 水泡沫 |
| `UnderwaterNode` | render.water.Underwater | 水下效果 |

## 天氣 (weather/)

| 類別 | typeId | 說明 |
|------|--------|------|
| `AtmosphereNode` | render.weather.Atmosphere | 大氣散射 |
| `CloudNode` | render.weather.Cloud | 雲層 |
| `FogNode` | render.weather.Fog | 霧 |
| `RainNode` | render.weather.Rain | 雨 |
| `SnowNode` | render.weather.Snow | 雪 |
| `AuroraNode` | render.weather.Aurora | 極光 |

## 預設 (preset/)

| 類別 | typeId | 說明 |
|------|--------|------|
| `QualityPresetNode` | render.preset.QualityPreset | 品質預設 |
| `GPUDetectNode` | render.preset.GPUDetect | GPU 偵測 |
| `PerformanceTargetNode` | render.preset.PerformanceTarget | 效能目標 |
| `TierSelectorNode` | render.preset.TierSelector | 階層選擇 |
| `ABCompareNode` | render.preset.ABCompare | A/B 比較 |

## 關聯接口
- 被依賴 ← [RenderConfigBinder / ShaderBinder](L3-binding.md)（推送渲染配置與 shader uniform）
