# Block Reality — 渲染管線遷移計劃（合併版）

## Voxy LOD + Vulkan RT 全路徑追蹤移植方案

> 版本：v2.0 | 日期：2026-03-31 | 狀態：計劃中
> 合併自：`LOCAL/RENDER_MIGRATION_PLAN.md`（v1.0）+ RT 移植技術分析
> 目標：RTX 30 以上、低相容高性能、不兼容其他模組

---

## 0. 兩版差異分析與合併決策

| 議題 | v1.0（原始計劃） | 技術分析 | **合併決策** |
|------|----------------|---------|------------|
| GL 後備路徑 | 保留，分 4 個 Tier | 完全拋棄 GL | **保留 LOD_ONLY 後備**（Phase 4 前必須有可用渲染） |
| 降噪器 | 先做簡易時序累積，後續再 NRD | 直接 NRD ReLAX/ReBLUR | **Phase 2 用時序累積，Phase 3 換 NRD** |
| LOD 體素 | 直抄 Voxy LodBuilder | 自研 4 級 3D LOD + SVDAG 遠場 | **近中場抄 Voxy，遠場加 SVDAG 軟追蹤** |
| 體素化 | 保留 CPU GreedyMesher | GPU 計算著色器體素化 | **Phase 1 用 GreedyMesher，Phase 3 加 GPU 體素化** |
| 套件命名 | 新建 `rendering/`（與舊 `render/` 並存） | 重構舊 `render/` | **採用 v1.0 的 `rendering/` 新套件**，避免衝突 |
| 彈射次數 | 未明確 | 2-3 次 | **RTX 30 基線 2 次，RTX 40 可 3 次** |
| Mesh Shader | 未提及 | VK_EXT_mesh_shader G-Buffer | **Phase 4 加入**（RTX 30+ 皆支援） |
| OMM / SER | 未提及 | Ada 進階優化 | **Phase 4 加入**（偵測到 Ada 時啟用） |
| 時程 | 10-14 週 | 15-22 週 | **12-16 週**（取中間值） |

---

## 1. 前置說明

### 參考來源與移植範圍

| 來源 | 移植內容 | 方式 |
|------|---------|------|
| **Voxy** (MIT) | LOD mesh 演算法、GLSL shader | 直接移植 Java 程式碼 |
| **Radiances/MCVR** (GPL-3.0) | BLAS/TLAS 建構邏輯、RT shader | 概念移植，用 LWJGL 重寫 |
| **Continuum RT** | GPU 體素化思路、NRD 遷移路徑 | 技術概念參考 |
| **Sascha Willems** | SBT 組裝邏輯 | C++ 翻譯成 LWJGL Java |

### 不用 JNI 的理由

Radiances 用 JNI 橋接 C++ 是因為它從零建 Vulkan 管線。我們已有 `lwjgl-vulkan:3.3.1`（在 build.gradle 中），可完全在 Java 內透過 LWJGL 操作 Vulkan API。

### 硬體目標

| 最低需求 | RTX 3060（第 2 代 RT 核心） |
|---------|--------------------------|
| 建議配置 | RTX 4060（OMM + SER 加速） |
| VRAM | ≥ 8 GB |
| Vulkan | 1.2+，`VK_KHR_ray_tracing_pipeline` |

---

## 2. 目標架構

### 廢棄 → 替換對照

| 舊系統 | 新系統 | 階段 |
|--------|--------|------|
| `BRRenderPipeline` (GL) | `VkRTPipeline` (VK) | Phase 2 |
| `BRFramebufferManager` | Vulkan framebuffer | Phase 2 |
| `BRLODEngine` | `VoxyLODMesher` | Phase 1 |
| `BRCascadedShadowMap` | RT 陰影光線 | Phase 3 |
| `BRGlobalIllumination` (SSGI+VCT) | RT 多彈射 GI | Phase 3 |
| `BRSVGFDenoiser` | NRD ReLAX/ReBLUR | Phase 3 |
| `BRVulkanInterop` (GL↔VK) | 純 VK 呈現 | Phase 2 |
| `BRWaterRenderer` | RT 反射 shader | Phase 3 |

### 保留不動

```
GreedyMesher.java         ← Voxy LOD 複用（Phase 1）
BRSparseVoxelDAG.java     ← 遠場 SVDAG 軟追蹤（Phase 3）
BRAnimationEngine.java    ← 動畫系統獨立
BRRenderConfig.java       ← 擴充 RT 參數
BRRenderTier.java         ← 加入 RT tier 偵測
ClientStressCache.java    ← 接入 RT 應力視覺化
所有 physics/ 系統         ← 完全不動
所有 node editor 系統      ← 僅更新 RenderConfigBinder
```

### 新套件結構

```
api/client/rendering/                    ← 全新套件
  lod/
    VoxyLODMesher.java                   ← 移植 Voxy LodBuilder
    LODChunkManager.java                 ← Chunk 生命週期
    LODTerrainBuffer.java                ← GPU buffer 管理
    LODRenderDispatcher.java             ← 調度 LOD 更新
    BRVoxelLODManager.java               ← 4 級 3D LOD 管理（新增）
    LODSection.java                      ← LOD 體素資料結構（新增）
  vulkan/
    VkContext.java                       ← 翻譯自 Radiance MCVR
    VkAccelStructBuilder.java            ← BLAS/TLAS（LOD 感知）
    VkRTPipeline.java                    ← 多彈射路徑追蹤
    VkRTShaderPack.java                  ← shader 載入
    VkMemoryAllocator.java               ← VMA wrapper
    VkSwapchain.java                     ← 呈現鏈
    VkGBuffer.java                       ← 純 VK G-Buffer（新增）
    BRNRDDenoiser.java                   ← NRD ReLAX+ReBLUR（新增）
    BRGPUVoxelizer.java                  ← GPU 計算體素化（新增）
  bridge/
    ChunkRenderBridge.java               ← Forge ChunkEvent 橋接
    ForgeRenderEventBridge.java          ← RenderLevelStageEvent 橋接
  physics/
    StressVisualizationRT.java           ← 應力熱圖 RT any-hit
```

---

## Phase 0 — 依賴整理 & 骨架建立（3-5 天）

### 任務

**0-1. 修改 `build.gradle`（api 子專案）**

```gradle
implementation "org.lwjgl:lwjgl-vulkan:3.3.1"
implementation "org.lwjgl:lwjgl-vma:3.3.1"
runtimeOnly "org.lwjgl:lwjgl-vulkan:3.3.1:natives-windows"
runtimeOnly "org.lwjgl:lwjgl-vulkan:3.3.1:natives-linux"
runtimeOnly "org.lwjgl:lwjgl-vma:3.3.1:natives-windows"
runtimeOnly "org.lwjgl:lwjgl-vma:3.3.1:natives-linux"
```

**0-2. `BRRenderTier` 加入 VK RT 偵測**

```java
public static boolean supportsVulkanRT() {
    // VK10.vkEnumerateInstanceExtensionProperties
    // 檢查 VK_KHR_ray_tracing_pipeline
}
```

**0-3. 建立空套件骨架** — `api/client/rendering/` 下所有空 `.java`

**0-4. 舊 pipeline 標記 `@Deprecated`**（不刪，Phase 4 後移除）

**0-5. 驗證** — `./gradlew :api:compileJava` 通過

---

## Phase 1 — Voxy LOD 移植（2-3 週）

### 目標

遠距地形以 3D LOD 渲染，視野 512+ chunk，幀率 +30%。

### 1-A. LOD Mesh 生成

**移植自 Voxy：**

| Voxy 原始類別 | 移植為 | 說明 |
|---|---|---|
| `LodBuilder` | `VoxyLODMesher.java` | 核心演算法直接移植 |
| `GeometryManager` | `LODTerrainBuffer.java` | GPU buffer 管理 |
| `ChunkLoadManager` | `LODChunkManager.java` | Chunk 生命週期 |

**複用現有：** `GreedyMesher.java`（LOD mesh 底層）、`BRSparseVoxelDAG.java`（遠距體素存儲）

### 1-B. 4 級 LOD 設計（合併兩版優點）

| 距離（chunk） | LOD 等級 | Mesh 精度 | BLAS 策略 |
|---|---|---|---|
| 0-8 | LOD 0（原始） | 全精度 16³ | 完整三角形 BLAS |
| 8-32 | LOD 1 | 2×2 合併 8³ | 降解析度 BLAS |
| 32-128 | LOD 2 | 4×4 合併 4³ | 極簡 BLAS |
| 128-512 | LOD 3 | 8×8 合併 2³ | SVDAG 軟追蹤（不進 TLAS） |
| 512+ | LOD 4 | 16×16 合併 | 僅 GI 光線觸及 |

### 1-C. Forge Hook 橋接

| Voxy Fabric Hook | Forge 替代 |
|---|---|
| `ChunkBuilderPlugin` | `ChunkEvent.Load` + `ChunkEvent.Unload` |
| `ClientTickEvents.END_CLIENT_TICK` | `ClientTickEvent.Post` |
| Sodium `ChunkMeshBuildingEvent` | `RenderLevelStageEvent.AFTER_CHUNK_ENTITIES` |

### 1-D. LOD GLSL Shader

移植 Voxy `lod.vert` / `lod.frag` → `assets/blockreality/shaders/lod/`
移除 Sodium `#ifdef`，調整 Uniform 對應 Block Reality UBO。

### 1-E. 記憶體預算（新增 SVDAG 遠場）

| 渲染距離 | 無 LOD | 有 LOD + SVDAG | 節省 |
|---------|--------|---------------|------|
| 32 chunks | ~800 MB | ~150 MB | 81% |
| 128 chunks | 不可行 | ~250 MB | ∞ |
| 512 chunks | 不可行 | ~400 MB | ∞ |

### 1-F. 檢查點

```
✓ 遠距地形有 LOD 漸變（先用 GL 模式驗證，不需 RT）
✓ 幀率比純 vanilla 提升 30%+（512 chunk）
✓ LOD mesh 正確跟隨玩家移動更新
✓ VRAM 使用量 ≤ 2 GB
```

---

## Phase 2 — Vulkan RT 基礎建設（3-4 週）

### 目標

螢幕顯示第一個 RT 結果。先跑通管線，效果後續加。

### 2-A. VkContext.java — Vulkan 裝置初始化

翻譯自 Radiance MCVR `VulkanBase.cpp` → LWJGL Java：

```
VkInstance → VkPhysicalDevice → VkDevice
  ├── Graphics Queue + Compute Queue
  ├── VkCommandPool
  └── VkSwapchain
```

**必要擴展（RTX 30+ 全支援）：**
```
VK_KHR_ray_tracing_pipeline
VK_KHR_acceleration_structure
VK_KHR_deferred_host_operations
VK_KHR_buffer_device_address
VK_EXT_descriptor_indexing
```

**新增偵測（Phase 4 啟用）：**
```
VK_EXT_mesh_shader              ← mesh shader G-Buffer
VK_KHR_ray_query                ← 計算著色器內聯 RT
VK_EXT_opacity_micromap         ← Ada OMM
VK_EXT_ray_tracing_invocation_reorder ← Ada SER
```

### 2-B. VkAccelStructBuilder.java — LOD 感知 BLAS/TLAS

**合併兩版的 BLAS 策略：**

| Chunk 類型 | BLAS 策略 | 更新頻率 |
|---|---|---|
| LOD 0 近場（靜態） | 完整三角形，`PREFER_FAST_TRACE` | build 一次 |
| LOD 0 近場（動態） | 完整三角形，`PREFER_FAST_BUILD` | 每 4 tick rebuild |
| LOD 1-2 中場 | 降解析度三角形 | LOD 切換時 rebuild |
| 實體 | bounding box 簡化 | 每幀 update |

**雙緩衝：** 前/後 BLAS 避免 GPU 停頓（技術分析新增）
**refit vs rebuild：** 單方塊變更用 refit，大規模變更用 rebuild（技術分析新增）
**每幀預算：** 最多 32 個 BLAS 重建（從 v1.0 的 8 個提升）

**TLAS：** 每幀重建（`PREFER_FAST_BUILD`），僅包含 LOD 0-2。LOD 3+ 走 SVDAG。

### 2-C. VkRTPipeline.java + SBT

SBT 組裝沿用 Sascha Willems alignment 公式：

```java
int sbtStride = alignedSize(shaderGroupHandleSize, shaderGroupBaseAlignment);
KHRRayTracingPipeline.vkGetRayTracingShaderGroupHandlesKHR(
    device, rtPipeline, 0, shaderGroupCount, shaderHandleStorage
);
```

### 2-D. 降噪策略（分階段）

| 階段 | 降噪器 | 原因 |
|------|--------|------|
| Phase 2 | **時序累積 + bilateral filter** | 快速可用，不需外部 SDK |
| Phase 3 | **NRD ReLAX（漫反射）+ ReBLUR（鏡面）** | 產業標準，Radiance/Continuum 都在用 |

Phase 2 的簡易降噪：
```
每幀混合歷史幀（alpha = 0.1）
spatial bilateral filter（深度 + 法線邊緣停止）
```

### 2-E. 最小可驗證目標

raygen shader 只做 hit→白色 / miss→天空藍，RT 結果疊在畫面上確認管線跑通。

### 2-F. 檢查點

```
✓ Vulkan 裝置初始化不崩
✓ BLAS/TLAS 成功建構
✓ 螢幕能看到 RT 輸出
✓ 零 Vulkan validation layer error
```

---

## Phase 3 — RT 效果實作 + NRD 降噪（3-4 週）

### 目標

多彈射路徑追蹤取代所有舊照明系統，NRD 降噪。

### 3-A. 多彈射路徑追蹤（技術分析新增）

```
彈射 0: 主光線（從 G-Buffer 重建世界座標）
彈射 1: 直接照明 + 陰影光線（取代 CSM）
彈射 2: 間接照明 / 漫反射 GI（取代 SSGI + VCT）
彈射 3: 鏡面反射/折射（水、玻璃）（RTX 40 可選）
```

**RTX 30 基線：2 彈射 | RTX 40 可選：3 彈射**

### 3-B. 統一照明模型（取代分散系統）

| 舊系統（分散） | 新系統（統一 RT） | 週次 |
|------------|--------------|------|
| CSM 陰影 | RT 陰影光線（面光源軟陰影） | 第 1 週 |
| SSGI + VCT | RT 漫反射 GI（1 bounce 天空光） | 第 2 週 |
| SSR | RT 鏡面反射（水面/玻璃） | 第 3 週 |
| SSAO | RT 環境遮蔽 | 第 3 週 |
| `BRWaterRenderer` | RT 反射+折射 shader | 第 3 週 |

### 3-C. NRD 降噪整合

NRD 是純 GLSL/HLSL 著色器庫，編譯為 SPIR-V 在 VK 計算管線執行：

```
路徑追蹤輸出
  ├── 漫反射輻照度 → ReLAX Diffuse
  ├── 鏡面輻照度 → ReBLUR Specular
  └── 陰影遮蔽 → SIGMA Shadow
      ↓
  合成最終影像
```

### 3-D. GPU 計算體素化（技術分析新增）

取代 CPU GreedyMesher 供近場 BLAS 使用：

```
計算著色器讀取 section 方塊資料（SSBO）
  → 輸出三角形幾何（硬體三角形求交更快）
  → 直接寫入 VK BLAS 構建輸入
```

GreedyMesher 保留供 LOD mesh 和非 RT 後備使用。

### 3-E. SVDAG 遠場軟追蹤（技術分析新增）

LOD 3+（>128 chunks）不進 TLAS，改用計算著色器：

```glsl
// Compute shader — SVDAG 遍歷
// 僅 GI 間接光線的遠距取樣
bool traceSVDAG(vec3 origin, vec3 dir, float maxDist,
                out vec3 hitPos, out uint hitMaterial) {
    // DDA 步進穿越 SVDAG，目標 LOD 早期終止
}
```

SVDAG 壓縮比 5-10x，128+ chunk 僅需 ~50 MB。

### 3-F. 物理視覺化整合

```glsl
// closest-hit shader 中讀取 StressBuffer
float stress = stressBuffer.data[primitiveID].stress;
vec3 stressColor = mix(vec3(0,1,0), vec3(1,0,0), stress);
hitColor = mix(hitColor, stressColor, 0.4 * stress);
```

### 3-G. 檢查點

```
✓ 陽光產生正確軟陰影（無 light leak）
✓ 水面能看到玩家倒影
✓ 金屬方塊有光澤反射
✓ 天空光照亮建築背陰面
✓ NRD 降噪品質優於舊 SVGF
✓ 應力熱圖透過 RT any-hit 正確著色
```

---

## Phase 4 — 整合收尾 + 硬體優化（2-3 週）

### 4-A. LOD → BLAS 直接對接

`VoxyLODMesher` 輸出直接餵進 `VkAccelStructBuilder`，LOD 等級自動對應 BLAS 精度。

### 4-B. RTX 40 Ada 進階優化（技術分析新增）

偵測到 Ada 硬體時啟用：

| 特性 | 效果 | Vulkan 擴展 |
|------|------|------------|
| **Opacity Micromap** | 樹葉/柵欄/玻璃 RT 成本 -39% | `VK_EXT_opacity_micromap` |
| **Shader Execution Reorder** | 材質發散場景 +20-40% | `VK_EXT_ray_tracing_invocation_reorder` |
| **Mesh Shader G-Buffer** | GPU 驅動幾何，單次 draw call | `VK_EXT_mesh_shader` |

### 4-C. ray_query 雙軌策略（技術分析新增）

| 效果 | API | 原因 |
|------|-----|------|
| 主路徑追蹤 | `ray_tracing_pipeline` | 多彈射、自訂 shader |
| 簡易陰影/AO | `ray_query`（計算著色器） | 更輕量 |

### 4-D. 節點編輯器更新

`RenderConfigBinder.applyValue()` 新增：

```java
case "rtShadowRays"        -> c.rtShadowRayCount = toInt(value);
case "rtReflectionBounces"  -> c.rtReflectionBounces = toInt(value);
case "rtGIEnabled"          -> c.rtGIEnabled = toBool(value);
case "rtDenoiserStrength"   -> c.rtDenoiserStrength = toFloat(value);
case "lodMaxDistance"       -> c.lodMaxChunks = toInt(value);
```

### 4-E. Tier Fallback 策略

```
GPU 偵測
├── VK RT + VRAM ≥ 8GB + Ada  → RT_ULTRA（全 RT + OMM + SER）
├── VK RT + VRAM ≥ 8GB        → RT_HIGH（全 RT，2 bounce）
├── VK RT + VRAM < 8GB        → RT_BALANCED（RT，降低 ray count）
├── 無 RT + GL 4.6            → LOD_ONLY（Voxy LOD + 舊 raster）
└── GL < 4.6                  → LEGACY（舊 pipeline）
```

### 4-F. 移除舊 pipeline

確認 Phase 3 完成且穩定後才執行：

```
render/pipeline/BRRenderPipeline.java          ← 移除
render/pipeline/BRFramebufferManager.java      ← 移除
render/effect/BRWaterRenderer.java             ← 移除
render/effect/BRWeatherEngine.java             ← 移除
render/effect/BRCloudRenderer.java             ← 移除
render/optimization/BRLODEngine.java           ← 移除
render/shadow/BRCascadedShadowMap.java         ← 移除
render/postfx/BRGlobalIllumination.java        ← 移除
render/rt/BRSVGFDenoiser.java                  ← 移除
render/rt/BRVulkanInterop.java                 ← 移除
```

### 4-G. 驗證

```bash
./gradlew build       # 零 error
./gradlew test        # 所有 JUnit 5 通過
./gradlew mergedJar   # 合併 JAR 正常
```

---

## 5. 效能目標

### RTX 3060（1080p 內部 → DLSS/FSR 升頻 4K）

| 階段 | 預算 |
|------|------|
| G-Buffer（VK 光柵化） | 2.0 ms |
| BLAS/TLAS 更新 | 1.0 ms |
| 路徑追蹤（2 bounce, 1 SPP） | 4.0 ms |
| NRD 降噪 | 2.0 ms |
| SVDAG 遠場 | 1.0 ms |
| 合成 + 後製 | 1.5 ms |
| DLSS/FSR | 1.0 ms |
| **總計** | **12.5 ms → 80 FPS** |

### RTX 4060（含 OMM + SER）

| 階段 | 預算 |
|------|------|
| G-Buffer（Mesh Shader） | 1.5 ms |
| BLAS/TLAS 更新 | 0.8 ms |
| 路徑追蹤（3 bounce, 1 SPP） | 2.5 ms |
| NRD 降噪 | 1.5 ms |
| SVDAG 遠場 | 0.8 ms |
| 合成 + 後製 | 1.0 ms |
| DLSS | 0.8 ms |
| **總計** | **8.9 ms → 112 FPS** |

---

## 6. 風險表

| 風險 | 嚴重度 | 緩解 |
|------|--------|------|
| Forge `ChunkEvent.Load` 與 Voxy 語意差異 | **高** | Phase 1 先做 PoC 驗證 |
| LWJGL VK natives 在 Forge 環境缺失 | **高** | Phase 0 立即用 `vkEnumerateInstanceVersion()` 測試 |
| SBT alignment 錯誤（RT 最常見崩潰） | **高** | 沿用 Sascha Willems 公式 |
| NRD SPIR-V 編譯複雜度 | **中** | NRD 官方提供 SPIR-V |
| VK 與 GL 上下文共存 | **中** | Phase 2 用 `VK_KHR_external_memory` 過渡，Phase 4 完全接管 |
| LOD 接縫/彈出 | **中** | 漸進混合 + 時序累積隱藏 |
| BLAS 記憶體碎片化 | **中** | 定期壓實 + VMA 管理 |
| 遷移期間舊 pipeline 衝突 | **低** | `BRRenderTier` flag 切換 |

---

## 7. 時間估計

| 階段 | 時間 | 前置條件 | 可並行 |
|------|------|---------|--------|
| **Phase 0** | 3-5 天 | — | — |
| **Phase 1** | 2-3 週 | Phase 0 | ✅ 與 Phase 2 並行 |
| **Phase 2** | 3-4 週 | Phase 0 | ✅ 與 Phase 1 並行 |
| **Phase 3** | 3-4 週 | Phase 2 | ❌ 需 RT 管線跑通 |
| **Phase 4** | 2-3 週 | Phase 1 + 3 | ❌ 需全部就緒 |
| **合計** | **~12-16 週** | | |

```
週次   1  2  3  4  5  6  7  8  9  10  11  12  13  14  15  16
Phase0 ██
Phase1    ████████
Phase2    ████████████
Phase3                   ████████████
Phase4                                    ██████████
```

---

## 8. 廢棄 vs 保留速查

### 🗑️ 廢棄（Phase 4 移除）

```
render/pipeline/BRRenderPipeline.java
render/pipeline/BRFramebufferManager.java
render/pipeline/RenderPass.java
render/effect/BRWaterRenderer.java
render/effect/BRWeatherEngine.java
render/effect/BRCloudRenderer.java
render/effect/BRAtmosphereEngine.java
render/optimization/BRLODEngine.java
render/shadow/BRCascadedShadowMap.java
render/postfx/BRGlobalIllumination.java
render/rt/BRSVGFDenoiser.java
render/rt/BRVulkanInterop.java
```

### ✅ 永久保留

```
render/BRRenderConfig.java
render/BRRenderTier.java
render/animation/BRAnimationEngine.java
render/optimization/GreedyMesher.java
render/optimization/BRSparseVoxelDAG.java
render/optimization/BRGPUProfiler.java
client/ClientStressCache.java
client/StressHeatmapRenderer.java
```

### 🟡 視情況

```
render/effect/BRParticleSystem.java    ← Phase 3 後決定
render/effect/BRLensFlare.java         ← 可用 RT 替換
render/optimization/BRGPUCulling.java  ← RT 不需要，但可作 fallback
```

---

*合併自 v1.0 原始計劃 + RT 移植技術分析 | Block Reality 開發組*
