# Vulkan 光線追蹤

> 所屬：L1-api > L2-render

## 概述

Block Reality 的混合 GL+Vulkan 光線追蹤管線，使用 `VK_KHR_ray_tracing_pipeline` 進行即時陰影、反射、環境光遮蔽與 DAG 全域光照，結合 SVGF/NRD 去噪器處理 1-spp 雜訊。僅在 Tier 3（RTX 3060+）啟用。Ada SM 8.9（RTX 40xx）與 Blackwell SM 10.x（RTX 50xx）各有專屬著色器路徑。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `BRRTCompositor` | `client.rendering` | RT 管線主協調器；單例；管理生命週期與每幀 dispatch |
| `VkRTPipeline` | `client.rendering.vulkan` | Vulkan RT pipeline 封裝；持有 `EnumSet<RTEffect>` 預算控制 |
| `VkAccelStructBuilder` | `client.rendering.vulkan` | BLAS/TLAS 增量重建；OMM 透明快取路由 |
| `BRVulkanBVH` | `client.render.rt` | 低階 BLAS/TLAS Vulkan 呼叫；`buildBLASOpaque()` OMM 路徑 |
| `BRVulkanDevice` | `client.render.rt` | Vulkan 裝置 stub；`buildBLASWithOMM()` Phase 3 預留介面 |
| `BRVulkanInterop` | `client.render.rt` | GL/VK 紋理零拷貝互操作（`VK_KHR_external_memory`） |
| `BRNRDDenoiser` | `client.rendering.vulkan` | SVGF/NRD 時序降噪器 |
| `VkRTAO` | `client.rendering.vulkan` | Ray Query RTAO pass（Ada SM 8.9+） |
| `RTEffect` | `client.render.rt` | 效果預算枚舉（見下表） |

## RTEffect 預算控制

`VkRTPipeline` 持有 `EnumSet<RTEffect> activeEffects`，預設全部啟用。所有效果均可在執行中動態切換，無需重建 pipeline。

| RTEffect 值 | 說明 | 關閉優先級 | 估計成本 (1080p) |
|------------|------|-----------|-----------------|
| `RTAO` | Ray Query 環境光遮蔽（最耗時） | 最先關閉 | ~0.8ms |
| `SHADOWS` | RT 硬陰影（primary.rgen R 通道） | 第二 | ~0.5ms |
| `REFLECTIONS` | RT 反射（primary.rgen GBA 通道） | 第三 | ~1.0ms |
| `SVGF_DENOISE` | SVGF/NRD 時序降噪器 | 第四 | ~0.4ms |
| `DAG_GI` | DAG SSBO PCIe 上傳（遠景 GI） | 最後 | ~0.2ms (CPU) |

### 控制 API

```java
// 透過 BRRTCompositor facade（節點系統 / 外部模組使用）
BRRTCompositor compositor = BRRTCompositor.getInstance();
compositor.enableRTEffect(RTEffect.RTAO);
compositor.disableRTEffect(RTEffect.SVGF_DENOISE);
compositor.isRTEffectEnabled(RTEffect.SHADOWS); // → boolean

// 直接存取 pipeline（內部使用）
VkRTPipeline pipeline = compositor.getRTPipeline();
pipeline.enableEffect(RTEffect.DAG_GI);
pipeline.getActiveEffects(); // → Set<RTEffect>（不可修改視圖）
```

> RTAO 開關同步寫入 `BRRTSettings.setEnableRTAO()`，由 `VkRTPipeline.enableEffect()` 內部完成，呼叫端無需重複設定。

## VkAccelStructBuilder — BLAS 建構與 OMM 路由

### 場景階層

```
Scene TLAS (Top-Level)
├── Section BLAS (16×16×16) × N
│   └── AABBs from GreedyMesher
│       ├── 不透明區段  → buildBLASOpaque() [VK_GEOMETRY_OPAQUE_BIT_KHR]
│       └── 透明區段   → buildBLAS()       [含玻璃/水/樹葉]
└── Cluster BLAS (4×4 section 群組, Blackwell SM 10.x)
```

- 每幀最多重建有限數量的 dirty BLAS，避免 GPU stall
- TLAS 在有 dirty section 時完整重建

### 透明快取 (OMM 路由)

`VkAccelStructBuilder` 維護 `ConcurrentHashMap<Long, Boolean> transparentSectionCache`，追蹤哪些 section 含玻璃/水/樹葉等透明幾何。

```java
// 由 ForgeRenderEventBridge.onBlockChange() 在客戶端方塊事件時呼叫
accelBuilder.markSectionTransparent(sectionX, sectionZ, true);

// 或透過 BRRTCompositor facade
BRRTCompositor.getInstance().markSectionTransparent(sectionX, sectionZ, true);

// 統計
int transparentCount = accelBuilder.getTransparentSectionCount();
```

> **保守策略**：透明方塊被移除時不立即清除標記，等待下次 BLAS rebuild 確認。OMM opaque 路由只在確認全不透明時才啟用，不影響渲染正確性。

### buildBLASOpaque() / buildBLASWithOMM()

| 方法 | 適用情境 | 狀態 |
|------|---------|------|
| `buildBLAS(x, z, aabbs, count)` | 含透明幾何的 section | 已實作 |
| `buildBLASOpaque(x, z, aabbs, count)` | 純不透明 section；傳遞 `VK_GEOMETRY_OPAQUE_BIT_KHR` | 已實作 |
| `buildBLASWithOMM(device, x, z, triangleData, count, ommState)` | Phase 3：LOD 0 三角形幾何 + VK_EXT_opacity_micromap | Stub（Javadoc 預留介面） |

> **注意**：`VK_EXT_opacity_micromap` (OMM) 僅支援三角形幾何，AABB 幾何使用 `VK_GEOMETRY_OPAQUE_BIT_KHR` 作為等效優化替代。

## Camera UBO 與 SVGF 時序重投影

CameraUBO 佈局（256 bytes）：

| 偏移 | 欄位 | 大小 | 用途 |
|-----|------|------|------|
| 0   | `invViewProj` | 64B | 當前幀反視投影矩陣 |
| 64  | `prevInvViewProj` | 64B | 上一幀反視投影矩陣（SVGF reprojection） |
| 128 | `weatherData` | 16B | 天氣參數 |
| 144 | `frameIndex` | 4B | 幀計數器 |

GLSL 運動向量計算（`primary.rgen.glsl`）：

```glsl
mat4  prevVP       = inverse(cam.prevInvViewProj);    // GLSL 4.6 built-in
vec4  prevClip     = prevVP * vec4(worldPos, 1.0);
vec2  prevNDC      = prevClip.xy / max(abs(prevClip.w), 1e-6); // 防除零
vec2  prevUV       = prevNDC * 0.5 + 0.5;
vec2  motionVector = uv - prevUV;
```

## DAG SSBO GPU 格式

`BRSparseVoxelDAG.serializeForGPU()` 輸出 GPU-native ByteBuffer：

```
Header (8 × u32):
  [0] nodeCount  [1] dagDepth
  [2] originX    [3] originY    [4] originZ
  [5] dagSize    [6] rootIndex  [7] _pad

Per-node (9 × u32, stride = DAG_NODE_STRIDE = 9):
  [0] flags = childMask(8b) | matId(8b) | lodLevel(8b) | _reserved(8b)
  [1..8] child[0..7]  ← 固定 8 slot，空槽 = 0（非壓縮）
```

> Java 內部使用壓縮陣列（只儲存已存在的子節點），`serializeForGPU()` 展開為 8-slot 固定格式，供 GPU O(1) octant 索引存取。

## BRVulkanInterop (GL/VK 共享)

```
VkRTPipeline.dispatchRays() → VkImage RGBA16F
    ↓  VK_KHR_external_memory (export fd)
BRRTCompositor.executeRTPass() ← GL texture (import fd)
    ↓  SVGF/NRD denoiser
    ↓  compositeRTResult() fullscreen quad
OpenGL backbuffer
```

- `VK_KHR_external_memory` + `GL_EXT_memory_object` 零拷貝
- Fallback：interop 不可用時使用 CPU readback

## 著色器路徑

| 路徑 | 硬體 | 功能差異 |
|------|------|---------|
| `shaders/rt/ada/` | Ada SM 8.9（RTX 40xx） | SER (`hitObjectTraceRayNV`)、Ray Query RTAO、OMM、DAG GI（4 探針） |
| `shaders/rt/blackwell/` | Blackwell SM 10.x（RTX 50xx） | Cluster AS（4×4 section 群組）、16 AO 取樣、2 GI bounce |

## ForgeRenderEventBridge 整合點

| 事件 | 觸發時機 | RT 相關操作 |
|------|---------|-----------|
| `AFTER_SOLID_BLOCKS` | 不透明幾何渲染後 | `VkAccelStructBuilder.updateTLAS()` |
| `AFTER_TRANSLUCENT_BLOCKS` | 半透明幾何渲染後 | `BRRTCompositor.executeRTPass()` |
| `BlockEvent.NeighborNotifyEvent` | 方塊放置/更新（客戶端） | `BRRTCompositor.markSectionTransparent()` 更新 OMM 路由快取 |
| `ChunkEvent.Load/Unload` | Chunk 生命週期 | `ChunkRenderBridge.onChunkLoad/Unload()` |

## GBuffer 附件整合（RT-5-2）

`BRGBufferAttachments`（同套件）管理延遲著色所需的全部 GBuffer 附件 VkImage，並在降噪器、體積光照、FSR 中提供真實 VkImageView handle（替代先前的 0L 佔位符）。

| 附件 | 格式 | Layout | 用途 |
|------|------|--------|------|
| `depth[0/1]` | R32F | GENERAL | 線性深度 ping-pong（cur/prev 幀交替） |
| `normal` | RGBA16F | SHADER_READ_ONLY_OPTIMAL | 世界空間法線 + 材料粗糙度 |
| `albedo` | RGBA8_UNORM | SHADER_READ_ONLY_OPTIMAL | 漫反射顏色 + 材料類型 |
| `material` | RGBA8_UNORM | SHADER_READ_ONLY_OPTIMAL | Roughness / Metallic / Emissive / Flags |

**生命週期接線**：
- `initOutputImage(w, h)` 成功後自動呼叫 `BRGBufferAttachments.getInstance().init(w, h)`
- `BRRTPipelineOrdering` 在每條路徑幀結束時呼叫 `swapDepthBuffers()`
- `dispatchReLAXFallback()` 使用 `gbuf.getDepthView()` / `getPrevDepthView()` / `getNormalView()` 及 `rtOutputImageView`

```java
// RT-5-2 接線後的 dispatchReLAXFallback 核心邏輯
BRGBufferAttachments gbuf = BRGBufferAttachments.getInstance();
BRReLAXDenoiser.denoise(
    rtOutputImageView,      // RT 輸出（RGBA16F，GENERAL）
    gbuf.getDepthView(),    // 當前幀深度（R32F，GENERAL）
    gbuf.getPrevDepthView(),// 前一幀深度（R32F，GENERAL）
    gbuf.getNormalView()    // 法線（RGBA16F，SHADER_READ_ONLY）
);
```

## 關聯接口

- 被依賴 ← [RenderPipeline](L3-pipeline.md) — Tier 3 光線追蹤 Pass
- 依賴 → [GreedyMesher](L3-mesher.md) — BLAS AABB 幾何來源
- 依賴 → [BRSparseVoxelDAG](../L2-node/index.md) — DAG SSBO GPU 序列化
- 依賴 → `BRGBufferAttachments`（同套件）— GBuffer VkImage 附件管理（RT-5-2）
- 相關 → `fastdesign` [VulkanRTConfigNode](../../L1-fastdesign/L2-node-editor/index.md) — 節點編輯器 RTEffect 控制
