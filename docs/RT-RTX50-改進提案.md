# Block Reality Vulkan 光線追蹤改進提案

## RTX 50 全路徑追蹤 + RTX 40 混合 RT 後備

---

## Context

Block Reality 現有 Vulkan RT 管線（Tier 3, RTX 3060+）已具備基礎陰影/反射/AO/GI 光線追蹤能力，但使用傳統 per-section BLAS、簡易 SVGF 降噪、無 DLSS 整合。本提案將主要目標提升至 **RTX 50 系（Blackwell）全路徑追蹤**，RTX 40 系（Ada）降為混合 RT 後備，充分利用新硬體的 Cluster BVH、ReSTIR、NRD、DLSS 4 等能力。

---

## 第一章：現狀分析

### 1.1 Vulkan RT 管線現況

位於 `api/client/render/rt/` 套件：

| 類別 | 大小 | 職責 |
|------|------|------|
| `BRVulkanRT` | 42.2KB | RT 管線主類別，內嵌 raygen/closesthit/miss GLSL，SBT 組裝 |
| `BRVulkanBVH` | 22.9KB | TLAS + N × Section BLAS（16×16×16），MAX_SECTIONS=4096，每幀重建上限 8 |
| `BRVulkanDevice` | 37.6KB | Vulkan 1.2 初始化，偵測 RT/ray_query/external_memory 擴展 |
| `BRVulkanInterop` | 11.3KB | GL/VK 零拷貝 interop（RGBA16F），fallback CPU readback |
| `BRSVGFDenoiser` | 19.5KB | 3-pass GL compute 降噪：temporal 10%/90% + variance + a-trous 5 iterations |
| `BRRTSettings` | 設定 | singleton，volatile 欄位：maxBounces, aoRadius, denoiserAlgo |

**關鍵常數**：
- `MAX_RT_RECURSION_DEPTH = 2`
- `SBT_HANDLE_SIZE = 32`, `SBT_HANDLE_ALIGNMENT = 64`
- `SCRATCH_BUFFER_SIZE = 16MB`
- Specialization Constants: `SC_0` = GPU_TIER (0/1/2), `SC_1` = AO_SAMPLES / MAX_BOUNCES

**RT 效果**：SHADOWS (~0.5ms), REFLECTIONS (~1.0ms), AO (~0.3ms), GI (~3.0ms)

### 1.2 Ada/Blackwell 擴展骨架

位於 `api/client/rendering/vulkan/` 套件（較新的遷移目標）：

| 類別 | 完成度 | 狀態 |
|------|--------|------|
| `BRAdaRTConfig` | 80% | Ada SM8.9 / Blackwell SM10 偵測，SER/OMM/ClusterAS 旗標 |
| `VkAccelStructBuilder` | 60% | LOD-aware BLAS，Blackwell Cluster AS `CLUSTER_SIZE=4` 已定義但未實作 |
| `VkRTPipeline` | 50% | Ada/Blackwell RT 橋接，DAG SSBO 節流上傳 |
| `BRNRDDenoiser` | 30% | NRD ReLAX/ReBLUR 骨架，回退至 SVGF |
| `VkGBuffer` | 70% | GL FBO GBuffer：Albedo/Normal/Material/Motion/Depth |

### 1.3 Shader 管線

**基礎 RT（`BRVulkanRT.java` 內嵌）**：
- raygen: GBuffer 取樣 → shadow ray (SER) → reflection (GGX importance sampling) → SVDAG far-field
- closesthit: PBR BRDF (D_GGX/G_Smith/F_Schlick) + 應力熱圖 + `u_Lights` LightBuffer (binding 11) ReSTIR DI 骨架
- miss: Rayleigh+Mie 大氣散射

**Ada 專用（`shaders/rt/ada/`）**：
- `primary.rgen.glsl`: SER (`GL_NV_shader_execution_reordering`), ray_query RTAO, blue noise, DAG SSBO
- `material.rchit.glsl`, `transparent.rahit.glsl`, `shadow.rmiss.glsl`, `sky.rmiss.glsl`
- `rtao.comp.glsl`: 8/16 samples (SC), shared memory bilateral blur

### 1.4 GBuffer 配置

```
g_Depth    : D24S8 或 D32F
g_Normal   : RG16（octahedron）或 RGBA16F
g_Albedo   : RGBA8（RGB=albedo, A=AO）
g_Material : RGBA8（R=roughness, G=metallic, B=matId, A=lodLevel）
g_Motion   : RG16F（screen-space velocity）
```

### 1.5 相關子系統

- **BRMeshletEngine**: Nanite-inspired, 128 tri/meshlet, 5 LOD levels
- **BRSparseVoxelDAG**: 遠場 GI（128+ chunk），DAG SSBO
- **BRMeshShaderPath**: Mesh shader rendering (NV/AMD)
- **BRAsyncComputeScheduler**: async compute dispatch
- **GreedyMesher**: 256× geometry reduction，BLAS 幾何來源
- **57+ 渲染節點**: 含 `VulkanRTConfigNode` 控制 RT 設定

---

## 第二章：目標架構

### 2.1 硬體層級重新定義

| 層級 | GPU | 核心特性 | 渲染路徑 |
|------|-----|---------|---------|
| **TIER_BLACKWELL** | RTX 5070+ | 4th gen RT Core, Cluster Intersection, DLSS 4 MFG | 全路徑追蹤 |
| **TIER_ADA** | RTX 4060+ | 3rd gen RT Core, SER, OMM, DLSS 3 FG | 混合 RT |
| **TIER_LEGACY** | RTX 3060+ | 2nd gen RT Core | 現有管線不變 |

### 2.2 RTX 50 全路徑追蹤管線

```
GBuffer (Mesh Shader)
    ↓
Mega Geometry Cluster BVH ← VK_NV_cluster_acceleration_structure
    ↓
ReSTIR DI（直接光照重取樣）← BRRTEmissiveManager Light Tree
    ↓
ReSTIR GI（4-8 間接光線/像素）
    ↓
NRD ReLAX 降噪（漫反射 + 鏡面分離）
    ↓
DLSS SR（升頻）→ DLSS 4 MFG（1→4 幀）→ Flip Metering
    ↓
最終輸出
```

### 2.3 RTX 40 混合 RT 後備管線

```
GBuffer (Mesh Shader)
    ↓
Standard TLAS/BLAS + Opacity Micromap
    ↓
RT Shadow + RT AO（ray query）
    ↓
DDGI Probe（低解析度 GI）
    ↓
NRD ReBLUR 降噪
    ↓
DLSS SR → DLSS 3 FG（1→2 幀）
    ↓
最終輸出
```

### 2.4 Tier 偵測擴充

修改 `BRRenderTier.java` + `BRAdaRTConfig.java`：

```
VK_NV_cluster_acceleration_structure + SM 10.x → TIER_BLACKWELL
  └ 啟用：Cluster BVH, ReSTIR DI/GI, NRD ReLAX, DLSS 4 MFG
VK_EXT_opacity_micromap + SER + SM 8.9         → TIER_ADA
  └ 啟用：Standard BVH + OMM, RT Shadow/AO, DDGI, NRD ReBLUR, DLSS 3 FG
VK_KHR_ray_tracing_pipeline                    → TIER_LEGACY_RT
  └ 維持現有管線
無 Vulkan RT                                    → TIER_RASTER
```

---

## 第三章：分階段實施計劃

### Phase 0 — 基礎設施升級（1 週）

**0-1. Vulkan 擴展偵測擴充**
- 修改：`api/.../rt/BRVulkanDevice.java`
- 新增偵測：`VK_NV_cluster_acceleration_structure`, `VK_NV_cooperative_vector`, `VK_NV_displacement_micromap`
- 新增 DLSS 4 Vulkan 擴展偵測

**0-2. BRAdaRTConfig 升級三層 Tier**
- 修改：`api/.../rendering/vulkan/BRAdaRTConfig.java`
- 新增 `TIER_BLACKWELL_FULL = 2`
- 新增旗標：`hasTriangleCluster`, `hasMegaGeometry`, `hasDLSS4`

**0-3. BRRTSettings 擴充**
- 修改：`api/.../rt/BRRTSettings.java`
- 新增欄位：`restirDIEnabled`, `restirGISamples`, `ddgiProbeEnabled`, `dlssMode`, `frameGenMode`, `clusterBVHEnabled`

**0-4. build.gradle 依賴**
- 確認 LWJGL 版本支援 Cluster AS 綁定（可能需 3.3.3+）

### Phase 1 — Mega Geometry / Cluster BVH（3 週）

**1-1. 新建 `BRClusterBVH.java`**
- 位置：`api/client/rendering/vulkan/`
- 使用 `VK_NV_cluster_acceleration_structure` 將相鄰 section 打包為 cluster
- 現有 `VkAccelStructBuilder.CLUSTER_SIZE=4` 已預留

**1-2. 修改 `VkAccelStructBuilder`**
- 實作 `rebuildClusterBLAS(long clusterKey)` — 4×4=16 section 幾何打包
- `MAX_BLAS_REBUILDS_PER_FRAME` 從 8 提升至 32
- scratch buffer 從 16MB 提升至 64MB

**1-3. Blackwell Shader 套件**
- 新建目錄：`shaders/rt/blackwell/`
- `cluster_raygen.rgen.glsl`: Cluster AS traversal
- 修改 hit shader 支援 cluster 內 primitiveID 重映射

**1-4. Opacity Micromap（Ada+Blackwell）**
- 新建：`BROpacityMicromap.java`
- 為 alpha-test 幾何（樹葉/柵欄/玻璃/水）建立 OMM
- BLAS 建構時附加 `VkAccelerationStructureTrianglesOpacityMicromapEXT`
- 預期 alpha-test RT 成本 -39%

### Phase 2 — ReSTIR DI 直接光照（2 週）

**2-1. BRRTEmissiveManager 升級**
- 修改：`api/.../rt/BRRTEmissiveManager.java`
- 現有 `LightNode` 已有 position/color/intensity/radius
- 新增 BVH Light Tree 建構（`buildLightBVH()` → GPU SSBO）

**2-2. ReSTIR DI Shader**
- 新建：`shaders/rt/blackwell/restir_di.comp.glsl`
- 候選光源生成 → 可見性測試 → 時空重取樣 → 直接光照 radiance
- 與現有 `u_Lights` LightBuffer (binding 11) 整合

**2-3. ReSTIR DI 管理器**
- 新建：`api/.../vulkan/BRReSTIRDI.java`
- 管理 reservoir buffer（雙緩衝）

### Phase 3 — ReSTIR GI 間接光照（3 週）

**3-1. ReSTIR GI Compute Shader**
- 新建：`shaders/rt/blackwell/restir_gi.comp.glsl`
- 4-8 indirect rays/pixel，多彈射路徑追蹤
- 時空重取樣壓低方差

**3-2. Java 管理層**
- 新建：`api/.../vulkan/BRReSTIRGI.java`
- 近場 ReSTIR GI + 遠場 SVDAG 配合

**3-3. SVDAG 遠場升級**
- 修改：`api/.../optimization/BRSparseVoxelDAG.java`
- 新增 `serializeForReSTIR()` 方法

### Phase 4 — DDGI Probe System（RTX 40 GI，2 週）

**4-1. DDGI 管理器**
- 新建：`api/.../vulkan/BRDDGIProbeSystem.java`
- Probe 間距 8 blocks，irradiance 8×8 + visibility 16×16（octahedral）
- 每幀距離優先 + 隨機子集更新

**4-2. DDGI Shader**
- 新建：`shaders/rt/ada/ddgi_update.comp.glsl` — probe 更新
- 新建：`shaders/rt/ada/ddgi_sample.glsl` — GI 取樣 include
- 使用 `VK_KHR_ray_query` 從 probe 射出 64 rays

**4-3. Ada 管線整合**
- 修改 `VkRTPipeline`：TIER_ADA GI 路徑為 DDGI
- NRD 在 Ada 使用 ReBLUR

### Phase 5 — NRD 降噪升級（2 週）

**5-1. BRNRDNative 完整整合**
- 現有 JNI wrapper 骨架已在 `BRNRDNative.java`
- 建構原生 `blockreality_nrd.dll/so` 封裝 NRD SDK

**5-2. BRNRDDenoiser 完整實作**
- 現有骨架已有 Algorithm enum
- Blackwell: ReLAX（漫反射+鏡面分離）+ SIGMA Shadow
- Ada: ReBLUR（AO + DDGI）
- Legacy: 保留 SVGF 回退

**5-3. 降噪管線**
```
ReSTIR DI → SIGMA Shadow
ReSTIR GI → ReLAX Diffuse + ReLAX Specular
RTAO      → ReBLUR
```

### Phase 6 — DLSS 4 整合（2 週）

**6-1. DLSS 4 MFG**
- 新建：`api/.../vulkan/BRDLSS4Manager.java`
- Blackwell: 1 rendered → 4 displayed
- Streamline SDK JNI 封裝

**6-2. DLSS 3 FG 後備**
- Ada: 1 → 2 幀
- 共用 motion vector + depth 輸入

**6-3. DLSS SR 共存**
- 內部 1080p → DLSS SR 4K → MFG 4×4K

### Phase 7 — 節點系統擴充（1 週）

新增節點（`fastdesign/client/node/impl/render/pipeline/`）：

| 節點 | 職責 |
|------|------|
| `ReSTIRConfigNode` | ReSTIR DI/GI 參數控制 |
| `DDGIConfigNode` | DDGI probe 密度/更新頻率 |
| `DLSSConfigNode` | DLSS 模式/品質 |
| `MegaGeometryNode` | Cluster BVH 設定 |
| `OMMConfigNode` | Opacity Micromap 啟停 |
| `NRDConfigNode` | NRD 演算法選擇 |

擴充 `VulkanRTConfigNode` + `RenderConfigBinder` 映射。

### Phase 8 — 整合與 Shader 重構（2 週）

**渲染 Pass 順序**：

RTX 50:
```
GBUFFER → CLUSTER_BVH_UPDATE → RESTIR_DI → RESTIR_GI → NRD → DLSS_SR → DLSS_MFG → TONEMAP → UI
```

RTX 40:
```
GBUFFER → BLAS_TLAS_UPDATE → RT_SHADOW_AO → DDGI_UPDATE → DDGI_SAMPLE → NRD → DLSS_SR → DLSS_FG → TONEMAP → UI
```

---

## 第四章：GBuffer 升級

### 4.1 現有不足

- Roughness/Metallic 精度：RGBA8 僅 8-bit，NRD 建議 16-bit
- 缺少 world-space position buffer：每次從 depth 重建增加 ALU 負擔
- 缺少 material emission channel：ReSTIR DI 需知表面自發光

### 4.2 升級方案

修改：`api/.../rendering/vulkan/VkGBuffer.java`

```
Attachment 0: g_Albedo    — RGBA8   （不變）
Attachment 1: g_Normal    — RG16F   （純 octahedron）
Attachment 2: g_Material  — RGBA16F （R=roughness, G=metallic, B=matId, A=emission）←升級
Attachment 3: g_Motion    — RG16F   （不變）
Attachment 4: g_WorldPos  — RGBA32F （新增：world XYZ + linearDepth）
Depth:        g_Depth     — D32F    （升級）
```

**VRAM 影響**（1080p）：
- 新增 g_WorldPos RGBA32F: +33MB
- g_Material 升至 RGBA16F: +4MB
- 總增量: ~37MB（可接受，RTX 40+ 至少 8GB VRAM）

---

## 第五章：BVH / 加速結構升級

### 5.1 現有限制

- `BRVulkanBVH`: MAX_SECTIONS=4096，每幀重建上限 8
- TLAS 完整重建（非 update 模式）
- 無 cluster 概念，每 section 獨立 BLAS

### 5.2 Blackwell Cluster BVH

```
現有：TLAS → 4096 × Section BLAS
升級：TLAS → ~256 × Cluster BLAS (4×4 sections each)
             └── Triangle Cluster Intersection Engine（硬體加速）
```

關鍵變更：
1. `VkAccelStructBuilder.CLUSTER_SIZE=4` 已定義 → 實作 `buildClusterBLAS()`
2. TLAS instance 數量 4096 → ~256（16× 縮減）
3. TLAS 改用 update 模式（非完整重建）
4. scratch buffer 16MB → 64MB

### 5.3 Ada OMM

- alpha-test 幾何使用 2-bit per micro-triangle（transparent/opaque/unknown）
- 完全省去 any-hit shader（`transparent.rahit.glsl`）的 overhead
- 建構時機：BLAS build 附加 OMM
- 需要 OMM atlas 管理（per-block-type）

---

## 第六章：ReSTIR DI / GI 設計

### 6.1 ReSTIR DI

**與現有架構接合**：
- `BRVulkanRT.java` closesthit 已有 `u_Lights` LightBuffer (binding 11)
- `BRRTEmissiveManager` 已有 `addEmissiveBlock()` / `getLightCount()`
- 需將 flat array → BVH Light Tree

**Reservoir 結構**（每像素）：
```glsl
struct Reservoir {
    vec3  lightSample;     // 選中光源位置
    float weightSum;       // 累積權重
    float targetPdf;       // 目標 PDF
    uint  M;               // 候選數量
    uint  age;             // 時域重用年齡
};
```

**流程**：
1. Initial Candidates: 從 Light BVH 取樣 32 候選
2. Temporal Resampling: 重用前幀 reservoir
3. Spatial Resampling: 鄰域 5×5 reservoir 共享
4. Visibility: shadow ray 驗證最終候選
5. 輸出直接光照 radiance

### 6.2 ReSTIR GI（僅 Blackwell）

- 每像素 4-8 indirect rays
- Cluster BVH 加速 secondary ray intersection
- 近場（<128 chunks）: ReSTIR GI trace TLAS
- 遠場（128+ chunks）: SVDAG soft trace（升級現有 `svdagAmbient()`）

---

## 第七章：DDGI Probe System（RTX 40 後備 GI）

### 7.1 設計

- Probe 網格：世界空間每 8 blocks 一個
- 每 probe 儲存：irradiance 8×8 + visibility 16×16（octahedral map）
- 每 probe 每幀射出 64 rays（`VK_KHR_ray_query` compute shader）
- 更新策略：距離優先 + 隨機子集

### 7.2 與現有系統關係

- 取代 `VCT_GINode`（體素錐追蹤）和 `SSGINode`（螢幕空間 GI）
- `BRSparseVoxelDAG` 仍用於遠場（probe 不覆蓋 128+ chunk）
- DDGI 結果噪聲低，使用 ReBLUR 而非 ReLAX

---

## 第八章：DLSS 整合設計

### 8.1 DLSS 4 Multi Frame Generation（Blackwell）

- 1 rendered frame → 4 displayed frames
- 需要 Streamline SDK JNI 封裝
- 輸入：g_Motion (RG16F) + g_Depth (D32F) + 最終渲染
- Flip Metering 確保均勻呈現步調

### 8.2 DLSS 3 Frame Generation（Ada）

- 1 → 2 幀
- 共用 motion vector 和 depth 管線

### 8.3 渲染解析度流程

```
內部渲染 1080p → DLSS SR 升頻 4K → DLSS MFG 生成 4×4K 輸出
```

### 8.4 GL/VK 呈現挑戰

- 現有透過 GL/VK interop 最終呈現為 GL texture
- DLSS MFG 需要接管 swapchain presentation
- Phase 8 評估完全 VK 呈現可行性

---

## 第九章：具體檔案變更清單

### 修改現有檔案

| 檔案 | 變更內容 |
|------|---------|
| `api/.../rt/BRVulkanDevice.java` | 新增 Blackwell 擴展偵測（Cluster AS, Coop Vector, DLSS 4） |
| `api/.../rt/BRVulkanBVH.java` | MAX_BLAS_REBUILDS 8→32, scratch buffer 16→64MB |
| `api/.../rt/BRRTSettings.java` | 新增 ReSTIR/DDGI/DLSS/Cluster 設定欄位 |
| `api/.../rt/BRRTEmissiveManager.java` | 新增 Light BVH 建構 `buildLightBVH()` |
| `api/.../rt/BRSVGFDenoiser.java` | **不修改**（保留為 fallback） |
| `api/.../rendering/vulkan/BRAdaRTConfig.java` | 三層 Tier 偵測，Blackwell 旗標 |
| `api/.../rendering/vulkan/VkAccelStructBuilder.java` | 實作 cluster BLAS (`rebuildClusterBLAS`) |
| `api/.../rendering/vulkan/VkRTPipeline.java` | 三條管線路徑分派 |
| `api/.../rendering/vulkan/BRNRDDenoiser.java` | 完整 NRD 整合（ReLAX/ReBLUR/SVGF） |
| `api/.../rendering/vulkan/VkGBuffer.java` | 新增 worldPos, Material 升至 RGBA16F |
| `api/.../rendering/vulkan/VkRTAO.java` | AO_SAMPLES 參數化 |
| `api/.../pipeline/BRRenderTier.java` | 新增 TIER_BLACKWELL, TIER_ADA |
| `api/.../optimization/BRSparseVoxelDAG.java` | 新增 `serializeForReSTIR()` |
| `fastdesign/.../VulkanRTConfigNode.java` | 新增 ReSTIR/DLSS/cluster 端口 |
| `fastdesign/.../RenderConfigBinder.java` | 新增映射分支 |
| `shaders/rt/ada/primary.rgen.glsl` | 新增 DDGI 取樣路徑 |

### 新建檔案

| 檔案 | 職責 |
|------|------|
| **Java — `api/.../rendering/vulkan/`** | |
| `BRClusterBVH.java` | Mega Geometry cluster BVH 管理 |
| `BROpacityMicromap.java` | OMM 建構與管理 |
| `BRReSTIRDI.java` | ReSTIR DI reservoir 管理 |
| `BRReSTIRGI.java` | ReSTIR GI reservoir 管理 |
| `BRDDGIProbeSystem.java` | DDGI probe 網格管理 |
| `BRDLSS4Manager.java` | DLSS 4 MFG / DLSS 3 FG |
| **Shader — `shaders/rt/blackwell/`** | |
| `primary.rgen.glsl` | Blackwell raygen (Cluster AS + ReSTIR) |
| `material.rchit.glsl` | 材料命中 PBR |
| `transparent.rahit.glsl` | OMM alpha |
| `shadow.rmiss.glsl` / `sky.rmiss.glsl` | miss shaders |
| `restir_di.comp.glsl` | ReSTIR DI compute |
| `restir_gi.comp.glsl` | ReSTIR GI compute |
| `nrd_pack.comp.glsl` | NRD 輸入打包 |
| **Shader — `shaders/rt/ada/`** | |
| `ddgi_update.comp.glsl` | DDGI probe 更新 |
| `ddgi_sample.glsl` | DDGI 取樣 include |
| **節點 — `fastdesign/.../render/pipeline/`** | |
| `ReSTIRConfigNode.java` | ReSTIR DI/GI 參數 |
| `DDGIConfigNode.java` | DDGI probe 設定 |
| `DLSSConfigNode.java` | DLSS 模式選擇 |
| `MegaGeometryNode.java` | Cluster BVH 設定 |
| `OMMConfigNode.java` | OMM 啟停 |
| `NRDConfigNode.java` | NRD 演算法選擇 |

---

## 第十章：效能目標

### RTX 5070（1080p 內部 → DLSS SR 4K → MFG 4×）

| 階段 | 預算 |
|------|------|
| GBuffer（Mesh Shader） | 1.0 ms |
| Cluster BVH 更新 | 0.3 ms |
| ReSTIR DI | 1.0 ms |
| ReSTIR GI（4 rays/px） | 2.0 ms |
| NRD ReLAX | 1.0 ms |
| 合成 + 後製 | 0.5 ms |
| DLSS SR | 0.5 ms |
| **渲染總計** | **6.3 ms → ~159 FPS rendered** |
| DLSS 4 MFG 4× | → **~636 FPS displayed** |

### RTX 4060（1080p 內部 → DLSS SR 4K → FG 2×）

| 階段 | 預算 |
|------|------|
| GBuffer（Mesh Shader） | 1.5 ms |
| BLAS/TLAS + OMM 更新 | 0.8 ms |
| RT Shadow + RT AO | 1.5 ms |
| DDGI Probe 更新 + 取樣 | 2.0 ms |
| NRD ReBLUR | 1.5 ms |
| 合成 + 後製 | 1.0 ms |
| DLSS SR | 0.8 ms |
| **渲染總計** | **9.1 ms → ~110 FPS rendered** |
| DLSS 3 FG 2× | → **~220 FPS displayed** |

### 場景基準

| 場景 | RTX 5070 目標 | RTX 4060 目標 |
|------|-------------|-------------|
| 平原（低複雜度） | >200 FPS | >140 FPS |
| 城市建築（高複雜度） | >120 FPS | >80 FPS |
| 水面+玻璃（alpha heavy） | >100 FPS | >70 FPS |
| 512 chunk 視距 | >80 FPS | >60 FPS |

---

## 第十一章：測試策略

### 11.1 單元測試

| 測試類別 | 驗證 |
|---------|------|
| `BRClusterBVHTest` | Cluster key 計算、4×4 section 打包 |
| `BROpacityMicromapTest` | OMM 資料生成正確性 |
| `BRReSTIRDITest` | Reservoir 更新數學 |
| `BRDDGIProbeSystemTest` | Probe 網格索引、octahedral 映射 |
| `BRRTSettingsTest` | volatile 欄位讀寫一致性 |
| `VulkanRTConfigNodeTest` | 節點 evaluate → BRRTSettings 映射 |

### 11.2 GPU 整合測試

- Vulkan validation layer 零 error
- BLAS/TLAS 建構不 crash（SBT alignment）
- NRD JNI 載入失敗 → 正確回退 SVGF
- DLSS SDK 不可用 → 正確禁用 frame gen
- 所有三個 Tier 路徑可獨立運行

### 11.3 回歸測試

- `./gradlew test` 全數通過
- 物理系統不受影響（渲染變更不觸及 `physics/`）
- 節點圖序列化/反序列化不因新端口中斷
- RTX 30 系列現有管線行為不變

---

## 第十二章：時程估計

| Phase | 內容 | 時間 | 前置 | 可並行 |
|-------|------|------|------|--------|
| 0 | 基礎設施 | 1 週 | — | — |
| 1 | Cluster BVH + OMM | 3 週 | Ph0 | Ph2, Ph4 |
| 2 | ReSTIR DI | 2 週 | Ph0 | Ph1, Ph4 |
| 3 | ReSTIR GI | 3 週 | Ph1+2 | — |
| 4 | DDGI（Ada） | 2 週 | Ph0 | Ph1, Ph2 |
| 5 | NRD 降噪 | 2 週 | Ph2+3 | Ph4 |
| 6 | DLSS 4 | 2 週 | Ph5 | — |
| 7 | 節點系統 | 1 週 | Ph1-6 | — |
| 8 | 整合 + Shader | 2 週 | 全部 | — |
| **總計** | | **~14-18 週** | | |

```
週次  1  2  3  4  5  6  7  8  9  10 11 12 13 14 15 16 17 18
Ph0   ██
Ph1      ██████████
Ph2      ████████
Ph4      ████████
Ph3                  ██████████
Ph5                  ████████
Ph6                           ████████
Ph7                                    ████
Ph8                                    ████████
```

---

## 第十三章：風險與緩解

| 風險 | 嚴重度 | 緩解策略 |
|------|--------|---------|
| `VK_NV_cluster_acceleration_structure` LWJGL 綁定未完整 | **高** | Phase 0 驗證，備案：手動 JNI 綁定 |
| DLSS 4 SDK Java 封裝不存在 | **高** | Streamline SDK + JNI bridge；備案：純 DLSS SR 不含 MFG |
| NRD JNI 原生庫跨平台編譯複雜 | **中** | SVGF 保留 fallback，NRD 為可選增強 |
| ReSTIR GI reservoir buffer 記憶體高 | **中** | VRAM < 8GB 時降至半解析度 |
| Cluster BVH 與 `BRVulkanBVH` 整合衝突 | **中** | 雙路徑：Legacy/Ada 用原 BVH，Blackwell 用 Cluster |
| Forge 1.20.1 GL context 與 VK swapchain 共存 | **高** | 維持 GL/VK interop 至 Ph6；Ph8 評估全 VK 呈現 |
| DDGI probe 更新延遲造成 GI 閃爍 | **低** | 時域穩定化 + hysteresis 過濾 |

---

## 驗證方式

1. **Phase 0 完成後**：`./gradlew :api:jar` 編譯通過，`BRAdaRTConfig` 在 RTX 40/50 正確偵測 Tier
2. **Phase 1 完成後**：Cluster BVH 建構無 Vulkan validation error，`BRClusterBVHTest` 通過
3. **Phase 2+3 完成後**：ReSTIR DI/GI 數學正確性測試通過，視覺無明顯噪點
4. **Phase 5 完成後**：NRD 降噪輸出品質 > SVGF，fallback 路徑正常
5. **Phase 8 完成後**：三條管線路徑（Blackwell/Ada/Legacy）各自獨立運行無 crash
6. **全部完成**：效能達到第十章基準，`./gradlew test` 全數通過
