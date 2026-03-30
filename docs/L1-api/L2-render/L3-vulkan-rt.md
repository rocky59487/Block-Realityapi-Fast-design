# Vulkan 光線追蹤

> 所屬：L1-api > L2-render

## 概述

Block Reality 的混合 GL+Vulkan 光線追蹤管線，使用 VK_KHR_ray_tracing_pipeline 進行陰影、反射、環境光遮蔽與全域光照的光線追蹤，結合 SVGF 去噪器處理 1-spp 雜訊。僅在 Tier 3（RTX 3060+）啟用。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `BRVulkanRT` | `client.render.rt` | 光線追蹤管線主類別 |
| `BRVulkanBVH` | `client.render.rt` | BVH 加速結構管理器（BLAS/TLAS） |
| `BRVulkanDevice` | `client.render.rt` | Vulkan 裝置初始化與管理 |
| `BRVulkanInterop` | `client.render.rt` | GL/VK 紋理共享互操作 |
| `BRSVGFDenoiser` | `client.render.rt` | SVGF 時空方差引導去噪器 |

## RT 效果

| 效果 | 預設啟用 | 估計成本 (1080p) |
|------|---------|-----------------|
| `SHADOWS` | 是 | ~0.5ms |
| `REFLECTIONS` | 否 | ~1.0ms |
| `AMBIENT_OCCLUSION` | 否 | ~0.3ms |
| `GLOBAL_ILLUMINATION` | 否 | ~3.0ms |

## BRVulkanBVH

### 場景階層

```
Scene TLAS (Top-Level)
+-- Chunk Section BLAS (16x16x16) x N
|   +-- AABBs from GreedyMesher
+-- Updated incrementally per-frame
```

- 每幀最多重建有限數量的 dirty BLAS，避免 GPU stall
- TLAS 在有 dirty section 時完整重建

## BRVulkanDevice

- 自動偵測 Vulkan 與 RT 擴展可用性
- 啟用的擴展：`VK_KHR_ray_tracing_pipeline`、`VK_KHR_acceleration_structure`、`VK_KHR_buffer_device_address`
- 優雅降級：Vulkan/RT 不可用時靜默停用，不影響遊戲

## BRVulkanInterop (GL/VK 共享)

```
Vulkan RT Pipeline → VkImage (RGBA16F)
    ↓ VK_KHR_external_memory (export fd)
OpenGL Composite ← GL texture (import fd)
```

- 使用 `VK_KHR_external_memory` + `GL_EXT_memory_object` 實現零拷貝
- Fallback：若 interop 不可用，使用 CPU readback 路徑

## BRSVGFDenoiser

三階段 GL Compute Shader 去噪：

1. **Temporal Accumulation** — 運動向量 reprojection，混合當前幀 (10%) + 歷史 (90%)
2. **Variance Estimation** — 計算鄰域亮度方差
3. **A-trous Wavelet Filter** — 5 次迭代邊緣保持空間濾波（深度/法線/亮度 edge-stopping）

## 關聯接口

- 被依賴 ← [RenderPipeline](L3-pipeline.md) — Tier 3 光線追蹤 Pass
- 依賴 → [GreedyMesher](L3-mesher.md) — BLAS 幾何來源
