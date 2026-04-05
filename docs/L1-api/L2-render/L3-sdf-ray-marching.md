# SDF Ray Marching 系統

> 所屬：[L2-render](index.md) > [L1-api](../index.md)

## 概述

基於 Signed Distance Field (SDF) 的 Ray Marching 系統，使用 Vulkan Compute Shader
在 3D SDF Volume 中執行 Sphere Tracing，計算遠距全域照明 (GI)、環境遮蔽 (AO) 與柔和陰影。
作為硬體 RT 的輔助，實現混合渲染策略（近處 HW RT，遠處 SDF）。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|----------|------|
| `BRSDFVolumeManager` | `com.blockreality.api.client.render.rt` | SDF 3D Texture 管理、JFA 更新、dirty section 追蹤 |
| `BRSDFRayMarcher` | `com.blockreality.api.client.render.rt` | Sphere Tracing compute shader 調度、GI+AO 輸出 |
| `RTEffect.SDF_GI` | `com.blockreality.api.client.render.rt` | SDF Ray Marching 效果開關（可動態啟停） |
| `RTRenderPass.SDF_UPDATE` | `com.blockreality.api.client.render.pipeline` | SDF Volume 增量更新 Pass |
| `RTRenderPass.SDF_GI_AO` | `com.blockreality.api.client.render.pipeline` | SDF Ray Marching GI+AO Pass |

## Compute Shaders

| Shader | 路徑 | 說明 |
|--------|------|------|
| `sdf_update.comp.glsl` | `assets/blockreality/shaders/compute/` | JFA 體素→SDF 重建 |
| `sdf_gi_ao.comp.glsl` | `assets/blockreality/shaders/compute/` | Sphere Tracing GI+AO+Soft Shadow |

## 架構

```
VoxelSection (16^3 blocks)
    ↓ markDirty()
BRSDFVolumeManager
    ↓ uploadSectionOccupancy() + dispatchJFA()
SDF 3D Texture (R16F, 256^3)
    ↓ sampler3D binding
BRSDFRayMarcher
    ↓ sdf_gi_ao.comp.glsl dispatch
GI Output (rgba16f) + AO Output (r8)
    ↓ 與 HW RT 結果混合
NRD 降噪
```

## 混合渲染策略

| 距離範圍 | 渲染方式 | 混合權重 |
|---------|---------|---------|
| 0-48 blocks | 硬體 RT 全強度 | SDF = 0% |
| 48-80 blocks | HW RT + SDF 線性混合 | smoothstep |
| 80+ blocks | SDF 全強度 | SDF = 100% |

## 管線位置

### Blackwell 路徑
```
GBUFFER → CLUSTER_BVH_UPDATE → SDF_UPDATE → RESTIR_DI → RESTIR_GI
        → SDF_GI_AO → NRD → DLSS_SR → DLSS_MFG → TONEMAP → UI
```

### Ada 路徑
```
GBUFFER → BLAS_TLAS_UPDATE → SDF_UPDATE → RT_SHADOW_AO
        → DDGI_UPDATE → DDGI_SAMPLE → SDF_GI_AO
        → NRD → DLSS_SR → DLSS_FG → TONEMAP → UI
```

## Specialization Constants

| ID | 名稱 | Ada | Blackwell | 說明 |
|----|------|-----|-----------|------|
| SC_0 | GPU_TIER | 1 | 2 | GPU 世代 |
| SC_2 | GI_CONE_COUNT | 4 | 8 | GI cone tracing 數量 |
| SC_3 | AO_RAY_COUNT | 2 | 4 | AO sphere trace 數量 |

## 關聯接口

- [BRAdaRTConfig](L3-vulkan-rt.md) — GPU 世代偵測
- [BRRTPipelineOrdering](L3-pipeline.md) — Pass 排序整合
- [BRSparseVoxelDAG](../L2-render/L3-mesher.md) — 遠距 LOD 資料（DAG SSBO）
