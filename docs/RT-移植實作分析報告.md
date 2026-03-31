# Block Reality RT 移植實作分析報告

> 分析 iterationRP / Radiances / Voxy 技術做法，移植重寫進 Block Reality 自有架構
> 目標：RTX 30 以上、低相容高性能、不兼容其他模組

---

## 第一章：現有架構分析

### 1.1 Block Reality 當前 Vulkan RT 架構

Block Reality 已實作混合式 GL+Vulkan 光線追蹤系統，位於 `api/client/render/rt/`：

| 類別 | 職責 |
|------|------|
| `BRVulkanDevice` | Vulkan 實例/裝置管理、擴展偵測、命令池 |
| `BRVulkanRT` | RT 管線建立、SBT 管理、`vkCmdTraceRaysKHR` 調度 |
| `BRVulkanBVH` | BLAS/TLAS 加速結構管理、增量更新 |
| `BRVulkanInterop` | GL↔VK 紋理共享（零拷貝 / CPU 回讀後備） |
| `BRSVGFDenoiser` | 時空變異引導濾波（時序累積 + 5 次 A-trous 小波） |

#### 當前渲染流程
```
OpenGL G-Buffer 生成
    ↓
Vulkan RT Pass（可選，Tier 3）
    ├── 更新髒 BLAS（每幀最多 8 個）
    ├── 重建 TLAS
    ├── vkCmdTraceRaysKHR（1 SPP）
    └── SVGF 降噪
    ↓
GL/VK 互操作（零拷貝或回讀）
    ↓
OpenGL 合成鏈（消費 RT 結果）
```

#### 當前效能預算

| 效果 | 預設 | 成本（1080p） |
|------|------|-------------|
| RT 陰影 | 開啟 | 0.5 ms |
| RT 反射 | 關閉 | 1.0 ms |
| RT AO | 關閉 | 0.3 ms |
| RT GI | 關閉 | 3.0 ms |
| SVGF 降噪 | 隨 RT | 0.5-1.5 ms |

#### 當前限制
- **最大遞迴深度：1 次彈射**（僅陰影光線）
- **每幀 BLAS 重建上限：8 個** section
- **GL/VK 互操作**增加同步開銷
- **VCT 體素解析度**僅 64³，限制遠距 GI 品質
- **無 LOD 感知**的加速結構

### 1.2 現有支撐系統

| 系統 | 位置 | RT 相關用途 |
|------|------|------------|
| `GreedyMesher` | `render/optimization/` | 產出 AABB 幾何供 BLAS 建構 |
| `BRSparseVoxelDAG` | `render/optimization/` | 稀疏體素 DAG，5-10x 壓縮 |
| `BRLODEngine` | `render/optimization/` | LOD 幾何過渡 |
| `BRCascadedShadowMap` | `render/shadow/` | 4 級聯陰影（可被 RT 取代） |
| `BRGlobalIllumination` | `render/postfx/` | SSGI + VCT（64³ 體素錐追蹤） |
| `SparseVoxelOctree` | `physics/sparse/` | 16³ section 物理體素（非 RT 用） |

---

## 第二章：外部技術研究

### 2.1 Continuum RT（iterationRP 系技術）

Continuum RT 是 Minecraft 首個完全路徑追蹤著色器，運行於其自研 **Focal Engine**。

#### 關鍵技術點

| 技術 | 做法 | 對 Block Reality 的意義 |
|------|------|----------------------|
| **體素化** | GPU 計算著色器直接從遊戲世界幾何建立體素網格 | 可取代 CPU 端 GreedyMesher → GPU 體素化管線 |
| **層級體素追蹤** | 1×1×1 m 體素表示，層級結構加速遍歷 | 與 BRSparseVoxelDAG 理念一致 |
| **降噪** | A-SVGF（自適應時空梯度）→ 計畫遷移至 NRD ReLAX | 我們應直接採用 NRD，跳過 A-SVGF |
| **取樣** | 1 SPP + 降噪重建 | 與現有設計一致 |
| **反射重投影** | 視訊壓縮啟發式技術處理反射/折射穩定性 | 可移植至水面/玻璃反射 |

#### 核心啟發
- **計算驅動體素化**：著色器在 GPU 上直接建構體素，避免 CPU 往返
- **NRD 降噪收斂**：Continuum 和 Radiances 都在向 NRD 遷移，這是產業方向
- **POM 與梯度估計衝突**：A-SVGF 的時序梯度在視差映射下會閃爍，這驗證了我們跳過 A-SVGF 直接用 NRD 的決策

### 2.2 Radiances（完整 Vulkan C++ 替換渲染器）

Radiances 完全取代 Minecraft 的 OpenGL 渲染器為原生 Vulkan C++ 後端。

#### 架構設計

```
Java 模組（Radiance）
    ↓ JNI 橋接
C++ 渲染器（MCVR）
    ├── Vulkan 管線
    ├── VK_KHR_ray_tracing_pipeline
    ├── VK_KHR_acceleration_structure
    ├── NRD 降噪（ReBLUR）
    ├── DLSS / FSR 3.1 / XeSS
    └── VMA 記憶體管理
```

#### 關鍵技術點

| 技術 | 做法 | 移植價值 |
|------|------|---------|
| **JNI 橋接** | Java↔C++ 雙倉庫，CMake 建構 | 我們已有 LWJGL Vulkan，不需 JNI |
| **純 Vulkan** | 完全取代 GL，無互操作 | **高移植價值** — 消除 GL/VK 同步開銷 |
| **NRD 整合** | ReBLUR 反射降噪 + DLSS Ray Reconstruction 後備 | **必須移植** — 取代自研 SVGF |
| **模組化渲染區塊** | 可插拔渲染通道架構 | 與 BRRenderPipeline 通道設計一致 |
| **多彈射路徑追蹤** | 完整路徑追蹤而非僅陰影 | **目標架構** — 統一照明模型 |

#### 核心啟發
- **放棄 GL/VK 混合**：Block Reality 不需兼容其他模組，可以拋棄 OpenGL 完全走 Vulkan
- **NRD 是標準**：Radiances 用 ReBLUR，Continuum 遷向 ReLAX，都是 NRD 家族
- **DLSS/FSR 原生整合**：純 Vulkan 架構下整合超解析度方案遠比 GL/VK 混合容易

### 2.3 Voxy（3D 體素 LOD 系統）

Voxy 由 MCRcortex 開發（同時也是 Nvidium、Vulkanite 作者），提供真正的 3D 體素 LOD。

#### 架構設計

```
玩家移動
    ↓
Ingest 執行緒（捕獲世界資料 → LOD 體素）
    ↓
層級 LOD 資料結構
    ├── LOD 0: 1:1（原始方塊）
    ├── LOD 1: 2:1（2×2×2 合併）
    ├── LOD 2: 4:1
    └── LOD N: 2^N:1
    ↓
GPU 稀疏 VRAM 緩衝
    ↓
Mesh Shader 間接繪製（GL_NV_mesh_shader）
```

#### 關鍵技術點

| 技術 | 做法 | 移植價值 |
|------|------|---------|
| **真 3D LOD** | 保留洞穴、懸崖等立體結構（非 Distant Horizons 的 2.5D 高度圖） | **核心移植** — RT 需要完整 3D 幾何 |
| **非同步管線** | 專用 CPU 執行緒：Ingest / Saving / Render | 可整合至 PhysicsScheduler 的執行緒模型 |
| **稀疏 VRAM** | 僅佔用表面積記憶體而非體積 | 與 SparseVoxelOctree 設計一致 |
| **Mesh Shader** | `glMultiDrawMeshTasksIndirectNV` GPU 驅動繪製 | **移植至 Vulkan mesh shader** |
| **磁碟持久化** | LOD 資料存磁碟避免 RAM 無限增長 | 大型結構物必須 |
| **漸進式捕獲** | 玩家探索時逐步建構 LOD | 適合 Block Reality 的動態建造場景 |

#### 與 Distant Horizons 差異（移植決策依據）

| | Voxy | Distant Horizons |
|---|------|-----------------|
| 維度 | 真 3D 體素 | 2.5D 高度圖 |
| 洞穴/懸崖 | 保留 | 丟失 |
| RT 可用性 | **可直接供 BLAS** | 需額外處理 |
| 記憶體效率 | 較高（稀疏） | 較低 |
| 效能 | 快 10-30% | — |

**結論：移植 Voxy 的 3D LOD 做法**，因為 RT 加速結構需要完整的 3D 幾何資訊。

### 2.4 RTX 30+（Ampere/Ada）硬體特性

我們限定 RTX 30 以上，可以充分利用以下硬體特性：

#### 第 2 代 RT 核心（Ampere — RTX 30 系列，最低目標）
- 光線-三角形求交吞吐量為 Turing 的 2 倍
- RT 與計算/光柵化可**並行執行**
- 這是我們的效能基準線

#### 第 3 代 RT 核心（Ada Lovelace — RTX 40 系列，進階目標）

| 特性 | 說明 | Minecraft 價值 |
|------|------|--------------|
| **Opacity Micromap (OMM)** | 硬體加速 alpha 測試幾何（樹葉、柵欄、玻璃） | **極高** — Minecraft 大量 alpha 測試方塊，OMM 可減少 ~39% RT 成本 |
| **Shader Execution Reorder (SER)** | 重排發散的光線工作負載提高 GPU 佔用率 | **高** — Minecraft 世界材質高度發散，SER 可提升 20-40% |
| **Displaced Micro-Mesh (DMM)** | 微三角形細分 | 中 — 可用於鑿刻系統曲面 |

#### Vulkan 擴展對照

| 擴展 | 用途 | 目標 |
|------|------|------|
| `VK_KHR_ray_tracing_pipeline` | 主路徑追蹤管線 | **必須**（已有） |
| `VK_KHR_acceleration_structure` | BLAS/TLAS | **必須**（已有） |
| `VK_KHR_ray_query` | 計算著色器內聯 RT（陰影/AO） | **新增** |
| `VK_EXT_mesh_shader` | GPU 驅動幾何（Voxy 式 LOD 渲染） | **新增** |
| `VK_EXT_opacity_micromap` | OMM alpha 測試加速 | **新增**（Ada） |
| `VK_EXT_ray_tracing_invocation_reorder` | SER 著色器重排 | **新增**（Ada） |
| `VK_KHR_buffer_device_address` | SBT/BLAS 定址 | **必須**（已有） |
| `VK_KHR_cooperative_matrix` | 張量核心矩陣運算（神經網路降噪） | **未來** |

---

## 第三章：移植方案設計 — 純 Vulkan RT 管線重構

### 3.1 核心決策：拋棄 GL/VK 混合，走純 Vulkan

現有架構的最大瓶頸是 GL↔VK 互操作。既然我們**不需兼容其他模組**，且目標硬體為 RTX 30+，最合理的做法是：

```
【現有】GL G-Buffer → VK RT → GL/VK 互操作 → GL 合成
                                    ↑ 瓶頸

【目標】VK G-Buffer → VK RT → VK 降噪 → VK 合成 → VK 呈現
         全程 Vulkan，零互操作開銷
```

#### 為什麼不用 JNI（Radiances 做法）

Radiances 用 JNI 橋接到 C++ 渲染器是因為它要從零建構完整的 Vulkan 管線。Block Reality 已有 LWJGL 3.3+ 的 Vulkan 綁定，可以**完全在 Java 內**透過 LWJGL 操作 Vulkan API，不需引入 C++ 建構鏈。

**我們的路線**：保持 Java + LWJGL Vulkan，但重構為純 Vulkan 管線。

### 3.2 新管線架構

```
┌─────────────────────────────────────────────────────┐
│                Block Reality RT Pipeline             │
│                                                     │
│  ┌──────────┐   ┌──────────┐   ┌──────────────────┐│
│  │ Geometry  │   │  Path    │   │    Denoise +     ││
│  │  Stage    │──→│  Trace   │──→│    Composite     ││
│  └──────────┘   └──────────┘   └──────────────────┘│
│       │              │                │              │
│  VK Mesh Shader  VK RT Pipeline   VK Compute       │
│  + BLAS/TLAS     1-2 SPP          NRD ReLAX        │
│                                   + DLSS/FSR        │
│                                                     │
│  ┌──────────────────────────────────────────┐       │
│  │           LOD Voxel System               │       │
│  │  近場：原始方塊 BLAS                      │       │
│  │  中場：2x-4x 合併 BLAS                    │       │
│  │  遠場：8x-16x SVDAG 計算追蹤              │       │
│  └──────────────────────────────────────────┘       │
└─────────────────────────────────────────────────────┘
```

### 3.3 幾何階段重構

#### 3.3.1 取代 GreedyMesher 的 GPU 體素化（Continuum 做法移植）

```
【現有】CPU GreedyMesher → AABB 陣列 → 上傳至 VK BLAS
         ↑ CPU 瓶頸，每次方塊變更需重跑

【目標】GPU 計算著色器體素化 → 直接寫入 VK BLAS 構建輸入
         ↑ 全 GPU，方塊變更僅重算受影響 section
```

**實作要點：**
- 計算著色器讀取 section 方塊資料（SSBO）
- 輸出三角形幾何（非 AABB）— 因為硬體三角形求交比自訂 AABB 求交快
- 利用 `VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT` 供頻繁更新的 section
- 穩定 section 切換為 `PREFER_FAST_TRACE`

#### 3.3.2 BLAS/TLAS 策略升級

```
TLAS（每幀重建，PREFER_FAST_BUILD）
├── 近場 BLAS（≤8 chunks）
│   └── 每 section 16×16×16，三角形幾何
│   └── 雙緩衝：前/後 BLAS 避免 GPU 停頓
│   └── 單方塊變更用 refit，大規模變更用 rebuild
│
├── 中場 BLAS（8-32 chunks）
│   └── LOD 1-2，2x/4x 合併體素
│   └── 降低三角形數但保留 3D 輪廓
│
└── 遠場（>32 chunks）
    └── 不進 TLAS — 改用 SVDAG 計算著色器追蹤
    └── 記憶體效率優先（5-10x 壓縮）
```

**每幀 BLAS 重建預算提升：**
- 現有：8 個/幀 → 目標：**32 個/幀**
- RTX 30 的並行 RT+計算能力允許更多非同步 BLAS 構建

### 3.4 路徑追蹤階段（Radiances 做法移植）

#### 3.4.1 從單彈射升級為多彈射

```
【現有】1 次彈射（僅陰影光線）

【目標】最多 3 次彈射（可配置）
  彈射 0: 主光線（從 G-Buffer 重建）
  彈射 1: 直接照明 + 陰影光線
  彈射 2: 間接照明（漫反射 GI）
  彈射 3: 鏡面反射/折射（水、玻璃）（可選）
```

#### 3.4.2 統一照明模型

取代目前分散的照明系統：

| 現有（分散） | 目標（統一） |
|------------|------------|
| CSM 陰影 | RT 陰影光線 |
| SSGI | RT 間接照明 |
| VCT 體素錐追蹤 | RT 漫反射 GI |
| SSR 螢幕空間反射 | RT 鏡面反射 |
| SSAO | RT 環境遮蔽 |

**全部由路徑追蹤器統一處理**，消除多套系統的維護成本和視覺不一致。

#### 3.4.3 著色器架構

```glsl
// Ray Generation Shader
layout(set = 0, binding = 0) uniform accelerationStructureEXT tlas;
layout(set = 0, binding = 1, rgba16f) uniform image2D outputImage;

void main() {
    // 從 G-Buffer 重建世界座標
    vec3 worldPos = reconstructWorldPos(gl_LaunchIDEXT.xy);
    vec3 normal = sampleGBufferNormal(gl_LaunchIDEXT.xy);
    
    vec3 radiance = vec3(0.0);
    vec3 throughput = vec3(1.0);
    
    // 多彈射路徑追蹤
    for (int bounce = 0; bounce < MAX_BOUNCES; bounce++) {
        // 直接照明（陰影光線）
        radiance += throughput * traceShadowRay(worldPos, sunDir);
        
        // 間接照明（隨機方向）
        vec3 bounceDir = cosineWeightedHemisphere(normal, rng);
        // traceRayEXT → closest hit / miss
        // 累積輻射度
    }
    
    imageStore(outputImage, ivec2(gl_LaunchIDEXT.xy), vec4(radiance, 1.0));
}
```

### 3.5 降噪階段重構（NRD 移植）

#### 3.5.1 取代 BRSVGFDenoiser

| | 現有 SVGF | 目標 NRD ReLAX |
|---|----------|---------------|
| 降噪品質 | 中等 | 高（產業標準） |
| 鬼影/殘影 | 時序累積易有 | 自適應歷史截斷 |
| 鏡面反射 | 不支援 | ReBLUR 專用鏡面降噪 |
| 跨廠商 | 是 | 是（NRD 純 HLSL/GLSL） |
| GPU 成本 | 0.5-1.5 ms | 0.8-2.0 ms（但品質遠高） |

#### 3.5.2 NRD 整合方式

NRD 是純著色器庫（HLSL/GLSL），不依賴任何 API 特定功能：

```
路徑追蹤輸出
    ├── 漫反射輻照度 → ReLAX Diffuse 降噪
    ├── 鏡面輻照度 → ReBLUR Specular 降噪
    └── 陰影遮蔽 → SIGMA 陰影降噪
         ↓
    合成最終影像
         ↓
    DLSS / FSR 超解析度（可選）
```

**LWJGL 整合路徑：**
- NRD 著色器編譯為 SPIR-V
- 在 Vulkan 計算管線中執行
- 輸入/輸出為 VkImage（純 Vulkan，無 GL 互操作）

### 3.6 要移除/取代的現有類別

| 現有類別 | 處置 | 原因 |
|---------|------|------|
| `BRVulkanInterop` | **移除** | 純 Vulkan 不需 GL 互操作 |
| `BRSVGFDenoiser` | **取代** → NRD ReLAX/ReBLUR | 產業標準降噪 |
| `BRCascadedShadowMap` | **移除** | RT 陰影取代 |
| `BRGlobalIllumination` (SSGI+VCT) | **移除** | RT GI 取代 |
| `GreedyMesher` (CPU 版) | **取代** → GPU 計算體素化 | 消除 CPU 瓶頸 |
| `BRVulkanRT` | **重構** | 從單彈射升級為多彈射路徑追蹤 |
| `BRVulkanBVH` | **重構** | 加入 LOD 感知 + 雙緩衝 + refit |
| `BRVulkanDevice` | **擴展** | 新增 mesh shader / OMM / SER 偵測 |

### 3.7 要保留/擴展的現有類別

| 現有類別 | 處置 | 原因 |
|---------|------|------|
| `BRSparseVoxelDAG` | **擴展** | 遠場 RT 追蹤用 |
| `BRLODEngine` | **擴展** | 整合 Voxy 式 3D LOD |
| `BRRenderPipeline` | **重構** | 從 GL+VK 混合改為純 VK 通道 |
| `RenderPipeline` 通道架構 | **保留** | 模組化通道設計仍然適用 |
| `BRShaderEngine` | **擴展** | 新增 SPIR-V 計算/RT 著色器管理 |

---

## 第四章：Voxy 式 3D LOD 體素系統移植

### 4.1 為什麼 RT 需要 LOD 體素

當前 `BRVulkanBVH` 將所有可見 section 以相同解析度放入 TLAS，這造成：
- 遠距 section 佔用大量 BLAS 記憶體但貢獻極少像素
- TLAS 實例數隨渲染距離立方成長
- 32 chunk 渲染距離 = ~4096 section = ~200 MB BLAS VRAM

**Voxy 的解法：距離感知 LOD，遠的用粗體素。**

### 4.2 LOD 體素資料結構

移植 Voxy 的層級 3D LOD，但重寫為 Block Reality 自有結構：

```java
/**
 * 新增類別：BRVoxelLODManager
 * 位於 api/client/render/optimization/
 */
public class BRVoxelLODManager {
    
    // LOD 層級定義
    // LOD 0: 1:1 原始方塊（現有 section 資料）
    // LOD 1: 2:1 合併（8 方塊 → 1 體素，取主要材質）
    // LOD 2: 4:1 合併（64 方塊 → 1 體素）
    // LOD 3: 8:1 合併（512 方塊 → 1 體素）
    // LOD 4: 16:1 合併（4096 方塊 → 1 體素）
    
    private static final int MAX_LOD_LEVEL = 4;
    
    // 每個 LOD 層級的 section 容器
    // key = sectionKey(x, z), value = 壓縮體素資料
    private final ConcurrentHashMap<Long, LODSection>[] lodLevels;
    
    // LOD 距離閾值（chunk 數）
    private static final int[] LOD_DISTANCES = {8, 16, 32, 64, 128};
}
```

#### LOD Section 資料結構

```java
public class LODSection {
    int lodLevel;           // 0-4
    int worldX, worldZ;     // section 世界座標
    short[] voxelData;      // 壓縮體素陣列（materialId）
    int voxelSize;          // 此 LOD 的有效體素數
    boolean dirty;          // 需要重建 BLAS
    long blasHandle;        // 對應的 VK BLAS（0 = 未建立）
    
    // LOD 0: 16×16×16 = 4096 voxels（原始）
    // LOD 1: 8×8×8 = 512 voxels
    // LOD 2: 4×4×4 = 64 voxels
    // LOD 3: 2×2×2 = 8 voxels
    // LOD 4: 1×1×1 = 1 voxel（整個 section 一個材質）
}
```

### 4.3 非同步 LOD 管線（移植 Voxy 執行緒模型）

```
┌─────────────────────────────────────────────────┐
│                LOD 管線執行緒模型                  │
│                                                 │
│  主執行緒                                        │
│  └── 標記髒 section、決定 LOD 層級               │
│                                                 │
│  Ingest 執行緒池（2 條）                          │
│  └── 從世界區塊提取方塊資料 → LOD 0               │
│  └── 方塊變更事件觸發增量更新                      │
│                                                 │
│  Downscale 執行緒（1 條）                         │
│  └── LOD 0 → LOD 1 → LOD 2 → ...（逐級降採樣）  │
│  └── 材質選擇：取 2×2×2 中出現最多的材質          │
│                                                 │
│  BLAS Builder 執行緒（1 條）                      │
│  └── 從 LODSection 產生三角形 → 構建 VK BLAS      │
│  └── 預算：每幀最多 32 個 BLAS 構建               │
│                                                 │
│  Disk I/O 執行緒（1 條）                          │
│  └── LOD 資料持久化（大型結構物必須）              │
└─────────────────────────────────────────────────┘
```

### 4.4 LOD 感知 TLAS

將 LOD 整合進加速結構：

```
TLAS（每幀重建）
│
├── 近場（≤8 chunks，LOD 0）
│   └── 完整 16×16×16 三角形 BLAS
│   └── 雙緩衝避免 GPU 停頓
│   └── 單方塊變更 → BLAS refit（非 rebuild）
│
├── 中場（8-32 chunks，LOD 1-2）
│   └── 降解析度 BLAS（8³ 或 4³ 三角形）
│   └── PREFER_FAST_BUILD（頻繁 LOD 切換）
│   └── 三角形數降為近場的 1/8 ~ 1/64
│
├── 遠場 A（32-64 chunks，LOD 3-4）
│   └── 極簡 BLAS（2³ 或 1³ 粗體素）
│   └── 主要供 GI 光線使用
│
└── 超遠場（>64 chunks）
    └── 不進 TLAS
    └── 改用 SVDAG 計算著色器軟追蹤
    └── 僅 GI/天空遮蔽光線會到達此距離
```

#### 記憶體預算對比

| 渲染距離 | 現有（無 LOD） | 目標（有 LOD） | 節省 |
|---------|--------------|-------------|------|
| 8 chunks | ~50 MB | ~50 MB | 0% |
| 16 chunks | ~200 MB | ~80 MB | 60% |
| 32 chunks | ~800 MB | ~150 MB | 81% |
| 64 chunks | 不可行 | ~250 MB | ∞ |
| 128 chunks | 不可行 | ~400 MB | ∞ |

### 4.5 遠場 SVDAG 軟追蹤

超遠場（>64 chunks）不放入硬體 TLAS，改用現有 `BRSparseVoxelDAG` 的計算著色器追蹤：

```glsl
// Compute shader — SVDAG 軟光線追蹤
// 僅用於 GI 間接光線的遠距取樣

layout(set = 0, binding = 0) readonly buffer SVDAGNodes {
    uint nodes[];  // 壓縮 DAG 節點
};

bool traceSVDAG(vec3 origin, vec3 direction, float maxDist, 
                out vec3 hitPos, out uint hitMaterial) {
    // DDA 步進穿越 SVDAG
    // 在目標 LOD 層級早期終止
    // 返回命中位置和材質 ID
}
```

**優勢：**
- SVDAG 壓縮比 5-10x，128 chunk 距離僅需 ~50 MB
- 計算著色器與 RT 管線並行執行（Ampere 並行計算能力）
- GI 光線對精度要求低，粗體素足夠

### 4.6 與物理系統的整合

Block Reality 的 `SparseVoxelOctree`（`physics/sparse/`）已有 16³ section 體素結構。LOD 系統可以**共用此資料源**：

```
世界方塊資料
    ├── physics/SparseVoxelOctree（物理用，已有）
    │   └── 結構分析、荷載計算
    │
    └── render/BRVoxelLODManager（渲染用，新增）
        └── LOD 降採樣 → BLAS/SVDAG → RT
```

兩者共用底層方塊資料，但維護獨立的衍生結構（物理用八叉樹 vs 渲染用 LOD 體素）。

---

## 第五章：RTX 30+ 專屬優化

### 5.1 基線配置（RTX 30 系列 — Ampere）

所有 RTX 30 必須支援的功能：

```
路徑追蹤：2 彈射（直接照明 + 1 次間接）
解析度：1080p 內部渲染 + DLSS/FSR 升頻至目標
降噪：NRD ReLAX（漫反射）+ ReBLUR（鏡面）
LOD：4 級 3D 體素 LOD
BLAS 重建：32/幀
TLAS 重建：每幀（PREFER_FAST_BUILD）
```

#### 效能目標（RTX 3060，1080p 內部）

| 階段 | 預算 |
|------|------|
| G-Buffer（Vulkan 光柵化） | 2.0 ms |
| BLAS 更新 + TLAS 重建 | 1.0 ms |
| 路徑追蹤（2 彈射，1 SPP） | 4.0 ms |
| NRD 降噪 | 2.0 ms |
| SVDAG 遠場追蹤 | 1.0 ms |
| 合成 + 後製 | 1.5 ms |
| DLSS/FSR | 1.0 ms |
| **總計** | **12.5 ms（80 FPS）** |

### 5.2 Ada 進階優化（RTX 40 系列）

偵測到 Ada 硬體時啟用的額外優化：

#### Opacity Micromap (OMM)
```java
// BRVulkanDevice 中新增偵測
if (hasExtension("VK_EXT_opacity_micromap")) {
    // 為 alpha 測試方塊（樹葉、柵欄、玻璃板）建立 OMM
    // 預期減少 RT 成本 30-39%
    enableOpacityMicromap();
}
```

**Minecraft 中受益的方塊：**
- 樹葉（oak_leaves, birch_leaves, ...）— 最大 RT 效能殺手
- 柵欄、鐵欄杆
- 玻璃板
- 高草、蕨類
- 作物

#### Shader Execution Reordering (SER)
```java
if (hasExtension("VK_EXT_ray_tracing_invocation_reorder")) {
    // Minecraft 世界材質高度發散（石頭旁邊就是木頭旁邊就是鑽石）
    // SER 重新排列相同材質的著色器調用
    // 預期提升 20-40%
    enableSER();
}
```

#### Ada 效能目標（RTX 4060，1080p 內部）

| 階段 | Ampere 預算 | Ada 預算（含 OMM+SER） |
|------|-----------|---------------------|
| 路徑追蹤 | 4.0 ms | **2.5 ms**（-37%） |
| 其他不變 | 8.5 ms | 8.5 ms |
| **總計** | **12.5 ms** | **11.0 ms** |

多出的預算可用於：提升至 3 彈射、或提高內部解析度、或啟用更多效果。

### 5.3 ray_tracing_pipeline + ray_query 雙軌策略

| 效果 | 使用 API | 原因 |
|------|---------|------|
| 主路徑追蹤（GI、反射） | `ray_tracing_pipeline` | 需要自訂著色器階段、多彈射 |
| 簡易陰影光線 | `ray_query`（計算著色器） | 更輕量、AMD 上更快 |
| AO 光線 | `ray_query` | 短距離、不需複雜命中處理 |

### 5.4 Mesh Shader LOD 光柵化

近場 G-Buffer 用 Vulkan Mesh Shader 取代傳統頂點管線（移植 Nvidium/Voxy 做法）：

```
傳統管線：
  CPU 準備繪製命令 → Vertex Shader → Rasterizer
  ↑ CPU 瓶頸：每 section 一次 draw call

Mesh Shader 管線：
  GPU 任務著色器（剔除不可見 section）
    → GPU 網格著色器（產生三角形）
      → 光柵化
  ↑ 單次 vkCmdDrawMeshTasksIndirectEXT，GPU 驅動
```

**需要 `VK_EXT_mesh_shader`**（RTX 30+ 皆支援）。

---

## 第六章：實作路線圖

### 6.1 階段劃分

```
Phase 1: 純 Vulkan 基礎（4-6 週）
├── 重構 BRVulkanDevice — 新增 mesh shader / OMM / SER 偵測
├── 純 VK G-Buffer 管線 — 取代 GL G-Buffer
├── 移除 BRVulkanInterop — 不再需要 GL/VK 互操作
└── 基礎 Vulkan 呈現鏈（swapchain → 螢幕）

Phase 2: 路徑追蹤升級（4-6 週）
├── 重構 BRVulkanRT — 多彈射路徑追蹤（2-3 bounce）
├── GPU 計算體素化 — 取代 CPU GreedyMesher
├── 整合 NRD 降噪 — ReLAX + ReBLUR SPIR-V 著色器
└── 移除舊照明系統（CSM, SSGI, VCT, SSR）

Phase 3: LOD 體素系統（3-4 週）
├── 新增 BRVoxelLODManager — 4 級 3D LOD
├── 非同步 Ingest/Downscale 執行緒管線
├── LOD 感知 TLAS — 近/中/遠場分級 BLAS
├── SVDAG 遠場軟追蹤計算著色器
└── 磁碟持久化（大型結構物）

Phase 4: 硬體特性利用（2-3 週）
├── VK_EXT_mesh_shader G-Buffer 光柵化
├── VK_EXT_opacity_micromap（Ada）
├── VK_EXT_ray_tracing_invocation_reorder（Ada）
├── VK_KHR_ray_query 陰影/AO 快速路徑
└── DLSS / FSR 整合

Phase 5: 調優與整合（2-3 週）
├── 效能分析與瓶頸調優
├── 與物理系統（SparseVoxelOctree）資料共用
├── 與節點系統（渲染節點）整合
├── 鑿刻系統子方塊幾何 RT 支援
└── 邊緣情況處理（動態方塊、實體、粒子）
```

### 6.2 新增/修改檔案清單

```
api/client/render/
├── rt/
│   ├── BRVulkanDevice.java          ← 擴展（mesh shader, OMM, SER）
│   ├── BRVulkanRT.java              ← 重構（多彈射路徑追蹤）
│   ├── BRVulkanBVH.java             ← 重構（LOD BLAS, 雙緩衝, refit）
│   ├── BRVulkanInterop.java         ← 移除
│   ├── BRSVGFDenoiser.java          ← 移除（NRD 取代）
│   ├── BRNRDDenoiser.java           ← 新增（NRD ReLAX + ReBLUR）
│   ├── BRVulkanPresent.java         ← 新增（Vulkan swapchain 呈現）
│   └── BRVulkanGBuffer.java         ← 新增（純 VK G-Buffer）
├── optimization/
│   ├── BRVoxelLODManager.java       ← 新增（3D LOD 管理）
│   ├── LODSection.java              ← 新增（LOD 體素資料）
│   ├── BRGPUVoxelizer.java          ← 新增（GPU 計算體素化）
│   ├── BRSparseVoxelDAG.java        ← 擴展（遠場 RT 追蹤）
│   └── GreedyMesher.java            ← 保留（後備/非 RT 用途）
├── pipeline/
│   ├── BRRenderPipeline.java        ← 重構（純 VK 通道）
│   └── BRMeshShaderPipeline.java    ← 新增（mesh shader G-Buffer）
├── postfx/
│   ├── BRGlobalIllumination.java    ← 移除（RT GI 取代）
│   └── 其他後製保留
└── shadow/
    └── BRCascadedShadowMap.java     ← 移除（RT 陰影取代）
```

### 6.3 風險與緩解

| 風險 | 影響 | 緩解策略 |
|------|------|---------|
| NRD SPIR-V 編譯複雜度 | 高 | NRD 官方提供 SPIR-V，直接使用 |
| 純 VK 遷移破壞現有渲染 | 高 | 保留 GL 後備路徑直到 VK 穩定 |
| Mesh shader 相容性 | 中 | 偵測不支援時回退傳統頂點管線 |
| LOD 接縫/彈出 | 中 | 漸進式 LOD 混合 + 時序累積隱藏 |
| BLAS 記憶體碎片化 | 中 | 定期壓實（compaction）+ VMA 管理 |
| 鑿刻系統子方塊幾何 | 低 | 鑿刻方塊展開為三角形加入 BLAS |

### 6.4 效能總結

#### RTX 3060（1080p 內部 → DLSS 4K）

```
G-Buffer (Mesh Shader):    2.0 ms
BLAS/TLAS 更新:            1.0 ms
路徑追蹤 (2 bounce, 1SPP): 4.0 ms
NRD 降噪:                  2.0 ms
SVDAG 遠場:                1.0 ms
合成 + 後製:               1.5 ms
DLSS:                      1.0 ms
────────────────────────────
總計:                      12.5 ms → 80 FPS
```

#### RTX 4060（1080p 內部 → DLSS 4K，含 OMM+SER）

```
G-Buffer (Mesh Shader):    2.0 ms
BLAS/TLAS 更新:            0.8 ms
路徑追蹤 (3 bounce, 1SPP): 2.5 ms  ← OMM+SER 加速
NRD 降噪:                  1.5 ms
SVDAG 遠場:                0.8 ms
合成 + 後製:               1.0 ms
DLSS:                      0.8 ms
────────────────────────────
總計:                       9.4 ms → 106 FPS
```

---

## 附錄：參考資料

| 來源 | 用途 |
|------|------|
| Continuum RT / Focal Engine | 計算驅動體素化、A-SVGF → NRD 遷移路徑 |
| Radiances / MCVR | 純 Vulkan 架構、NRD 整合模式、DLSS/FSR 原生接入 |
| Voxy / Nvidium | 3D 體素 LOD、mesh shader 間接繪製、稀疏 VRAM |
| NVIDIA NRD | ReLAX/ReBLUR 降噪著色器庫 |
| Vulkan RT Best Practices (Khronos) | BLAS/TLAS 策略、混合光柵化+RT |
| Ada Lovelace Architecture (NVIDIA) | OMM、SER、DMM 硬體特性 |
| High Resolution Sparse Voxel DAGs (Kämpe et al.) | SVDAG 壓縮與遍歷 |
| Aokana GPU-Driven Voxel Framework (2025) | 多小型 SVDAG 策略 |
