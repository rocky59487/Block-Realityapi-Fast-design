# Block Reality — 渲染管線遷移計劃
## Voxy LOD + Radiance RT 真光追移植方案

> 版本：v1.0 | 日期：2026-03-30 | 狀態：計劃中

---

## 目錄

1. [前置說明：能抄與不能抄的邊界](#1-前置說明能抄與不能抄的邊界)
2. [整體架構方向](#2-整體架構方向)
3. [Phase 0 — 依賴整理 & 套件建立](#phase-0--依賴整理--套件建立3-5-天)
4. [Phase 1 — Voxy LOD 移植](#phase-1--voxy-lod-移植2-3-週)
5. [Phase 2 — Vulkan RT 基礎建設](#phase-2--vulkan-rt-基礎建設3-4-週)
6. [Phase 3 — RT 效果實作](#phase-3--rt-效果實作3-4-週)
7. [Phase 4 — 整合收尾](#phase-4--整合收尾1-2-週)
8. [風險表](#8-風險表)
9. [時間估計總覽](#9-時間估計總覽)
10. [新套件結構速查](#10-新套件結構速查)
11. [廢棄 vs 保留檔案清單](#11-廢棄-vs-保留檔案清單)

---

## 1. 前置說明：能抄與不能抄的邊界

### 參考來源

| 來源 | Repo | Loader | 語言 | License |
|---|---|---|---|---|
| **Radiance** | `Minecraft-Radiance/Radiance` + `MCVR` | Fabric / NeoForge | Java + C++ (JNI) | GPL-3.0 |
| **Voxy** | `MCRcortex/voxy` | **Fabric only** | Java 96% + GLSL 4% | MIT |

### ✅ 可以直接抄的

- **Voxy** `LodBuilder.java` 等 LOD mesh 生成演算法（純 Java，語言相同，直接移植）
- **Voxy** 的 GLSL LOD shader（`lod.vert` / `lod.frag`）
- **Radiance** 的 GLSL RT shader（raygen / closest-hit / miss / any-hit）
- **Radiance** BLAS/TLAS 建構邏輯（概念 + 程式碼，需改成 LWJGL Java API）
- **Sascha Willems** SBT 組裝邏輯（從 C++ 翻譯成 LWJGL Java，alignment 公式直接用）

### ❌ 不能直接抄的（需要重寫）

| 無法抄的部分 | 原因 | 替代方案 |
|---|---|---|
| Voxy 的 `ChunkBuilderPlugin` | Fabric 專屬 API | Forge `ChunkEvent.Load` / `Unload` |
| Voxy 的 Sodium 整合 | Sodium 是 Fabric mod | 不需要，直接對接新 Vulkan pipeline |
| Radiance 的 C++ JNI backend (MCVR) | 需要 native 編譯，維護成本高 | 直接使用 `lwjgl-vulkan:3.3.1`（已在 build.gradle）|
| Radiance 的 DLSS/FSR/XeSS 整合 | 需要廠商 SDK | Phase 4 後視需求決定 |

### 🟡 好消息：已有基礎

`build.gradle` 已有 `org.lwjgl:lwjgl-vulkan:3.3.1`（目前是 `compileOnly`）
→ **Phase 0 最重要的任務就是把它改成 `implementation` 並補上 natives**

---

## 2. 整體架構方向

### 廢棄（替換）

```
api/client/render/
  BRRenderPipeline.java          ← 替換為 VkRTPipeline
  BRFramebufferManager.java      ← 替換為 Vulkan framebuffer
  BRLODEngine.java               ← 替換為 VoxyLODMesher
  effect/BRCloudRenderer.java    ← 替換為 RT atmosphere shader
  effect/BRWaterRenderer.java    ← 替換為 RT reflection shader
  effect/BRWeatherEngine.java    ← 整合進 RT miss shader
  pipeline/RenderPass.java       ← 替換為 VkRenderPass
  (所有 OpenGL FBO 相關檔案)
```

### 保留（不動）

```
BRRenderConfig.java              ← 設定系統保留，新增 RT 參數
BRRenderTier.java                ← 保留，加入 RT tier 偵測
BRAnimationEngine.java           ← 動畫系統獨立，不影響
GreedyMesher.java                ← 供 Voxy LOD 複用
BRSparseVoxelDAG.java            ← 供 Voxy 遠距體素存儲
所有 physics/ 系統               ← 完全不動
所有 node editor 系統            ← 只需更新 RenderConfigBinder
ClientStressCache.java           ← 保留，接入 RT 應力視覺化
```

### 新套件結構

```
api/client/rendering/            ← 全新套件，取代舊 render/
  lod/
    VoxyLODMesher.java           ← 抄自 Voxy LodBuilder
    LODChunkManager.java         ← 管理 BLAS-per-chunk 生命週期
    LODTerrainBuffer.java        ← GPU buffer 管理
    LODRenderDispatcher.java     ← 調度 LOD 更新 vs. RT
  vulkan/
    VkContext.java               ← Instance / Device / Queue / CommandPool
    VkAccelStructBuilder.java    ← BLAS / TLAS 建構
    VkRTPipeline.java            ← RT pipeline + SBT
    VkRTShaderPack.java          ← raygen / chit / miss shader 載入
    VkMemoryAllocator.java       ← VMA wrapper
    VkSwapchain.java             ← Swapchain 管理
  bridge/
    ChunkRenderBridge.java       ← Forge ChunkEvent → LOD pipeline
    ForgeRenderEventBridge.java  ← RenderLevelStageEvent → RT pipeline
  physics/
    StressVisualizationRT.java   ← 應力熱圖改用 RT any-hit shader
```

---

## Phase 0 — 依賴整理 & 套件建立（3–5 天）

### 目標

Vulkan 初始化不崩，新套件骨架建立完成。

### 任務清單

#### 0-1. 修改 `build.gradle`（api 子專案）

```gradle
// 把 compileOnly 改成 implementation
implementation "org.lwjgl:lwjgl-vulkan:3.3.1"
implementation "org.lwjgl:lwjgl-vma:3.3.1"        // VMA — 記憶體分配器

// 加入 natives（Windows / Linux / macOS）
runtimeOnly "org.lwjgl:lwjgl-vulkan:3.3.1:natives-windows"
runtimeOnly "org.lwjgl:lwjgl-vulkan:3.3.1:natives-linux"
runtimeOnly "org.lwjgl:lwjgl-vma:3.3.1:natives-windows"
runtimeOnly "org.lwjgl:lwjgl-vma:3.3.1:natives-linux"
```

#### 0-2. `BRRenderTier.java` 加入 Vulkan RT 偵測

```java
// 在 detect() 中加入
public static boolean supportsVulkanRT() {
    // 用 VK10.vkEnumerateInstanceExtensionProperties 檢查
    // VK_KHR_ray_tracing_pipeline 是否存在
    // shaderGroupHandleSize 是 32（標準）或 16（某些 AMD）
}
```

#### 0-3. 建立空套件骨架

建立 `api/client/rendering/` 下所有空 `.java` 檔（只有 package 宣告），確保 Gradle 能編譯。

#### 0-4. 廢棄標記舊 pipeline

```java
@Deprecated(since = "2.0", forRemoval = true)
public class BRRenderPipeline { ... }
```

不刪，Phase 3 完成後再移除。

#### 0-5. 驗證

```bash
./gradlew :api:compileJava   # 必須通過，無 error
```

---

## Phase 1 — Voxy LOD 移植（2–3 週）

### 目標

遠距地形能以低多邊形 LOD 渲染，視野拉到 512+ chunk，幀率比純 vanilla 高 30%+。

### 1-A. LOD Mesh 生成（抄 Voxy Java 程式碼）

**來源：** `MCRcortex/voxy` → `src/main/java/me/cortex/voxy/client/core/rendering/`

| Voxy 原始類別 | 移植為 | 說明 |
|---|---|---|
| `LodBuilder` | `VoxyLODMesher.java` | 核心演算法，幾乎可直接用 |
| `GeometryManager` | `LODTerrainBuffer.java` | GPU buffer 管理 |
| `ChunkLoadManager` | `LODChunkManager.java` | Chunk 生命週期 |

**複用現有：**
- `GreedyMesher.java` — 作為 LOD mesh 底層優化
- `BRSparseVoxelDAG.java` — 遠距 chunk 的體素存儲

**LOD 等級設計：**

| 距離（chunk） | LOD 等級 | Mesh 精度 |
|---|---|---|
| 0–32 | 原始 Minecraft chunk | 全精度 |
| 32–128 | LOD-1 | 2×2 方塊合併 |
| 128–512 | LOD-2 | 4×4 方塊合併 |
| 512+ | LOD-3 | 8×8 方塊合併 |

### 1-B. Forge Hook 橋接

Voxy 用 Fabric API，需要換成 Forge 等價物：

| Voxy Fabric Hook | Forge 替代 | 實作位置 |
|---|---|---|
| `ChunkBuilderPlugin` | `ChunkEvent.Load` + `ChunkEvent.Unload` | `ChunkRenderBridge.java` |
| `ClientTickEvents.END_CLIENT_TICK` | `ClientTickEvent.Post` | `ChunkRenderBridge.java` |
| Sodium `ChunkMeshBuildingEvent` | `RenderLevelStageEvent.AFTER_CHUNK_ENTITIES` | `ForgeRenderEventBridge.java` |

### 1-C. LOD GLSL Shader（抄 Voxy GLSL）

**來源：** `MCRcortex/voxy` → `src/main/resources/assets/voxy/shaders/`

移植：
- `lod.vert` → `resources/assets/blockreality/shaders/lod/lod.vert`
- `lod.frag` → `resources/assets/blockreality/shaders/lod/lod.frag`

需調整：
- Uniform 命名對應 Block Reality 的 UBO 格式
- 移除 Sodium 相關的 `#ifdef` 分支

### 1-D. BRConfig 新增 LOD 設定

```java
// 在 BRConfig 的 structure_engine 區段後新增 lod_engine 區段
builder.push("lod_engine");

lodMaxChunks = builder
    .comment("Maximum LOD render distance (chunks)")
    .defineInRange("lod_max_chunks", 512, 64, 4096);

lodLevel1Threshold = builder
    .comment("LOD-1 start distance (chunks)")
    .defineInRange("lod_level1_threshold", 32, 16, 256);
```

### 1-E. 檢查點

```
✓ 遠距地形有 LOD 漸變（無 RT，純 OpenGL 模式）
✓ 幀率比純 vanilla 提升 30%+（512 chunk 以上）
✓ LOD mesh 正確跟隨玩家移動更新
✓ 記憶體使用量合理（不超過 2GB VRAM）
```

---

## Phase 2 — Vulkan RT 基礎建設（3–4 週）

### 目標

GPU 上有一條能跑的 Vulkan RT pipeline，螢幕顯示第一個 RT 結果（哪怕只是全白/全紅）。

### 2-A. VkContext.java — Vulkan 裝置初始化

**來源：** 翻譯自 Radiance MCVR `VulkanBase.cpp` → LWJGL Java API

```
初始化順序：
VkInstance
  └─ VkPhysicalDevice（選擇支援 RT 的 GPU）
       └─ VkDevice（啟用必要 extension）
            ├─ VkQueue（graphics queue + compute queue）
            ├─ VkCommandPool
            └─ VkSwapchain
```

**必要 Vulkan Extension：**
```
VK_KHR_ray_tracing_pipeline
VK_KHR_acceleration_structure
VK_KHR_deferred_host_operations
VK_KHR_buffer_device_address
VK_EXT_descriptor_indexing
```

### 2-B. VkAccelStructBuilder.java — BLAS / TLAS

**來源：** Radiance MCVR `AccelerationStructure.cpp` 翻譯 + Sascha Willems `raytracingbasic.cpp`

**BLAS 策略：**

| Chunk 類型 | BLAS 更新頻率 | 說明 |
|---|---|---|
| 靜態（石頭、泥土） | 一次 build，永不更新 | Per-chunk BLAS |
| 動態（水流、活塞） | 每 4 tick rebuild | 或 fallback 到 raster |
| 實體（生物、掉落物） | 每幀 update | 使用 bounding box 簡化 |

**TLAS：** 整個場景的 BLAS instance 集合，每幀根據玩家視野更新。

### 2-C. VkRTPipeline.java — RT Pipeline + SBT

**SBT 組裝（從 Sascha Willems 抄，改成 LWJGL）：**

```java
// shaderGroupHandleSize 對齊 shaderGroupBaseAlignment
int sbtStride = alignedSize(shaderGroupHandleSize, shaderGroupBaseAlignment);

// LWJGL API 版本（對應 C++ 的 vkGetRayTracingShaderGroupHandlesKHR）
KHRRayTracingPipeline.vkGetRayTracingShaderGroupHandlesKHR(
    device, rtPipeline, 0, shaderGroupCount,
    shaderHandleStorage  // ByteBuffer
);
```

**RT Shader Stages：**

| Shader | 檔案 | 來源 |
|---|---|---|
| raygen | `rt_raygen.rgen.glsl` | 抄自 Radiance |
| closest-hit | `rt_chit.rchit.glsl` | 抄自 Radiance |
| miss | `rt_miss.rmiss.glsl` | 抄自 Radiance |
| shadow miss | `rt_shadow.rmiss.glsl` | 抄自 Radiance |

### 2-D. 第一個可驗證目標（最小可測試）

raygen shader 只做：
- hit → `imageStore(outputImage, pixel, vec4(1.0))` （白色）
- miss → `imageStore(outputImage, pixel, vec4(0.4, 0.6, 1.0, 1.0))` （天空藍）

RT 結果疊在 Minecraft 原生畫面上（50% 透明度混合），確認 RT pipeline 能跑通。

### 2-E. 檢查點

```
✓ Vulkan 裝置初始化不崩（有 RT 支援的 GPU）
✓ BLAS/TLAS 能成功建構（不需要正確，先跑通）
✓ 螢幕能看到 RT 輸出的白色/天空藍
✓ 沒有 Vulkan validation layer error
```

---

## Phase 3 — RT 效果實作（3–4 週）

### 目標

真實的光追陰影 + 反射，取代舊管線的假陰影/反射。

### 週次規劃

| 週次 | 效果 | 抄哪裡 | 取代的舊系統 |
|---|---|---|---|
| 第 1 週 | Primary visibility（主光線） | Radiance `raygen.glsl` | BRRenderPipeline G-Buffer pass |
| 第 2 週 | Soft shadow（面光源） | Radiance `shadow.glsl` / RTXDI 簡化版 | 舊 shadow map / CSM |
| 第 2 週 | Hard shadow（太陽/月亮） | 1 shadow ray per pixel | 舊 CSM cascade |
| 第 3 週 | Mirror reflection（水面/玻璃） | Radiance `reflection.glsl` | BRWaterRenderer 假反射 |
| 第 3 週 | Glossy reflection（金屬方塊） | 1 bounce + cosine sampling | 舊 SSR |
| 第 4 週 | 1-bounce GI（天空光） | 半球採樣 + 天空 miss shader | 舊 SSGI |

### Descriptor Set Layout（抄自 Radiance raygen.glsl）

```glsl
// raygen.glsl
layout(binding = 0, set = 0) uniform accelerationStructureEXT tlas;
layout(binding = 1, set = 0, rgba8) uniform image2D outputImage;
layout(binding = 2, set = 0) uniform Camera {
    mat4 invViewProj;
    vec3 pos;
    float fov;
} cam;
layout(binding = 3, set = 0) uniform sampler2D skyboxTex;
layout(binding = 4, set = 0) uniform LightInfo {
    vec3 sunDirection;
    vec3 sunColor;
    float sunIntensity;
} light;
```

### Denoiser 策略

**選項 A（推薦，簡單）：** Temporal accumulation + bilateral filter
- 不需要外部 SDK
- 每幀混合歷史幀（`alpha = 0.1`）
- spatial bilateral filter 消除噪點

**選項 B（品質更好）：** 整合 NRD SDK（參考 Radiance 的整合方式）
- 需要額外的 JNI 或 LWJGL binding
- Phase 3 完成後視需求決定

### Block Reality 物理視覺化整合

```glsl
// 在 closest-hit shader 中加入應力視覺化
// 從 Descriptor Set 讀取 StressBuffer (SSBO)
float stress = stressBuffer.data[primitiveID].stress;
vec3 stressColor = mix(vec3(0,1,0), vec3(1,0,0), stress); // 綠→紅
// 覆蓋在材質顏色上（半透明混合）
hitColor = mix(hitColor, stressColor, 0.4 * stress);
```

### 檢查點

```
✓ 陽光產生正確的軟陰影（無 light leak）
✓ 水面能看到玩家倒影
✓ 金屬方塊有合理的光澤反射
✓ 天空光照亮建築的背陰面
✓ 應力熱圖透過 RT any-hit 正確著色
```

---

## Phase 4 — 整合收尾（1–2 週）

### 目標

LOD + RT + 物理視覺化 + 節點編輯器全部接通，舊 pipeline 完全移除。

### 任務清單

#### 4-1. LOD mesh → BLAS 直接對接

Phase 1 的 `VoxyLODMesher` 輸出 → 直接餵進 Phase 2 的 `VkAccelStructBuilder`
不再需要中間轉換，LOD 等級自動對應 BLAS 精度。

#### 4-2. 節點編輯器 RenderConfigBinder 更新

新增 RT 相關可調參數：

```java
// 在 RenderConfigBinder.applyValue() 加入
case "rtShadowRays"     -> c.rtShadowRayCount    = toInt(value);    // 1-8
case "rtReflectionBounces" -> c.rtReflectionBounces = toInt(value); // 1-3
case "rtGIEnabled"      -> c.rtGIEnabled          = toBool(value);
case "rtGIRays"         -> c.rtGIRayCount         = toInt(value);   // 1-4
case "rtDenoiserStrength" -> c.rtDenoiserStrength  = toFloat(value); // 0.0-1.0
case "lodMaxDistance"   -> c.lodMaxChunks         = toInt(value);
```

#### 4-3. BRRenderTier Fallback 策略

```
GPU 偵測結果
├─ 支援 VK_KHR_ray_tracing_pipeline
│   ├─ VRAM >= 8GB  → Tier.RT_ULTRA（全 RT + LOD）
│   └─ VRAM < 8GB   → Tier.RT_BALANCED（RT + LOD，降低 ray count）
└─ 不支援 RT
    ├─ OpenGL 4.6   → Tier.LOD_ONLY（Voxy LOD + 舊 raster shadow）
    └─ OpenGL < 4.6 → Tier.LEGACY（舊 pipeline，最低相容）
```

#### 4-4. 移除廢棄的舊 render 檔案

```bash
# 確認 Phase 3 完成後執行
rm -rf api/src/main/java/com/blockreality/api/client/render/pipeline/
rm     api/src/main/java/com/blockreality/api/client/render/effect/BRWaterRenderer.java
rm     api/src/main/java/com/blockreality/api/client/render/effect/BRWeatherEngine.java
# ... 等確認沒有殘留引用後刪除
```

#### 4-5. 完整建置驗證

```bash
./gradlew build          # 零 error，warning 數不超過遷移前
./gradlew test           # 所有現有 JUnit 5 測試通過
./gradlew mergedJar      # 合併 JAR 能正常生成
```

---

## 8. 風險表

| 風險 | 嚴重度 | 緩解方式 |
|---|---|---|
| Forge `ChunkEvent.Load` 與 Voxy `ChunkBuilderPlugin` 語意差異 | **HIGH** | Phase 1 開始前先做 PoC，確認能拿到完整 mesh 資料 |
| LWJGL Vulkan runtime natives 在 Forge 環境缺失 | **HIGH** | Phase 0 立即驗證，用 `VK10.vkEnumerateInstanceVersion()` 測試 |
| SBT alignment 錯誤（RT 最常見的崩潰點） | **HIGH** | 直接沿用 Sascha Willems 的 alignment 計算公式，不自己算 |
| 玩家 GPU 沒有 RT 支援（GTX 1080 以下） | **MEDIUM** | Tier fallback 路徑（LOD_ONLY / LEGACY）必須完整可用 |
| Radiance GLSL shader 使用了 SPIR-V 特有 extension | **MEDIUM** | 逐一檢查 `GL_EXT_ray_tracing` vs `GL_NV_ray_tracing` 差異，移除 NV 專屬語法 |
| Vulkan 與 OpenGL 上下文共存問題（Minecraft 用 GL） | **MEDIUM** | 用 `VK_KHR_external_memory` + `GL_EXT_semaphore` 做資源共享，或完全接管 swapchain |
| Phase 2-3 期間舊 pipeline 殘留導致渲染衝突 | **LOW** | 用 `BRRenderTier` 的 flag 切換，同一幀只有一個 pipeline 輸出 |
| LOD mesh 更新頻率過高導致 BLAS rebuild 卡頓 | **LOW** | 限制每幀最多 rebuild N 個 BLAS，其餘排隊到下幀 |

---

## 9. 時間估計總覽

| 階段 | 時間 | 最低可交付物 | 前置條件 |
|---|---|---|---|
| **Phase 0** | 3–5 天 | Vulkan 初始化不崩，新套件骨架建立 | — |
| **Phase 1** | 2–3 週 | LOD 地形可見，幀率 +30% | Phase 0 完成 |
| **Phase 2** | 3–4 週 | 螢幕有 RT 輸出（白色/天空藍） | Phase 0 完成 |
| **Phase 3** | 3–4 週 | 軟陰影 + 反射正確 | Phase 2 完成 |
| **Phase 4** | 1–2 週 | 全系統整合，舊 pipeline 移除 | Phase 1 + Phase 3 完成 |
| **合計** | **~10–14 週** | | |

> **Phase 0、Phase 1、Phase 2 可以並行：**
> LOD 開發和 Vulkan 初始化彼此獨立，不同的人可以同時進行。
> Phase 3 必須等 Phase 2 的 RT pipeline 跑通後才能開始。

---

## 10. 新套件結構速查

```
Block Reality/
├─ api/
│   └─ src/main/java/com/blockreality/api/
│       └─ client/
│           ├─ rendering/                    ← 全新（取代舊 render/）
│           │   ├─ lod/
│           │   │   ├─ VoxyLODMesher.java   ← 抄 Voxy LodBuilder
│           │   │   ├─ LODChunkManager.java
│           │   │   ├─ LODTerrainBuffer.java
│           │   │   └─ LODRenderDispatcher.java
│           │   ├─ vulkan/
│           │   │   ├─ VkContext.java        ← 翻譯自 Radiance MCVR
│           │   │   ├─ VkAccelStructBuilder.java
│           │   │   ├─ VkRTPipeline.java
│           │   │   ├─ VkRTShaderPack.java
│           │   │   ├─ VkMemoryAllocator.java
│           │   │   └─ VkSwapchain.java
│           │   ├─ bridge/
│           │   │   ├─ ChunkRenderBridge.java
│           │   │   └─ ForgeRenderEventBridge.java
│           │   └─ physics/
│           │       └─ StressVisualizationRT.java
│           └─ render/                       ← 舊套件（Phase 4 後移除）
│               └─ (全部標 @Deprecated)
└─ fastdesign/
    └─ (節點編輯器只需更新 RenderConfigBinder，其餘不動)
```

---

## 11. 廢棄 vs 保留檔案清單

### 🗑️ Phase 4 移除（舊 OpenGL pipeline）

```
render/pipeline/BRRenderPipeline.java
render/pipeline/BRFramebufferManager.java
render/pipeline/RenderPass.java
render/pipeline/RenderPassContext.java
render/effect/BRWaterRenderer.java
render/effect/BRWeatherEngine.java
render/effect/BRCloudRenderer.java
render/effect/BRAtmosphereEngine.java
render/effect/BRAuroraRenderer.java
render/effect/BRFogEngine.java
render/optimization/BRLODEngine.java
render/optimization/BRMeshShaderPath.java
```

### ✅ 永久保留

```
render/BRRenderConfig.java             ← 設定，擴充 RT 參數
render/BRRenderTier.java               ← 擴充 RT tier 偵測
render/BRRenderSettings.java           ← 保留
render/animation/BRAnimationEngine.java
render/animation/AnimationController.java
render/optimization/GreedyMesher.java  ← Voxy LOD 複用
render/optimization/BRSparseVoxelDAG.java ← Voxy 體素存儲
render/optimization/BRGPUProfiler.java
render/optimization/MeshCache.java
client/ClientStressCache.java
client/StressHeatmapRenderer.java      ← 接入 RT，不移除
client/AnchorPathRenderer.java
```

### 🟡 視情況保留

```
render/effect/BRParticleSystem.java    ← 可先保留，Phase 3 後決定
render/effect/BRLensFlare.java         ← 可用 RT lens flare 替換
render/optimization/BRGPUCulling.java  ← RT 不需要傳統 culling，但可保留作 fallback
```

---

*最後更新：2026-03-30 | Block Reality 開發組*
