# Block Reality — Node-Based Visual Configuration System
# 節點式視覺化設定系統 設計報告 v1.1

**日期**: 2026-03-28
**版本**: 1.1
**機密等級**: Internal Use Only

---

### v1.1 變更摘要

| 變更項目 | 說明 |
|---------|------|
| **新增 Category B5: 材質調配節點** | 8 個新節點 — 基於現有基礎方塊選取、數值微調、混合比例、即時預覽，輸出為可放置的自訂方塊 |
| 節點總數 | 136 → **144** |
| B 類節點數 | 32 → **40** |
| 新增 Port 型別 `BLOCK` | 封裝 blockId + RMaterial + texture，用於調配管線的方塊傳遞 |
| 工時更新 | N4 材料節點小計 48h → **60h**；總計 364.5h → **376.5h** |
| 路線圖更新 | Week 8-10 加入材質調配節點實作 |
| 附錄更新 | 連接矩陣新增 BLOCK 型別行列 |

---

## 目錄

1. [Executive Summary](#1-executive-summary)
2. [系統架構總覽](#2-系統架構總覽)
3. [節點引擎核心設計](#3-節點引擎核心設計)
4. [節點類別總表](#4-節點類別總表)
5. [Category A: 渲染管線節點](#5-category-a-渲染管線節點)
6. [Category B: 材料與方塊特性節點](#6-category-b-材料與方塊特性節點)
7. [Category C: 物理計算節點](#7-category-c-物理計算節點)
8. [Category D: 工具與 UI 節點](#8-category-d-工具與-ui-節點)
9. [Category E: 輸出與匯出節點](#9-category-e-輸出與匯出節點)
10. [視覺設計規範](#10-視覺設計規範)
11. [取代原版視訊設定](#11-取代原版視訊設定)
12. [剩餘工作清單](#12-剩餘工作清單)
13. [實作路線圖](#13-實作路線圖)
14. [附錄: 節點連接規則](#14-附錄-節點連接規則)

---

## 1. Executive Summary

本報告設計一套 **Grasshopper 風格的節點式視覺化設定系統**，用於 Block Reality 的所有可配置行為。此系統：

- **取代原版 Minecraft 視訊設定**（Options → Video Settings）
- **統一管理** 渲染管線（30+ 後處理 pass）、材料屬性、物理引擎參數、工具行為
- **節點間真實連接**：資料沿連線流動，修改上游節點即時影響下游
- **高設定豐富度**：從「一鍵品質預設」到「逐參數微調」，覆蓋初學者到專業用戶
- **即時預覽**：修改任何參數時，3D 視窗即時反映變化
- **材質調配**（v1.1 新增）：選用任何基礎方塊作為起點，視覺化調整工程數值，創建全新自訂方塊並即時放置於世界中

**核心理念**：Block Reality 的設定不是一張平面表單，而是一張有向無環圖（DAG），
每個渲染效果、每種材料、每條物理規則都是圖中的一個節點，
用戶透過拖曳連線定義它們之間的關係。

---

## 2. 系統架構總覽

### 2.1 架構層次

```
┌─────────────────────────────────────────────────────────┐
│                    Node Graph UI Layer                    │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐   │
│  │ Canvas   │ │ Node     │ │ Wire     │ │ Preview  │   │
│  │ Renderer │ │ Widgets  │ │ Renderer │ │ Viewport │   │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘   │
├─────────────────────────────────────────────────────────┤
│                    Node Engine Layer                      │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐   │
│  │ NodeGraph│ │ Port     │ │ Type     │ │ Evaluate │   │
│  │ Manager  │ │ System   │ │ Checker  │ │ Scheduler│   │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘   │
├─────────────────────────────────────────────────────────┤
│                    Data Binding Layer                     │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐   │
│  │ Config   │ │ Material │ │ Physics  │ │ Shader   │   │
│  │ Binder   │ │ Binder   │ │ Binder   │ │ Binder   │   │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘   │
├─────────────────────────────────────────────────────────┤
│                Block Reality Runtime Layer                │
│  BRRenderConfig │ RMaterial │ ForceEQ │ BRShaderEngine   │
└─────────────────────────────────────────────────────────┘
```

### 2.2 核心元件

| 元件 | 職責 | 對應 Grasshopper |
|------|------|----------------|
| **NodeGraph** | 管理所有節點和連線的 DAG | GH_Document |
| **BRNode** | 單一計算單元（輸入→處理→輸出） | GH_Component |
| **NodePort** | 節點的輸入/輸出接口 | GH_Param |
| **Wire** | 兩個 Port 之間的資料連線 | GH_Wire |
| **Canvas** | 無限平移/縮放的 2D 畫布 | GH_Canvas |
| **NodeGroup** | 可折疊的節點群組 | GH_Group |
| **Preset** | 預設節點圖（一鍵套用） | GH_Cluster |

### 2.3 資料流模型

```
[Input Node] ──float──→ [Processing Node] ──Material──→ [Output Node]
     │                        │                              │
     │  u_ssaoRadius=0.5     │  RC Fusion 計算              │  寫入 BRConfig
     │                        │  Rcomp=33.0 MPa             │
     ▼                        ▼                              ▼
  即時預覽更新            中間結果可視化              TOML 檔案輸出
```

**評估策略**：惰性評估 + 髒標記傳播
- 修改任一輸入 → 標記該節點及所有下游為「髒」
- 下一幀只重新計算髒節點（避免全圖重算）
- 使用拓撲排序確保評估順序正確

---

## 3. 節點引擎核心設計

### 3.1 BRNode 基礎類別

```java
public abstract class BRNode {
    // ─── 識別 ───
    String nodeId;          // UUID
    String displayName;     // 「SSAO 環境遮蔽」
    String category;        // "render", "material", "physics", "tool"
    NodeColor color;        // 類別色（渲染=藍、材料=綠、物理=橙、工具=紫）

    // ─── 幾何 ───
    float posX, posY;       // 畫布座標
    float width, height;    // 節點尺寸（自動計算）
    boolean collapsed;      // 折疊狀態

    // ─── 端口 ───
    List<InputPort> inputs;
    List<OutputPort> outputs;

    // ─── 狀態 ───
    boolean dirty;          // 需要重新計算
    boolean enabled;        // 是否啟用（灰色 = 停用）
    long lastEvalTimeNs;    // 上次計算耗時

    // ─── 核心方法 ───
    abstract void evaluate();       // 從 inputs 計算 outputs
    abstract void onPortChanged();  // 輸入變更回調
    abstract String getTooltip();   // 懸停提示
}
```

### 3.2 Port 型別系統

| 型別 ID | Java 型別 | 線色 | 用途 |
|---------|----------|------|------|
| `FLOAT` | float | 灰白 #CCCCCC | 數值參數（半徑、強度、閾值） |
| `INT` | int | 淺藍 #88BBFF | 整數（解析度、迭代次數、距離） |
| `BOOL` | boolean | 黃色 #FFCC00 | 開關（啟用/停用） |
| `VEC2` | float[2] | 淡紫 #CC88FF | 2D 向量（UV、螢幕座標） |
| `VEC3` | float[3] | 青色 #00CCCC | 3D 向量（位置、顏色 RGB） |
| `VEC4` | float[4] | 粉紅 #FF88CC | 4D（RGBA、四元數） |
| `COLOR` | int ARGB | 彩虹漸層 | 顏色選擇 |
| `MATERIAL` | RMaterial | 綠色 #44CC44 | 材料物件 |
| `BLOCK` | BRBlockDef | 翠綠 #00CC88 | 方塊定義（blockId + material + texture）（v1.1 新增） |
| `SHAPE` | SubBlockShape | 棕色 #AA7744 | 方塊形狀 |
| `TEXTURE` | int (GL tex ID) | 橘紅 #FF6644 | 紋理引用 |
| `ENUM` | Enum<?> | 白色 #FFFFFF | 枚舉選擇 |
| `CURVE` | float[] | 紫色 #8844CC | 曲線/LUT 資料 |
| `STRUCT` | CompoundTag | 銀色 #AAAAAA | 複合資料結構 |

### 3.3 連線規則

```
同型別：直接連接
FLOAT → INT：自動四捨五入
INT → FLOAT：自動提升
BOOL → INT：0/1
BOOL → FLOAT：0.0/1.0
VEC3 → COLOR：RGB→ARGB (alpha=1)
COLOR → VEC3：ARGB→RGB
BLOCK → MATERIAL：自動拆出 RMaterial（v1.1 新增）
MATERIAL → BLOCK：不允許（需要 BlockCreator 節點封裝）

不允許：
MATERIAL → FLOAT（需要拆分節點）
SHAPE → MATERIAL（語意不同）
任何型別 → TEXTURE（紋理不可運算）
```

### 3.4 節點尺寸規則（Grasshopper 風格）

```
┌──────────────────────────────────────┐
│  ▣ SSAO 環境遮蔽                [×] │  ← 標題列（類別色）
├──────────────────────────────────────┤
│ ● enabled ─────────── bool      ○   │  ← 輸入端口（左）/ 輸出端口（右）
│ ● kernelSize ──[16]── int       ○   │  ← 內嵌滑桿/數值框
│ ● radius ──[0.50]──── float     ○   │
│ ● intensity ──[1.0]── float     ○   │
│                                      │
│   ┌─────────────────────────┐        │
│   │   SSAO 效果即時預覽     │        │  ← 內嵌預覽（可選）
│   │   (64×64 thumbnail)     │        │
│   └─────────────────────────┘        │
├──────────────────────────────────────┤
│ result ────────────── texture    ●   │  ← 輸出端口
│ aoMap ─────────────── texture    ●   │
└──────────────────────────────────────┘
     140px 寬 × 自動高度
```

---

## 4. 節點類別總表

### 總計：144 個節點

| Category | 子類 | 節點數 | 色彩 |
|----------|------|--------|------|
| **A: 渲染管線** | A1 品質預設 | 5 | 🔵 藍 #2196F3 |
| | A2 管線控制 | 8 | |
| | A3 後處理效果 | 18 | |
| | A4 光照與陰影 | 7 | |
| | A5 LOD 與優化 | 9 | |
| | A6 天氣與大氣 | 6 | |
| | A7 水體 | 4 | |
| **B: 材料與方塊** | B1 基礎材料 | 14 | 🟢 綠 #4CAF50 |
| | B2 材料運算 | 8 | |
| | B3 方塊形狀 | 6 | |
| | B4 材料視覺化 | 4 | |
| | **B5 材質調配** | **8** | **🟢 翠綠 #00CC88** |
| **C: 物理計算** | C1 求解器設定 | 6 | 🟠 橙 #FF9800 |
| | C2 荷載與力 | 7 | |
| | C3 分析結果 | 5 | |
| | C4 崩塌與破壞 | 4 | |
| **D: 工具與 UI** | D1 選取工具 | 7 | 🟣 紫 #9C27B0 |
| | D2 放置工具 | 5 | |
| | D3 UI 外觀 | 6 | |
| | D4 輸入映射 | 4 | |
| **E: 輸出** | E1 配置匯出 | 3 | ⚪ 銀 #9E9E9E |
| | E2 效能監控 | 5 | |

---

## 5. Category A: 渲染管線節點

### A1: 品質預設節點（5 個）

這些是「一鍵設定」節點，連接到所有渲染子系統的輸入。
模擬 Grasshopper 的 Cluster 概念。

#### A1-1: `QualityPreset` — 總品質預設

```
輸入：
  ● preset ── ENUM [Potato / Low / Medium / High / Ultra / Custom]

輸出：
  ○ shadowRes ── INT        → 連接到 ShadowMap.resolution
  ○ ssaoEnabled ── BOOL     → 連接到 SSAO.enabled
  ○ ssrEnabled ── BOOL      → 連接到 SSR.enabled
  ○ taaEnabled ── BOOL      → 連接到 TAA.enabled
  ○ bloomEnabled ── BOOL    → 連接到 Bloom.enabled
  ○ lodMaxDist ── FLOAT     → 連接到 LOD.maxDistance
  ○ volumetricEnabled ── BOOL
  ○ cloudEnabled ── BOOL
  ○ waterQuality ── ENUM
  ○ particleCount ── INT
  ... (共 20 個輸出，對應所有子系統)
```

| Preset | shadowRes | SSAO | SSR | TAA | Bloom | LOD Max | Volumetric |
|--------|-----------|------|-----|-----|-------|---------|------------|
| Potato | 512 | OFF | OFF | OFF | OFF | 128 | OFF |
| Low | 1024 | ON (basic) | OFF | OFF | ON | 256 | OFF |
| Medium | 2048 | ON (GTAO) | OFF | ON | ON | 512 | OFF |
| High | 2048 | ON (GTAO) | ON | ON | ON | 768 | ON |
| Ultra | 4096 | ON (GTAO) | ON | ON | ON | 1024 | ON |
| Custom | — | — | — | — | — | — | — |

**行為**：選擇 Custom 時，所有輸出端口變為「未連接」狀態，
允許用戶手動連接個別設定節點。

#### A1-2: `PerformanceTarget` — 效能目標節點

```
輸入：
  ● targetFPS ── INT [30 / 60 / 120 / 144 / 240]
  ● gpuBudgetMs ── FLOAT [默認 16.67ms for 60fps]

輸出：
  ○ recommended ── STRUCT   → 根據 GPU 型號自動推薦品質
  ○ budgetPerPass ── FLOAT  → 每個 pass 的毫秒預算
  ○ warningLevel ── INT     → 0=OK, 1=接近預算, 2=超出
```

#### A1-3: `TierSelector` — 渲染 Tier 選擇

```
輸入：
  ● tier ── ENUM [Tier0_Compat / Tier1_Quality / Tier2_Ultra / Tier3_RT]

輸出：
  ○ glVersion ── INT        → 最低 GL 版本要求
  ○ featureFlags ── INT     → 位元旗標
  ○ shaderComplexity ── ENUM
  ○ maxTextureUnits ── INT
```

對應 v4 報告 §9.1 的四個渲染 Tier。

#### A1-4: `GPUDetect` — GPU 自動偵測節點

```
輸入：（無 — 自動偵測）

輸出：
  ○ gpuVendor ── ENUM [NVIDIA / AMD / INTEL / OTHER]
  ○ vramMB ── INT
  ○ glVersionMajor ── INT
  ○ meshShaderSupport ── BOOL
  ○ computeShaderSupport ── BOOL
  ○ recommendedTier ── ENUM
```

#### A1-5: `ABCompare` — A/B 品質比較節點

```
輸入：
  ● configA ── STRUCT （品質設定 A）
  ● configB ── STRUCT （品質設定 B）
  ● splitPosition ── FLOAT [0.0~1.0] （分割線位置）

輸出：
  ○ splitView ── BOOL → 啟用分割預覽
```

連接後，3D 預覽窗口左半使用 configA、右半使用 configB，
用戶可拖曳分割線即時比較。

---

### A2: 管線控制節點（8 個）

#### A2-1: `PipelineOrder` — 渲染 Pass 排序

```
輸入：
  ● passes ── ENUM[] （拖曳排序）
    [Shadow → GBuffer → Deferred → SSAO → SSR → Volumetric →
     Cloud → Fog → Weather → Bloom → Tonemap → DoF →
     Cinematic → ColorGrade → LensFlare → TAA → Final]

輸出：
  ○ orderedPasses ── STRUCT → 傳入 BRRenderPipeline
```

**特殊行為**：拖曳節點中的 pass 條目可重新排列，
紅色警示標記不合法的排序（如 TAA 在 GBuffer 之前）。

#### A2-2: `GBufferConfig` — GBuffer 配置

```
輸入：
  ● attachmentCount ── INT [3~5, 默認 5]
  ● hdrEnabled ── BOOL [默認 true]
  ● format ── ENUM [RGBA8 / RGBA16F / RGBA32F]

輸出：
  ○ gbufferSpec ── STRUCT
  ○ vramUsageMB ── FLOAT （即時計算的 VRAM 用量）
```

#### A2-3: `ShadowConfig` — 陰影設定

```
輸入：
  ● resolution ── INT [512 / 1024 / 2048 / 4096]
  ● maxDistance ── FLOAT [32~256, 默認 128]
  ● cascadeCount ── INT [1~4, 默認 4]
  ● pcfSamples ── INT [1~16]
  ● bias ── FLOAT [0.0001~0.01]

輸出：
  ○ shadowSpec ── STRUCT
  ○ shadowMap ── TEXTURE （即時預覽）
```

#### A2-4: `FramebufferChain` — FBO 鏈設定

```
輸入：
  ● screenScale ── FLOAT [0.25~2.0, 默認 1.0]
  ● pingPongEnabled ── BOOL [默認 true]
  ● taaHistoryEnabled ── BOOL

輸出：
  ○ fboSpec ── STRUCT
  ○ totalVRAM ── FLOAT （全部 FBO 的 VRAM 用量）
```

#### A2-5: `ViewportLayout` — 視窗佈局

```
輸入：
  ● mode ── ENUM [Single / DualH / DualV / Quad]
  ● mainViewFOV ── FLOAT [30~120, 默認 70]
  ● orthoZoom ── FLOAT [0.1~10.0]

輸出：
  ○ viewportConfig ── STRUCT → BRViewportManager
```

#### A2-6: `VRAMBudget` — VRAM 預算管理

```
輸入：
  ● budgetMB ── INT [128~2048, 默認 512]
  ● evictionPolicy ── ENUM [LRU / Distance / Priority]

輸出：
  ○ usedMB ── FLOAT （即時用量）
  ○ freeMB ── FLOAT
  ○ utilizationPercent ── FLOAT
  ○ warningLevel ── INT
```

#### A2-7: `RenderScale` — 渲染解析度縮放

```
輸入：
  ● scale ── FLOAT [0.25 / 0.5 / 0.75 / 1.0 / 1.25 / 1.5 / 2.0]
  ● upscaleMethod ── ENUM [Nearest / Bilinear / Bicubic / FSR]

輸出：
  ○ internalWidth ── INT
  ○ internalHeight ── INT
  ○ outputWidth ── INT
  ○ outputHeight ── INT
```

#### A2-8: `VertexFormat` — 頂點格式

```
輸入：
  ● compactEnabled ── BOOL [默認 true]
  ● bytesPerVertex ── INT （只讀，16 or 28）

輸出：
  ○ formatSpec ── STRUCT → BRVertexFormat
  ○ bandwidthSavingPercent ── FLOAT
```

---

### A3: 後處理效果節點（18 個）

每個節點都有 `enabled: BOOL` 輸入和 `result: TEXTURE` 輸出。

#### A3-1: `SSAO_GTAO` — 環境遮蔽

```
輸入：
  ● enabled ── BOOL [默認 true]
  ● mode ── ENUM [Basic_SSAO / GTAO]
  ● kernelSize ── INT [4~64, 默認 16]（Basic 模式）
  ● radius ── FLOAT [0.1~5.0, 默認 0.5]（Basic 模式）
  ● gtaoSlices ── INT [1~8, 默認 3]（GTAO 模式）
  ● gtaoStepsPerSlice ── INT [1~16, 默認 4]
  ● gtaoRadius ── FLOAT [0.1~5.0, 默認 1.5]
  ● gtaoFalloff ── FLOAT [0.5~5.0, 默認 2.0]
  ● intensity ── FLOAT [0.0~2.0, 默認 1.0]

輸出：
  ○ aoTexture ── TEXTURE
  ○ gpuTimeMs ── FLOAT
```

**即時預覽**：節點內嵌 64×64 AO map 縮圖，
灰階值越暗表示遮蔽越強。

#### A3-2: `SSR` — 螢幕空間反射

```
輸入：
  ● enabled ── BOOL [默認 true]
  ● maxDistance ── FLOAT [10~200, 默認 50]
  ● maxSteps ── INT [16~256, 默認 64]
  ● binarySteps ── INT [0~16, 默認 8]
  ● thickness ── FLOAT [0.01~1.0, 默認 0.1]
  ● fadeEdge ── FLOAT [0.0~0.5, 默認 0.1]

輸出：
  ○ ssrTexture ── TEXTURE
  ○ hitRate ── FLOAT （射線命中率 %）
```

#### A3-3: `TAA` — 時序抗鋸齒

```
輸入：
  ● enabled ── BOOL [默認 true]
  ● blendFactor ── FLOAT [0.5~0.99, 默認 0.9]
  ● jitterSamples ── INT [4 / 8 / 16 / 32, 默認 16]
  ● velocityWeight ── FLOAT [0.0~1.0]
  ● clampingMode ── ENUM [None / MinMax / Variance]

輸出：
  ○ taaTexture ── TEXTURE
  ○ ghostingMetric ── FLOAT （ghosting 嚴重度）
```

#### A3-4: `Bloom` — 泛光

```
輸入：
  ● threshold ── FLOAT [0.0~5.0, 默認 1.0]
  ● intensity ── FLOAT [0.0~3.0, 默認 1.0]
  ● passes ── INT [1~8, 默認 5]（Kawase blur 次數）
  ● radius ── FLOAT [0.5~5.0, 默認 1.0]
  ● lensDirt ── BOOL [默認 false]
  ● dirtIntensity ── FLOAT [0.0~1.0]

輸出：
  ○ bloomTexture ── TEXTURE
```

#### A3-5: `Tonemap` — 色調映射

```
輸入：
  ● mode ── ENUM [ACES / Reinhard / Uncharted2 / Filmic / None]
  ● exposure ── FLOAT [0.1~10.0, 默認 1.0]
  ● autoExposure ── BOOL [默認 true]
  ● adaptSpeed ── FLOAT [0.1~5.0, 默認 1.5]
  ● minEV ── FLOAT [-4~0, 默認 -2]
  ● maxEV ── FLOAT [4~16, 默認 12]
  ● gamma ── FLOAT [1.8~2.6, 默認 2.2]

輸出：
  ○ tonemappedTexture ── TEXTURE
  ○ currentEV ── FLOAT （當前曝光值，即時）
  ○ avgLuminance ── FLOAT
```

**特殊行為**：當 `autoExposure=true` 時，`exposure` 輸入被覆蓋
（顯示為灰色），改由亮度直方圖自動計算。

#### A3-6 ~ A3-18: 其餘後處理節點（列表）

| # | 節點名稱 | 關鍵輸入 | 來源 |
|---|---------|---------|------|
| A3-6 | `VolumetricLight` | raySteps, fogDensity, scatterStrength | §3.3 Iris |
| A3-7 | `ContactShadow` | maxDist, steps | BRRenderConfig |
| A3-8 | `DOF` | focusDist, aperture, sampleCount | §3.2.2 Radiance |
| A3-9 | `MotionBlur` | intensity, samples, velocityScale | BRMotionBlurEngine |
| A3-10 | `ColorGrading` | lutTexture, intensity, timeOfDay | BRColorGrading |
| A3-11 | `LensFlare` | intensity, ghostCount, dispersal | BRLensFlare |
| A3-12 | `Cinematic` | vignette, chromAb, filmGrain, letterbox | BRRenderConfig |
| A3-13 | `POM` | scale, steps, refinementSteps | §3.2 Radiance |
| A3-14 | `Anisotropic` | roughnessX, roughnessY, metallic | BRAnisotropicReflection |
| A3-15 | `SSS` | scatterRadius, profile, strength | BRSubsurfaceScattering |
| A3-16 | `SSGI` | intensity, radius, samples, temporal | BRGlobalIllumination |
| A3-17 | `VCT_GI` | coneAngle, coneCount, maxDist, resolution | VCT params |
| A3-18 | `WetPBR` | wetness, snowCoverage, puddleIntensity | BRWeatherEngine |

---

### A4: 光照與陰影節點（7 個）

#### A4-1: `SunLight` — 主光源

```
輸入：
  ● angle ── FLOAT [0~360] （時間角）
  ● color ── COLOR [默認 warm white]
  ● intensity ── FLOAT [0~5.0, 默認 1.0]
  ● shadowBias ── FLOAT [0.0001~0.01]

輸出：
  ○ lightDir ── VEC3
  ○ lightColor ── VEC3
  ○ shadowMatrix ── STRUCT
```

#### A4-2: `AmbientLight` — 環境光

```
輸入：
  ● skyColor ── COLOR
  ● groundColor ── COLOR
  ● intensity ── FLOAT [0~2.0]
  ● aoStrength ── FLOAT [0~1.0]

輸出：
  ○ ambientSpec ── STRUCT
```

#### A4-3 ~ A4-7:

| # | 節點 | 用途 |
|---|------|------|
| A4-3 | `CSM_Cascade` | 級聯陰影參數（splits, blending） |
| A4-4 | `PointLight` | 動態點光源（火把、岩漿） |
| A4-5 | `AreaLight` | 面光源（螢光石、海燈籠） |
| A4-6 | `EmissiveBlock` | 自發光方塊設定 |
| A4-7 | `LightProbe` | 光照探針（未來 Tier 3） |

---

### A5: LOD 與優化節點（9 個）

#### A5-1: `LODConfig` — LOD 總控

```
輸入：
  ● maxDistance ── FLOAT [64~1024, 默認 1024]
  ● levelCount ── INT [3~5, 默認 5]
  ● hysteresis ── FLOAT [0~32, 默認 8]
  ● transitionBand ── FLOAT [0~32, 默認 8]（SMOOTH_DROPOFF 帶寬）
  ● fogMatchEnabled ── BOOL [默認 true]（霧色匹配過渡）

輸出：
  ○ lodSpec ── STRUCT
  ○ visibleSections ── INT
  ○ totalVRAM ── FLOAT
```

#### A5-2: `LODLevel` — 單一 LOD 等級

```
輸入：
  ● levelIndex ── INT [0~4]
  ● maxDistance ── FLOAT
  ● geometryRetention ── FLOAT [0.01~1.0]
  ● voxelScale ── INT [1/2/4/8/16]
  ● useSVDAG ── BOOL [默認 false]

輸出：
  ○ levelConfig ── STRUCT
  ○ compressionRatio ── FLOAT
```

#### A5-3 ~ A5-9:

| # | 節點 | 用途 |
|---|------|------|
| A5-3 | `FrustumCuller` | 視錐體裁剪設定 |
| A5-4 | `OcclusionCuller` | 遮蔽剔除（query count, timeout） |
| A5-5 | `HiZConfig` | Hi-Z 金字塔設定 |
| A5-6 | `GreedyMesh` | 貪心合併設定（maxArea, cache） |
| A5-7 | `MeshCache` | VBO 快取（maxSections, eviction） |
| A5-8 | `IndirectDraw` | GPU indirect draw 設定 |
| A5-9 | `BatchRender` | 批次渲染（glMultiDraw） |

---

### A6: 天氣與大氣節點（6 個）

| # | 節點 | 關鍵輸入 |
|---|------|---------|
| A6-1 | `Atmosphere` | Rayleigh, Mie, sunAngle, turbidity |
| A6-2 | `Cloud` | bottomHeight, thickness, coverage, density |
| A6-3 | `Fog` | density, heightFalloff, inscattering |
| A6-4 | `Rain` | dropsPerTick, puddleIntensity, splashSize |
| A6-5 | `Snow` | flakesPerTick, coverage, meltRate |
| A6-6 | `Aurora` | intensity, height, waveSpeed, color1, color2 |

### A7: 水體節點（4 個）

| # | 節點 | 關鍵輸入 |
|---|------|---------|
| A7-1 | `WaterSurface` | level, reflectionScale, waveCount, waveAmplitude |
| A7-2 | `WaterCaustics` | intensity, speed, scale |
| A7-3 | `WaterFoam` | threshold, fadeSpeed, color |
| A7-4 | `Underwater` | fogDensity, tintColor, causticIntensity |

---

## 6. Category B: 材料與方塊特性節點

### B1: 基礎材料節點（14 個）

每種 DefaultMaterial 對應一個預設節點。

#### B1-1: `MaterialConstant` — 材料常數

```
輸入：
  ● materialType ── ENUM [12 種 DefaultMaterial]

輸出：
  ○ material ── MATERIAL
  ○ rcomp ── FLOAT （抗壓 MPa）
  ○ rtens ── FLOAT （抗拉 MPa）
  ○ rshear ── FLOAT （抗剪 MPa）
  ○ density ── FLOAT （密度 kg/m³）
  ○ youngsModulus ── FLOAT （楊氏模量 GPa）
  ○ poissonsRatio ── FLOAT （泊松比）
  ○ yieldStrength ── FLOAT （屈服強度 MPa）
  ○ maxSpan ── INT （最大懸臂 blocks）
```

選擇材料後，所有輸出端口自動填入對應工程常數。
Grasshopper 類比：**Panel with preset data**。

#### B1-2: `CustomMaterial` — 自定義材料

```
輸入：
  ● materialId ── STRING [用戶自訂名稱]
  ● rcomp ── FLOAT [0~10000, 滑桿 + 數值框]
  ● rtens ── FLOAT [0~10000]
  ● rshear ── FLOAT [0~10000]
  ● density ── FLOAT [100~10000]
  ● youngsModulus ── FLOAT [0.001~1000 GPa]
  ● poissonsRatio ── FLOAT [0.0~0.499]

輸出：
  ○ material ── MATERIAL
  ○ validation ── BOOL （是否通過驗證）
  ○ warnings ── STRING （驗證警告訊息）
```

### B2: 材料運算節點（8 個）

#### B2-1: `RCFusion` — 鋼筋混凝土融合

**Grasshopper 類比**：數學運算節點（如 Multiply + Addition）

```
輸入：
  ● concrete ── MATERIAL [混凝土材料]
  ● rebar ── MATERIAL [鋼筋材料]
  ● phiTens ── FLOAT [0.0~2.0, 默認 0.8]（抗拉增強係數）
  ● phiShear ── FLOAT [0.0~2.0, 默認 0.6]（抗剪增強係數）
  ● compBoost ── FLOAT [1.0~3.0, 默認 1.1]（抗壓提升）
  ● hasHoneycomb ── BOOL [默認 false]（蜂巢缺陷？）
  ● rebarSpacing ── INT [1~8]（鋼筋間距）

輸出：
  ○ rcMaterial ── MATERIAL （融合後的 RC 材料）
  ○ rcRcomp ── FLOAT
  ○ rcRtens ── FLOAT
  ○ rcRshear ── FLOAT
  ○ strengthGainPercent ── FLOAT （強度提升 %）
```

**公式**（節點內嵌公式預覽）：
```
R_RC_comp  = R_concrete × compBoost
R_RC_tens  = R_concrete_tens + R_rebar_tens × φ_tens
R_RC_shear = R_concrete_shear + R_rebar_shear × φ_shear
if honeycomb: all × 0.7
```

#### B2-2: `MaterialMixer` — 材料混合

```
輸入：
  ● materialA ── MATERIAL
  ● materialB ── MATERIAL
  ● ratio ── FLOAT [0.0~1.0]（A 的佔比）
  ● mixMode ── ENUM [WeightedAvg / Max / Min]

輸出：
  ○ mixed ── MATERIAL
```

#### B2-3: `MaterialScaler` — 材料縮放

```
輸入：
  ● material ── MATERIAL
  ● compScale ── FLOAT [0.1~10.0]
  ● tensScale ── FLOAT [0.1~10.0]
  ● shearScale ── FLOAT [0.1~10.0]
  ● densityScale ── FLOAT [0.1~10.0]

輸出：
  ○ scaled ── MATERIAL
```

#### B2-4: `CuringProcess` — 養護過程

```
輸入：
  ● baseMaterial ── MATERIAL
  ● curingTicks ── INT [0~72000]
  ● currentTick ── INT （當前養護時間）
  ● ambientTemp ── FLOAT [0~50°C]

輸出：
  ○ curedMaterial ── MATERIAL
  ○ curingProgress ── FLOAT [0~1.0]
  ○ strengthPercent ── FLOAT （目前強度 vs 完全養護）
```

**養護曲線**：強度隨時間的對數增長，
節點內嵌顯示養護進度曲線圖。

#### B2-5 ~ B2-8:

| # | 節點 | 用途 |
|---|------|------|
| B2-5 | `WeatherDegradation` | 風化劣化（降低材料強度） |
| B2-6 | `FireResistance` | 耐火等級計算 |
| B2-7 | `MaterialCompare` | 兩種材料的屬性比較（雷達圖） |
| B2-8 | `MaterialLookup` | 從 block ID 查詢對應材料 |

### B3: 方塊形狀節點（6 個）

#### B3-1: `ShapeSelector` — 形狀選擇

```
輸入：
  ● shape ── ENUM [14 種 SubBlockShape]

輸出：
  ○ shape ── SHAPE
  ○ fillRatio ── FLOAT
  ○ crossSectionArea ── FLOAT （A, m²）
  ○ momentOfInertiaX ── FLOAT （Ix, m⁴）
  ○ momentOfInertiaY ── FLOAT （Iy, m⁴）
  ○ sectionModulusX ── FLOAT （Wx, m³）
  ○ sectionModulusY ── FLOAT （Wy, m³）
  ○ voxelGrid ── STRUCT （10³ 體素資料）
```

**即時預覽**：節點內嵌 3D 等角投影的形狀預覽，
可旋轉查看截面形狀。

#### B3-2: `CustomShape` — 自訂形狀

```
輸入：
  ● voxelEditor ── STRUCT （內嵌 10³ 體素編輯器）
  ● autoCalcProperties ── BOOL [默認 true]

輸出：
  ○ shape ── SHAPE
  ○ properties ── STRUCT （自動計算的截面特性）
```

#### B3-3 ~ B3-6:

| # | 節點 | 用途 |
|---|------|------|
| B3-3 | `ShapeCombine` | 布林合併兩個形狀（Union/Subtract/Intersect） |
| B3-4 | `ShapeRotate` | 旋轉形狀 (90°×N) |
| B3-5 | `ShapeMirror` | 鏡像形狀 |
| B3-6 | `ShapeToMesh` | 形狀→網格（預覽渲染用） |

### B4: 材料視覺化節點（4 個）

| # | 節點 | 用途 |
|---|------|------|
| B4-1 | `MaterialRadarChart` | 6 軸雷達圖（comp/tens/shear/density/E/ν） |
| B4-2 | `StressStrainCurve` | 應力-應變曲線繪製 |
| B4-3 | `MaterialPalette` | 材料色板（依強度著色） |
| B4-4 | `BlockPropertyTable` | 方塊屬性表格匯出 |

---

### B5: 材質調配節點（8 個）— v1.1 新增

**設計理念**：讓玩家在節點編輯器中以任何 Minecraft 基礎方塊為起點，
直覺地調整工程數值（強度、密度等），創建出全新的自訂方塊。
整條調配管線為：**選取基礎方塊 → 調整數值 → 預覽驗證 → 輸出註冊**。

**Grasshopper 類比**：參數化設計的「取樣→變形→烘焙」流程。

**與 B1/B2 的區別**：B1/B2 操作的是抽象的 `MATERIAL`（純數值），
B5 操作的是完整的 `BLOCK`（包含 blockId、material、texture、blockstate），
最終產出可直接放置於世界中的方塊實體。

#### B5-1: `VanillaBlockPicker` — 原版方塊選取器

```
輸入：
  ● blockId ── STRING [內嵌搜尋式方塊選取器]
    支持：
    - 文字搜尋（"oak" → oak_planks, oak_log, ...）
    - 分類瀏覽（木材 / 石材 / 金屬 / 混凝土 / 玻璃 / 砂土 / 黑曜石）
    - 最近使用清單
  ● showVanillaStats ── BOOL [默認 true]

輸出：
  ○ block ── BLOCK （完整方塊定義：blockId + material + texture ref）
  ○ baseMaterial ── MATERIAL （該方塊的原始 RMaterial，由 VanillaMaterialMap 查詢）
  ○ rcomp ── FLOAT （原始抗壓 MPa）
  ○ rtens ── FLOAT （原始抗拉 MPa）
  ○ rshear ── FLOAT （原始抗剪 MPa）
  ○ density ── FLOAT （原始密度 kg/m³）
  ○ youngsModulus ── FLOAT （原始楊氏模量 GPa）
  ○ categoryTag ── STRING （VanillaMaterialMap 分類名）
```

**節點內嵌 UI**：
```
┌─────────────────────────────────────────┐
│  ▣ 原版方塊選取器              [×]      │
├─────────────────────────────────────────┤
│  🔍 搜尋方塊...  [oak_planks       ]   │
│                                         │
│  ┌──────┐  minecraft:oak_planks         │
│  │      │  材料: TIMBER                 │
│  │  🪵  │  抗壓: 5.0 MPa               │
│  │      │  抗拉: 8.0 MPa               │
│  └──────┘  密度: 500 kg/m³             │
│            楊氏模量: 11.0 GPa          │
├─────────────────────────────────────────┤
│ block ──────────── BLOCK           ●    │
│ baseMaterial ───── MATERIAL        ●    │
│ rcomp ──────────── 5.0      float  ●    │
│ rtens ──────────── 8.0      float  ●    │
│ rshear ─────────── 1.5      float  ●    │
│ density ────────── 500      float  ●    │
│ youngsModulus ──── 11.0     float  ●    │
└─────────────────────────────────────────┘
```

**Runtime 對接**：讀取 `VanillaMaterialMap.getInstance().getMaterial(blockId)`
取得 `DefaultMaterial`，再將其所有工程數值展開到輸出端口。

---

#### B5-2: `PropertyTuner` — 數值微調器

**核心節點**：接收基礎方塊，逐項覆寫工程數值，輸出調整後的材料。
未連接的數值端口保持原始值（pass-through）。

```
輸入：
  ● baseMaterial ── MATERIAL [來自 VanillaBlockPicker 或其他材料節點]
  ● overrideRcomp ── BOOL [默認 false]
  ● rcomp ── FLOAT [0~10000, 滑桿]（僅 overrideRcomp=true 時生效）
  ● overrideRtens ── BOOL [默認 false]
  ● rtens ── FLOAT [0~10000]
  ● overrideRshear ── BOOL [默認 false]
  ● rshear ── FLOAT [0~10000]
  ● overrideDensity ── BOOL [默認 false]
  ● density ── FLOAT [100~10000]
  ● overrideYoungs ── BOOL [默認 false]
  ● youngsModulus ── FLOAT [0.001~1000 GPa]
  ● overridePoissons ── BOOL [默認 false]
  ● poissonsRatio ── FLOAT [0.0~0.499]

輸出：
  ○ tunedMaterial ── MATERIAL （調整後材料）
  ○ deltaReport ── STRING （與原始值的差異摘要）
  ○ validation ── BOOL （是否合理？）
  ○ warnings ── STRING
```

**節點內嵌 UI**：每行顯示原始值 → 新值的對比色條。
覆寫的項目以亮綠色標示，未覆寫的以灰色顯示。

**驗證規則**：
- `rcomp < rtens × 0.1` → 警告「抗壓遠低於抗拉，不符常見材料特性」
- `density < 100 或 > 10000` → 警告「密度超出已知材料範圍」
- `poissonsRatio ≥ 0.5` → 錯誤「泊松比必須小於 0.5」
- `youngsModulus < 0.001` → 錯誤「楊氏模量過低」

---

#### B5-3: `BlockBlender` — 方塊混合器

**Grasshopper 類比**：Lerp / Blend 節點

以兩個基礎方塊為輸入，按比例混合其工程數值，產出一個新材料。
用於快速創建「介於兩種材料之間」的自訂方塊——
例如 70% 石材 + 30% 鋼材 = 鋼石複合材。

```
輸入：
  ● blockA ── BLOCK [基礎方塊 A]
  ● blockB ── BLOCK [基礎方塊 B]
  ● ratio ── FLOAT [0.0~1.0, 默認 0.5]（A 的佔比，滑桿）
  ● mixMode ── ENUM [Linear / Geometric / Max / Min]
  ● textureSource ── ENUM [BlockA / BlockB / Checkerboard / Custom]

輸出：
  ○ blendedMaterial ── MATERIAL
  ○ blendedBlock ── BLOCK （含混合紋理引用）
  ○ rcomp ── FLOAT （混合後抗壓）
  ○ rtens ── FLOAT （混合後抗拉）
  ○ rshear ── FLOAT （混合後抗剪）
  ○ density ── FLOAT （混合後密度）
```

**混合公式**：
```
Linear:     V_out = V_A × ratio + V_B × (1 - ratio)
Geometric:  V_out = V_A^ratio × V_B^(1-ratio)
Max:        V_out = max(V_A, V_B)
Min:        V_out = min(V_A, V_B)
```

**節點內嵌 UI**：水平漸變條顯示混合比例，
左端標 Block A 縮圖，右端標 Block B 縮圖，
拖曳滑桿時漸變色即時變化。

---

#### B5-4: `BlockCreator` — 自訂方塊生成器

**管線終端**：將調配完成的材料封裝為一個可在世界中放置的自訂方塊。
這是調配流程的最終輸出節點。

```
輸入：
  ● material ── MATERIAL [調配/混合後的材料]
  ● blockName ── STRING [用戶自訂名稱, 如 "reinforced_oak"]
  ● displayName ── STRING [遊戲內顯示名, 如 "強化橡木"]
  ● baseTexture ── BLOCK [繼承哪個方塊的材質外觀]
  ● tintColor ── COLOR [默認 #FFFFFF, 無色調]（可選色調疊加）
  ● tintIntensity ── FLOAT [0.0~1.0, 默認 0.0]
  ● creativeTab ── ENUM [BlockReality / Building / Decoration]
  ● shape ── SHAPE [默認 FULL_BLOCK]（來自 B3 形狀節點）
  ● autoRegister ── BOOL [默認 true]（自動註冊到遊戲）

輸出：
  ○ customBlock ── BLOCK （完整的自訂方塊定義）
  ○ registryId ── STRING （"blockreality:reinforced_oak"）
  ○ nbtData ── STRUCT （方塊 NBT 序列化資料）
  ○ isValid ── BOOL
```

**行為**：
1. 驗證 material 數值合理性（同 PropertyTuner 驗證規則）
2. 生成 `blockreality:<blockName>` 的 registry ID
3. 調用 `CustomMaterial.builder(blockName).rcomp(...).rtens(...)....build()`
4. 在 `BlockTypeRegistry` 註冊新方塊（如 `autoRegister=true`）
5. 為 baseTexture 方塊的材質加上 tintColor 色調作為外觀
6. 輸出可被 D2 放置工具節點直接使用的 BLOCK 物件

**名稱衝突處理**：若 `blockName` 已存在，輸出 `isValid=false`
並在 warnings 顯示「名稱已被佔用，請更改」。

---

#### B5-5: `BlockPreview3D` — 方塊即時預覽

```
輸入：
  ● block ── BLOCK [來自 BlockCreator 或 VanillaBlockPicker]
  ● showStressOverlay ── BOOL [默認 false]（物理應力疊加層）
  ● rotateSpeed ── FLOAT [0~2.0, 默認 0.5]（自動旋轉速度，0=靜止）
  ● showGrid ── BOOL [默認 true]（底部格線）
  ● compareWith ── BLOCK [可選，用於 A/B 對照]

輸出：
  ○ previewTexture ── TEXTURE （64×64 預覽縮圖）
```

**節點內嵌 UI**：
```
┌─────────────────────────────────────────┐
│  ▣ 方塊即時預覽                  [×]    │
├─────────────────────────────────────────┤
│                                         │
│   ┌───────────────────────────────┐     │
│   │                               │     │
│   │   [3D 等角投影旋轉預覽]      │     │
│   │   自訂方塊 vs 原版對比        │     │
│   │   128×128 互動式              │     │
│   │                               │     │
│   └───────────────────────────────┘     │
│                                         │
│   reinforced_oak                        │
│   抗壓: 15.0 MPa (+200%)               │
│   抗拉: 16.0 MPa (+100%)               │
│   密度: 650 kg/m³ (+30%)               │
│                                         │
├─────────────────────────────────────────┤
│ previewTexture ─── TEXTURE         ●    │
└─────────────────────────────────────────┘
```

若 `compareWith` 已連接，預覽區域分割為左右兩半：
左側為自訂方塊，右側為對比方塊，並在下方顯示差異百分比。

---

#### B5-6: `BatchBlockFactory` — 批量方塊工廠

一次性從同一基礎方塊派生多個變體。
例如：以 `oak_planks` 為基底，一次產出強度 ×1.5、×2.0、×3.0 三種變體。

```
輸入：
  ● baseBlock ── BLOCK [基礎方塊]
  ● variants ── INT [2~8, 默認 3]（變體數量）
  ● scaleProperty ── ENUM [Rcomp / Rtens / Rshear / Density / All]
  ● scaleMin ── FLOAT [0.5~1.0, 默認 0.5]
  ● scaleMax ── FLOAT [1.0~10.0, 默認 3.0]
  ● distribution ── ENUM [Linear / Exponential / Custom]
  ● nameSuffix ── STRING [默認 "_tier_{n}"]（{n} 自動替換為 1,2,3...）

輸出：
  ○ blocks ── BLOCK[] （所有變體的陣列）
  ○ count ── INT （實際產出數量）
  ○ summaryTable ── STRING （所有變體的屬性摘要表）
```

**節點內嵌 UI**：表格顯示每個變體的名稱和關鍵數值，
色階從綠（最弱）到紅（最強），一目了然。

```
範例：baseBlock = oak_planks, scaleProperty = Rcomp, variants = 4
┌──────────────────────────────────┐
│  Tier  │  名稱              │ Rcomp │
│  1     │ oak_planks_tier_1  │  2.5  │
│  2     │ oak_planks_tier_2  │  5.0  │
│  3     │ oak_planks_tier_3  │  7.5  │
│  4     │ oak_planks_tier_4  │ 10.0  │
└──────────────────────────────────┘
```

---

#### B5-7: `RecipeAssigner` — 合成配方指派

為 BlockCreator 產出的自訂方塊指定合成配方，
讓玩家在生存模式中可以合成取得。

```
輸入：
  ● customBlock ── BLOCK [來自 BlockCreator]
  ● recipeType ── ENUM [Shaped / Shapeless / Smelting / Stonecutting / None]
  ● ingredients ── BLOCK[1~9] [合成材料, 內嵌 3×3 網格編輯器]
  ● outputCount ── INT [1~64, 默認 1]
  ● unlockAdvancement ── STRING [可選, 解鎖條件]

輸出：
  ○ recipeJson ── STRING （data pack 格式的 JSON 配方）
  ○ isValid ── BOOL （配方是否合法）
```

**節點內嵌 UI**（Shaped 模式）：
```
┌─────────────────────────────────────────┐
│  ▣ 合成配方指派                  [×]    │
├─────────────────────────────────────────┤
│  模式: [▼ Shaped]                       │
│                                         │
│  合成網格:              輸出:           │
│  ┌───┬───┬───┐         ┌───┐           │
│  │ 🪵│ 🔩│ 🪵│   →    │ 🟩│ ×4        │
│  ├───┼───┼───┤         │強化│           │
│  │   │ 🪵│   │         │橡木│           │
│  ├───┼───┼───┤         └───┘           │
│  │   │   │   │                          │
│  └───┴───┴───┘                          │
│  🔩 = iron_nugget                       │
│  🪵 = oak_planks                        │
├─────────────────────────────────────────┤
│ recipeJson ────── STRING           ●    │
│ isValid ─────── true   BOOL       ●    │
└─────────────────────────────────────────┘
```

**Smelting 模式**：簡化為單一輸入 + 燒煉時間 + 經驗值。
**None 模式**：不產生配方（僅創造模式可用）。

---

#### B5-8: `BlockLibrary` — 自訂方塊庫

管理所有已創建的自訂方塊。提供匯入/匯出、搜尋、刪除功能。
可將調配方案保存為 `.brblock` 檔案分享給其他玩家。

```
輸入：
  ● importFile ── STRING [.brblock 檔案路徑]

輸出：
  ○ allBlocks ── BLOCK[] （庫中所有自訂方塊）
  ○ count ── INT
  ○ totalDiskSizeKB ── FLOAT
```

**節點內嵌 UI**：
```
┌──────────────────────────────────────────┐
│  ▣ 自訂方塊庫                     [×]   │
├──────────────────────────────────────────┤
│  🔍 搜尋...                              │
│                                          │
│  ┌────┬──────────────────┬───────┬────┐  │
│  │ 🟩 │ reinforced_oak   │ 15MPa │ [×]│  │
│  ├────┼──────────────────┼───────┼────┤  │
│  │ 🟦 │ steel_glass      │200MPa │ [×]│  │
│  ├────┼──────────────────┼───────┼────┤  │
│  │ 🟫 │ dense_brick_v2   │ 18MPa │ [×]│  │
│  └────┴──────────────────┴───────┴────┘  │
│                                          │
│  [匯入 .brblock] [匯出全部] [清空庫]    │
│                                          │
│  共 3 個自訂方塊 | 12.4 KB               │
├──────────────────────────────────────────┤
│ allBlocks ────── BLOCK[]           ●     │
│ count ────────── 3         INT     ●     │
└──────────────────────────────────────────┘
```

**儲存格式**（`.brblock`）：
```json
{
  "version": "1.1",
  "blocks": [
    {
      "name": "reinforced_oak",
      "displayName": "強化橡木",
      "baseBlockId": "minecraft:oak_planks",
      "material": {
        "rcomp": 15.0, "rtens": 16.0, "rshear": 4.5,
        "density": 650.0, "youngsModulus": 15.0, "poissonsRatio": 0.35
      },
      "tintColor": "#FFFFFF",
      "tintIntensity": 0.0,
      "shape": "FULL_BLOCK",
      "recipe": { "type": "shaped", "pattern": ["ABA","_A_"], "key": {"A":"oak_planks","B":"iron_nugget"}, "count": 4 }
    }
  ]
}
```

---

### B5 完整調配流程範例

#### 範例 1：「強化橡木」— 基礎微調

```
[VanillaBlockPicker]──oak_planks──→[PropertyTuner]──→[BlockCreator]──→[BlockPreview3D]
  (TIMBER: 5MPa comp)              override rcomp=15     "reinforced_oak"
                                   override rtens=16      tint=#D4A373
                                   override density=650
                                                    ↓
                                              [RecipeAssigner]
                                                Shaped: 🪵🔩🪵 / _🪵_ → ×4
```

#### 範例 2：「鋼石複合板」— 雙方塊混合

```
[VanillaBlockPicker]──stone_bricks──→[BlockBlender]──→[BlockCreator]──→[BlockLibrary]
                                         ratio=0.7       "steel_stone"
[VanillaBlockPicker]──iron_block────→[BlockBlender]     texture=Checkerboard
                                         mode=Geometric
```

#### 範例 3：「橡木強度系列」— 批量生成

```
[VanillaBlockPicker]──oak_planks──→[BatchBlockFactory]──→[BlockLibrary]
                                     variants=5            匯出 oak_series.brblock
                                     scaleProperty=All
                                     scaleMin=0.5
                                     scaleMax=3.0
                                     suffix="_grade_{n}"
```

---

## 7. Category C: 物理計算節點

### C1: 求解器設定節點（6 個）

#### C1-1: `ForceEquilibrium` — 力平衡求解器

```
輸入：
  ● enabled ── BOOL [默認 true]
  ● maxIterations ── INT [10~500, 默認 100]
  ● convergenceThreshold ── FLOAT [0.0001~0.1, 默認 0.001]
  ● absoluteFloor ── FLOAT [0.001~1.0, 默認 0.01]
  ● omega ── FLOAT [1.0~1.95, 默認 1.25]（SOR 鬆弛因子）
  ● autoOmega ── BOOL [默認 true]（自適應 ω）
  ● omegaMin ── FLOAT [1.0~1.5]
  ● omegaMax ── FLOAT [1.5~1.95]
  ● warmStartEntries ── INT [0~256, 默認 64]

輸出：
  ○ solverSpec ── STRUCT → ForceEquilibriumSolver
  ○ convergenceRate ── FLOAT （即時收斂率）
  ○ iterationsUsed ── INT
  ○ residual ── FLOAT
```

**即時預覽**：收斂曲線圖（iteration vs residual），
曲線應單調下降，若震盪則 ω 過大。

#### C1-2: `BeamAnalysis` — 梁分析引擎

```
輸入：
  ● enabled ── BOOL [默認 true]
  ● maxBlocks ── INT [64~5000, 默認 500]
  ● gravity ── FLOAT [默認 9.81]
  ● asyncTimeout ── INT [1000~30000ms, 默認 5000]

輸出：
  ○ analysisSpec ── STRUCT → BeamStressEngine
  ○ beamsAnalyzed ── INT
  ○ failedBeams ── INT
  ○ maxUtilization ── FLOAT
```

#### C1-3: `SupportPath` — 支撐路徑分析

```
輸入：
  ● bfsMaxBlocks ── INT [64~72M, 默認 500K]
  ● bfsMaxMs ── INT [5~2000, 默認 200]
  ● gravity ── FLOAT [9.81]
  ● blockSectionModulus ── FLOAT [默認 1/6]

輸出：
  ○ analysisSpec ── STRUCT → SupportPathAnalyzer
  ○ stableCount ── INT
  ○ failedCount ── INT
  ○ maxMoment ── FLOAT
```

#### C1-4: `CoarseFEM` — 粗粒度 FEM

```
輸入：
  ● maxIterations ── INT [10~200, 默認 50]
  ● convergenceThreshold ── FLOAT [0.001~0.1, 默認 0.005]
  ● omega ── FLOAT [1.0~1.9, 默認 1.4]
  ● lateralFraction ── FLOAT [0.0~0.5, 默認 0.15]
  ● interval ── INT [5~200 ticks, 默認 20]

輸出：
  ○ femSpec ── STRUCT → CoarseFEMEngine
  ○ sectionsAnalyzed ── INT
  ○ avgStress ── FLOAT
```

#### C1-5: `PhysicsLOD` — 物理精度分層

```
輸入：
  ● fullPrecisionDist ── INT [8~128, 默認 32]
  ● standardDist ── INT [32~256, 默認 96]
  ● coarseDist ── INT [96~512, 默認 256]

輸出：
  ○ physicsLodSpec ── STRUCT
```

**視覺化**：以同心圓顯示三個精度等級的範圍，
Full（紅）→ Standard（黃）→ Coarse（藍）。

#### C1-6: `SpatialPartition` — 空間分割並行

```
輸入：
  ● threadCount ── INT [1~16, 默認 CPU-1]
  ● asyncMode ── BOOL [默認 true]
  ● timeoutMs ── INT [100~5000]

輸出：
  ○ executorSpec ── STRUCT → SpatialPartitionExecutor
  ○ activePartitions ── INT
  ○ avgPartitionTimeMs ── FLOAT
```

### C2: 荷載與力節點（7 個）

#### C2-1: `Gravity` — 重力設定

```
輸入：
  ● g ── FLOAT [0~20, 默認 9.81]
  ● direction ── VEC3 [默認 (0, -1, 0)]

輸出：
  ○ gravityVec ── VEC3 （g × direction）
  ○ gravityMagnitude ── FLOAT
```

#### C2-2: `DistributedLoad` — 分布荷載

```
輸入：
  ● magnitude ── FLOAT [N/m]
  ● direction ── ENUM [Down / Up / North / South / East / West]
  ● area ── FLOAT [m²]

輸出：
  ○ totalForce ── FLOAT [N]
  ○ loadVector ── VEC3
```

#### C2-3: `ConcentratedLoad` — 集中荷載

```
輸入：
  ● force ── FLOAT [N]
  ● position ── VEC3 [world coordinates]
  ● direction ── VEC3

輸出：
  ○ loadSpec ── STRUCT
```

#### C2-4: `MomentCalculator` — 力矩計算

```
輸入：
  ● loads ── STRUCT[] （多個荷載輸入）
  ● pivotPoint ── VEC3 （力矩基準點）

輸出：
  ○ moment ── FLOAT [N·m]
  ○ momentVector ── VEC3
```

**公式顯示**：M = Σ(F_i × d_i)

#### C2-5 ~ C2-7:

| # | 節點 | 用途 |
|---|------|------|
| C2-5 | `WindLoad` | 風荷載（速度→壓力→力） |
| C2-6 | `SeismicLoad` | 地震荷載（未來功能） |
| C2-7 | `ThermalLoad` | 熱膨脹荷載（未來功能） |

### C3: 分析結果節點（5 個）

#### C3-1: `StressVisualizer` — 應力視覺化

```
輸入：
  ● stressField ── STRUCT （來自求解器）
  ● colorMap ── ENUM [Rainbow / BlueRed / Grayscale / Custom]
  ● minStress ── FLOAT （色階下限）
  ● maxStress ── FLOAT （色階上限）
  ● showFailures ── BOOL [默認 true]

輸出：
  ○ heatmapTexture ── TEXTURE → StressHeatmapRenderer
  ○ maxStressValue ── FLOAT
  ○ failureCount ── INT
```

**即時預覽**：節點內嵌色階圖 + 統計數據。

#### C3-2: `LoadPathVisualizer` — 荷載路徑視覺化

```
輸入：
  ● loadPaths ── STRUCT （來自 LoadPathEngine）
  ● showArrows ── BOOL
  ● lineWidth ── FLOAT [1~5px]
  ● colorByMagnitude ── BOOL

輸出：
  ○ pathRenderData ── STRUCT → AnchorPathRenderer
```

#### C3-3 ~ C3-5:

| # | 節點 | 用途 |
|---|------|------|
| C3-3 | `DeflectionMap` | 變形量分佈圖 |
| C3-4 | `UtilizationReport` | 利用率報告（表格 + 圖表） |
| C3-5 | `StructuralScore` | 結構健康度評分（0~100） |

### C4: 崩塌與破壞節點（4 個）

| # | 節點 | 輸入 | 輸出 |
|---|------|------|------|
| C4-1 | `CollapseConfig` | maxQueueSize, blocksPerTick, cascadeEnabled | collapseSpec |
| C4-2 | `FailureMode` | type(CANTILEVER/CRUSHING/NO_SUPPORT), threshold | failureSpec |
| C4-3 | `CableConstraint` | xpbdIterations, compliance, damping | cableSpec |
| C4-4 | `BreakPattern` | fragmentCount, debrisPhysics, soundEnabled | breakSpec |

---

## 8. Category D: 工具與 UI 節點

### D1: 選取工具節點（7 個）

#### D1-1: `SelectionConfig` — 選取設定

```
輸入：
  ● tool ── ENUM [Box / MagicWand / Lasso / Brush / Face]
  ● booleanMode ── ENUM [Replace / Union / Intersect / Subtract]
  ● maxBlocks ── INT [1K~1M, 默認 1M]
  ● undoDepth ── INT [1~100, 默認 32]

輸出：
  ○ selectionSpec ── STRUCT → BRSelectionEngine
  ○ currentCount ── INT
```

#### D1-2: `BrushConfig` — 筆刷設定

```
輸入：
  ● shape ── ENUM [Sphere / Cylinder / Cube]
  ● radius ── INT [1~64, 默認 3]
  ● scrollWheelEnabled ── BOOL [默認 true]

輸出：
  ○ brushSpec ── STRUCT
```

#### D1-3: `SelectionFilter` — 選取過濾器

```
輸入：
  ● maskConfig ── STRUCT （來自 ToolMask 節點）
  ● predicateChain ── STRUCT （來自 CompoundPredicate 節點）

輸出：
  ○ filteredSelection ── STRUCT
  ○ filteredCount ── INT
```

#### D1-4 ~ D1-7:

| # | 節點 | 用途 |
|---|------|------|
| D1-4 | `ToolMask` | 遮罩設定（blockId/tag/yRange/solid/surface） |
| D1-5 | `CompoundPredicate` | AND/OR/NOT 邏輯組合 |
| D1-6 | `SelectionViz` | 選取視覺效果（glowPeriod, alpha, pulseSpeed） |
| D1-7 | `SelectionExport` | 匯出選取為 NBT/Blueprint |

### D2: 放置工具節點（5 個）

| # | 節點 | 輸入 | 輸出 |
|---|------|------|------|
| D2-1 | `BuildMode` | mode(SINGLE/LINE/PLANE/VOLUME), material | buildSpec |
| D2-2 | `BatchOp` | op(FILL/REPLACE/HOLLOW/WALLS), undoDepth | batchSpec |
| D2-3 | `QuickPlacer` | brushRadius, faceExtendEnabled, scrollWheel | placerSpec |
| D2-4 | `BlueprintPlace` | snapMode, rotation, mirror, ghostAlpha | previewSpec |
| D2-5 | `GhostBlock` | alpha, breatheAmp, scanSpeed, collisionColor | ghostSpec |

### D3: UI 外觀節點（6 個）

#### D3-1: `RadialMenu` — 徑向菜單設定

```
輸入：
  ● sectorCount ── INT [3~12, 默認 8]
  ● activationKey ── INT [GLFW keycode]
  ● openDurationMs ── INT [50~500, 默認 150]
  ● deadZoneRatio ── FLOAT [0.1~0.5, 默認 0.2]
  ● innerRadiusRatio ── FLOAT [0.1~0.4]
  ● outerRadiusRatio ── FLOAT [0.3~0.6]
  ● easing ── ENUM [Linear / CubicOut / CubicInOut / Bounce]
  ● gamepadEnabled ── BOOL [默認 true]
  ● gamepadDeadzone ── FLOAT [0.1~0.5, 默認 0.2]
  ● highlightColor ── COLOR [默認 #FFCC00]
  ● backgroundColor ── COLOR [默認 #AA000000]

輸出：
  ○ menuConfig ── STRUCT → BRRadialMenu
```

#### D3-2 ~ D3-6:

| # | 節點 | 用途 |
|---|------|------|
| D3-2 | `HologramStyle` | 全息圖外觀（alpha, cullDist, cornerMarks） |
| D3-3 | `HUDLayout` | HUD 元素位置和大小 |
| D3-4 | `ThemeColor` | 全域 UI 色彩主題 |
| D3-5 | `FontConfig` | 字型大小、中英混排 |
| D3-6 | `Crosshair` | 準心樣式和大小 |

### D4: 輸入映射節點（4 個）

| # | 節點 | 用途 |
|---|------|------|
| D4-1 | `KeyBindings` | 鍵位映射（分類顯示） |
| D4-2 | `MouseConfig` | 滑鼠靈敏度、反轉、滾輪速度 |
| D4-3 | `GamepadConfig` | 手柄按鍵映射、搖桿曲線 |
| D4-4 | `GestureConfig` | 未來觸控手勢（預留） |

---

## 9. Category E: 輸出與匯出節點

### E1: 配置匯出節點（3 個）

#### E1-1: `ConfigExport` — TOML 匯出

```
輸入：
  ● allConfigs ── STRUCT[] （從所有配置節點收集）

輸出：
  ○ tomlContent ── STRING （配置檔案內容）
  ○ filePath ── STRING （config/*.toml）

行為：
  1. 收集所有連接的配置值
  2. 按命名空間分組
  3. 生成 blockreality-common.toml / fastdesign-common.toml
  4. 即時寫入磁碟
```

#### E1-2: `NodeGraphExport` — 節點圖匯出

```
輸出：
  ○ jsonGraph ── STRING （節點圖的完整序列化）

支持格式：
  - .brgraph （Block Reality 節點圖格式）
  - .json （通用交換格式）
```

#### E1-3: `PresetExport` — 預設匯出

```
輸入：
  ● presetName ── STRING
  ● description ── STRING
  ● author ── STRING

輸出：
  ○ presetFile ── STRING （.brpreset）
```

### E2: 效能監控節點（5 個）

#### E2-1: `GPUProfiler` — GPU 效能

```
輸出：
  ○ frameTimeMs ── FLOAT
  ○ gpuTimeMs ── FLOAT
  ○ drawCalls ── INT
  ○ triangles ── INT
  ○ vramUsedMB ── FLOAT
  ○ fpsHistory ── CURVE （最近 300 幀的 FPS 曲線）
```

#### E2-2: `PassProfiler` — 逐 Pass 效能

```
輸出：
  ○ passTimings ── STRUCT[] （每個 pass 的 GPU 毫秒數）
  ○ bottleneckPass ── STRING （最慢的 pass）
```

**視覺化**：堆疊長條圖，每個 pass 一個色塊，
寬度代表 GPU 時間。

#### E2-3 ~ E2-5:

| # | 節點 | 用途 |
|---|------|------|
| E2-3 | `PhysicsProfiler` | 物理引擎耗時（SPA/BSE/FEM） |
| E2-4 | `MemoryProfiler` | RAM/VRAM 用量（即時折線圖） |
| E2-5 | `NetworkProfiler` | 封包大小、頻率（Sidecar 通訊） |

---

## 10. 視覺設計規範

### 10.1 Grasshopper 設計語言適配

| 元素 | Grasshopper 原版 | Block Reality 適配 |
|------|----------------|-------------------|
| **背景** | 灰白網格 | 深色網格（#1A1A2E）配合 Minecraft 夜空感 |
| **節點** | 圓角矩形 | 圓角矩形 + 微光邊框 |
| **連線** | 貝茲曲線 | 帶流動粒子的貝茲曲線（表示資料流向） |
| **群組** | 半透明色塊 | 半透明色塊 + 標題 |
| **選取** | 藍框 | 青色發光框 |
| **錯誤** | 紅橘色節點 | 紅色脈動邊框 + 警告圖標 |

### 10.2 節點色彩系統

```
渲染管線 ─── #2196F3 (藍) ─── 半透明藍 header
材料特性 ─── #4CAF50 (綠) ─── 半透明綠 header
材質調配 ─── #00CC88 (翠綠) ── 半透明翠綠 header （v1.1 新增）
物理計算 ─── #FF9800 (橙) ─── 半透明橙 header
工具 UI  ─── #9C27B0 (紫) ─── 半透明紫 header
輸出匯出 ─── #9E9E9E (銀) ─── 半透明銀 header
```

### 10.3 互動模式

| 操作 | 鍵位 | 效果 |
|------|------|------|
| 平移畫布 | 中鍵拖曳 | 無限平移 |
| 縮放 | 滾輪 | 0.1x ~ 10x |
| 框選多節點 | 左鍵拖曳空白 | 矩形選取 |
| 連線 | 左鍵拖曳端口 | 貝茲曲線預覽 |
| 斷線 | 右鍵連線 | 移除連線 |
| 新增節點 | 雙擊空白 / Tab | 搜尋面板 |
| 折疊節點 | 雙擊標題 | 最小化為一行 |
| 群組 | Ctrl+G | 框選節點建立群組 |
| 啟用/停用 | 右鍵→Toggle | 節點灰化 |
| 複製 | Ctrl+D | 克隆選取的節點+連線 |
| 搜尋 | Ctrl+F | 節點名稱搜尋 |
| 全部適配 | F | 縮放以顯示所有節點 |

### 10.4 搜尋面板設計

按 Tab 或雙擊空白處彈出的快速搜尋：

```
┌────────────────────────────────────┐
│  🔍 搜尋節點...                    │
├────────────────────────────────────┤
│  最近使用                           │
│    ● SSAO_GTAO                      │
│    ● MaterialConstant               │
│    ● ForceEquilibrium               │
├────────────────────────────────────┤
│  分類                               │
│  🔵 渲染管線 (57)                   │
│  🟢 材料與方塊 (40)                 │
│  🟠 物理計算 (22)                   │
│  🟣 工具與 UI (22)                  │
│  ⚪ 輸出 (8)                        │
└────────────────────────────────────┘
```

輸入文字後即時過濾，支援：
- 英文名稱：`ssao` → SSAO_GTAO
- 中文名稱：`環境遮蔽` → SSAO_GTAO
- 類別過濾：`render:bloom` → Bloom
- 模糊搜尋：`taa` → TAA, `blom` → Bloom
- 調配搜尋（v1.1）：`調配` → B5 所有節點, `blend` → BlockBlender

---

## 11. 取代原版視訊設定

### 11.1 Minecraft 原版設定 → 節點映射

| 原版設定 | 對應節點 | 端口 |
|---------|---------|------|
| Render Distance | `LODConfig` | maxDistance |
| Simulation Distance | `PhysicsLOD` | coarseDist |
| Graphics (Fast/Fancy/Fabulous) | `QualityPreset` | preset |
| Smooth Lighting | `SSAO_GTAO` | enabled |
| Max Framerate | `PerformanceTarget` | targetFPS |
| Use VSync | `FramebufferChain` | （新增 vsync 輸入） |
| GUI Scale | `HUDLayout` | scale |
| Brightness | `Tonemap` | exposure |
| FOV | `ViewportLayout` | mainViewFOV |
| View Bobbing | `CameraConfig` | （新增節點） |
| Attack Indicator | `Crosshair` | mode |
| Clouds | `Cloud` | enabled |
| Particles | `ParticleConfig` | （新增節點） |
| Entity Distance | `LODConfig` | （新增 entityLod 輸入） |
| Entity Shadows | `ShadowConfig` | entityShadowEnabled |
| Fullscreen | — | 系統層級，不入節點 |
| Mipmap | `GreedyMesh` | （新增 mipLevel 輸入） |
| Biome Blend | — | 不屬於 BR 管轄 |

### 11.2 簡化模式（初學者）

對不想使用節點圖的用戶，提供「簡化面板」：

```
┌─────────────────────────────────────────┐
│  ⚙ Block Reality 視訊設定               │
│                                          │
│  品質預設: [▼ High          ]            │
│                                          │
│  ━━ 基本 ━━━━━━━━━━━━━━━━━━━            │
│  視距        ├──────●──────┤ 768         │
│  目標幀率    ├────●────────┤ 60          │
│  解析度縮放  ├───────●─────┤ 1.0x        │
│                                          │
│  ━━ 光影 ━━━━━━━━━━━━━━━━━━━            │
│  陰影品質    [▼ High  ]                  │
│  環境遮蔽    [✓] GTAO                    │
│  反射        [✓] SSR                     │
│  抗鋸齒      [✓] TAA                     │
│                                          │
│  ━━ 效果 ━━━━━━━━━━━━━━━━━━━            │
│  泛光        [✓]                         │
│  體積光      [✓]                         │
│  景深        [ ]                         │
│  動態模糊    [ ]                         │
│                                          │
│  [進階設定 (節點編輯器) →]               │
│  [材質調配工作台 →]                      │
│                                          │
│  GPU: RTX 3070 | VRAM: 312/512 MB       │
│  FPS: 142 | GPU: 6.8ms | Pass瓶頸: SSGI │
└─────────────────────────────────────────┘
```

點擊「進階設定 (節點編輯器)」打開完整的 Grasshopper 風格畫布。
點擊「材質調配工作台」打開預設的 B5 調配節點圖（v1.1 新增）。

### 11.3 雙向同步

- 簡化面板的滑桿/勾選 ↔ 節點圖的端口值 **雙向綁定**
- 在簡化面板修改品質 → 節點圖中對應節點自動更新
- 在節點圖中手動連線 → 簡化面板中對應項目變為「自訂」

---

## 12. 剩餘工作清單

### 12.1 本報告涵蓋的新功能（全部未實作）

#### Phase N1: 節點引擎核心（最高優先）

| # | 任務 | 涉及檔案 | 預估工時 |
|---|------|---------|---------|
| N1-1 | BRNode 基礎類別 + Port 型別系統 | 新增 `node/BRNode.java`, `node/NodePort.java`, `node/PortType.java` | 8h |
| N1-2 | NodeGraph DAG 管理 + 拓撲排序 | 新增 `node/NodeGraph.java` | 6h |
| N1-3 | Wire 連線系統 + 型別檢查 | 新增 `node/Wire.java`, `node/TypeChecker.java` | 4h |
| N1-4 | 髒標記傳播 + 惰性評估排程 | `node/EvaluateScheduler.java` | 4h |
| N1-5 | NodeGroup 群組 + 折疊 | `node/NodeGroup.java` | 3h |
| N1-6 | 序列化/反序列化（JSON） | `node/NodeGraphIO.java` | 4h |
| N1-7 | 預設節點圖（5 個品質 Preset） | `node/presets/*.json` | 3h |

**N1 群總工時**: ~32h

#### Phase N2: 畫布渲染器（UI 層）

| # | 任務 | 預估工時 |
|---|------|---------|
| N2-1 | Canvas 無限平移/縮放渲染 | 8h |
| N2-2 | 節點 widget 渲染（header + ports + 內嵌控件） | 12h |
| N2-3 | 貝茲曲線連線渲染 + 流動粒子動畫 | 6h |
| N2-4 | 端口拖曳連線互動 | 4h |
| N2-5 | 搜尋面板 + 節點新增 | 4h |
| N2-6 | 框選 + 群組建立 | 3h |
| N2-7 | 內嵌預覽 thumbnail（SSAO map, 材料雷達圖等） | 8h |
| N2-8 | 內嵌控件（滑桿, 勾選框, 下拉選單, 色彩選擇器, 曲線編輯器） | 10h |
| N2-9 | 節點 tooltip + 錯誤標記 | 3h |
| N2-10 | Undo/Redo 節點操作歷史 | 4h |

**N2 群總工時**: ~62h

#### Phase N3: 資料綁定層（連接到 Runtime）

| # | 任務 | 預估工時 |
|---|------|---------|
| N3-1 | BRRenderConfig Binder（52 個渲染節點 ↔ static final 轉 mutable） | 8h |
| N3-2 | RMaterial Binder（材料節點 ↔ DefaultMaterial/DynamicMaterial） | 4h |
| N3-3 | Physics Binder（物理節點 ↔ ForceEQ/BSE/SPA/FEM 參數） | 4h |
| N3-4 | BRShaderEngine Binder（shader uniform 即時更新） | 4h |
| N3-5 | FastDesignConfig Binder（工具節點 ↔ Forge ConfigSpec） | 3h |
| N3-6 | TOML 匯出生成器 | 3h |
| N3-7 | 即時預覽管線（修改參數→下一幀生效） | 4h |

**N3 群總工時**: ~30h

#### Phase N4: 節點實作（144 個）

| 子類 | 節點數 | 平均工時/節點 | 小計 |
|------|--------|-------------|------|
| A1-A2 品質+管線 | 13 | 2h | 26h |
| A3 後處理 | 18 | 1.5h | 27h |
| A4-A7 光照+天氣+水 | 17 | 1.5h | 25.5h |
| B1-B4 材料+形狀 | 32 | 1.5h | 48h |
| **B5 材質調配** | **8** | **1.5h** | **12h** |
| C1-C4 物理 | 22 | 2h | 44h |
| D1-D4 工具+UI | 22 | 1.5h | 33h |
| E1-E2 輸出+監控 | 8 | 2h | 16h |

**N4 群總工時**: ~231.5h

#### Phase N5: 簡化面板 + 原版取代

| # | 任務 | 預估工時 |
|---|------|---------|
| N5-1 | 簡化設定面板 UI（取代 Options→Video Settings） | 8h |
| N5-2 | 雙向同步機制（簡化面板 ↔ 節點圖） | 4h |
| N5-3 | A/B 比較分割預覽 | 4h |
| N5-4 | GPU 自動偵測 + 推薦品質 | 3h |
| N5-5 | 首次啟動引導（自動設定最佳品質） | 2h |

**N5 群總工時**: ~21h

---

### 12.2 v4 報告殘留工作（非節點系統）

| # | 任務 | 原因 | 預估 |
|---|------|------|------|
| R-1 | Vulkan RT 渲染後端 | 需完全不同的管線，P3 長期 | 200h+ |
| R-2 | VRS (Variable Rate Shading) | 需 Vulkan/DX12 | 40h |
| R-3 | Mesh Shaders (NV_mesh_shader) | 需 GL 4.6 + Nvidia | 60h |
| R-4 | F 群: 系統櫃模組 API | 用戶跳過 | 30h |
| R-5 | D-4c: Rust Sidecar 後端 | 研究級 | 80h+ |
| R-6 | SharedMemoryBridge TODO 實作 | 待 Java 22 穩定 | 8h |
| R-7 | BRThreadedMeshBuilder mesh 實體建構 | 目前 stub | 12h |

---

## 13. 實作路線圖

### 總工時估算

| Phase | 內容 | 工時 |
|-------|------|------|
| N1 | 節點引擎核心 | 32h |
| N2 | 畫布渲染器 | 62h |
| N3 | 資料綁定層 | 30h |
| N4 | 144 個節點實作 | 231.5h |
| N5 | 簡化面板 | 21h |
| **總計** | | **376.5h** |

### 建議開發順序

```
Week 1-2:  N1 (核心引擎) ─── 可運行的最小節點圖系統
    │
    ▼
Week 3-5:  N2 (畫布渲染) ─── 可視化節點圖 + 連線互動
    │
    ▼
Week 5-6:  N3 (資料綁定) ─── 節點值 → 實際渲染配置
    │
    ▼
Week 6-7:  N4-A (渲染節點) ─── 取代視訊設定的核心節點
    │
    ▼
Week 7:    N5 (簡化面板) ─── 初學者友好的入口
    │
    ▼
Week 8-10: N4-B (材料節點 + 材質調配) ─── 材料配方系統 + B5 調配管線
    │
    ▼
Week 10-12: N4-C (物理節點) ─── 物理引擎可視化配置
    │
    ▼
Week 12-14: N4-D+E (工具+輸出) ─── 完整系統
```

### MVP（最小可行產品）— 4 週

只實作：
- N1 全部
- N2-1~N2-4（畫布+節點+連線）
- N3-1（渲染配置綁定）
- A1-1（品質預設）、A3-1~A3-5（5 個核心後處理）、A5-1（LOD）
- N5-1（簡化面板）

共 ~80h，可在 4 週內完成，提供基本的視覺化品質設定功能。

### 材質調配 MVP（v1.1 追加）— 額外 1 週

在主 MVP 基礎上追加：
- B5-1（VanillaBlockPicker）
- B5-2（PropertyTuner）
- B5-4（BlockCreator）
- B5-5（BlockPreview3D）
- N3-2（RMaterial Binder）

共 ~12h，提供最基本的「選方塊→改數值→產出自訂方塊」調配流程。

---

## 14. 附錄: 節點連接規則

### 14.1 合法連接矩陣

```
          → FLOAT  INT  BOOL  VEC2  VEC3  VEC4  COLOR  MAT   BLOCK  SHAPE  TEX  ENUM  CURVE  STRUCT
FLOAT       ✓     ✓    ✗     ✗     ✗     ✗     ✗      ✗     ✗      ✗      ✗    ✗     ✗      ✗
INT         ✓     ✓    ✓     ✗     ✗     ✗     ✗      ✗     ✗      ✗      ✗    ✗     ✗      ✗
BOOL        ✓     ✓    ✓     ✗     ✗     ✗     ✗      ✗     ✗      ✗      ✗    ✗     ✗      ✗
VEC2        ✗     ✗    ✗     ✓     ✗     ✗     ✗      ✗     ✗      ✗      ✗    ✗     ✗      ✗
VEC3        ✗     ✗    ✗     ✗     ✓     ✗     ✓      ✗     ✗      ✗      ✗    ✗     ✗      ✗
VEC4        ✗     ✗    ✗     ✗     ✗     ✓     ✗      ✗     ✗      ✗      ✗    ✗     ✗      ✗
COLOR       ✗     ✗    ✗     ✗     ✓     ✗     ✓      ✗     ✗      ✗      ✗    ✗     ✗      ✗
MATERIAL    ✗     ✗    ✗     ✗     ✗     ✗     ✗      ✓     ✗      ✗      ✗    ✗     ✗      ✗
BLOCK       ✗     ✗    ✗     ✗     ✗     ✗     ✗      ✓     ✓      ✗      ✗    ✗     ✗      ✗
SHAPE       ✗     ✗    ✗     ✗     ✗     ✗     ✗      ✗     ✗      ✓      ✗    ✗     ✗      ���
TEXTURE     ✗     ✗    ✗     ✗     ✗     ✗     ✗      ✗     ✗      ✗      ✓    ✗     ✗      ✗
ENUM        ✗     ✓    ✗     ✗     ✗     ✗     ✗      ✗     ✗      ✗      ✗    ✓     ✗      ✗
CURVE       ✗     ✗    ✗     ✗     ✗     ✗     ✗      ✗     ✗      ✗      ✗    ✗     ✓      ✗
STRUCT      ✗     ✗    ✗     ✗     ✗     ✗     ✗      ✗     ✗      ✗      ✗    ✗     ✗      ✓*

✓ = 直接相容
✓* = 需相同 schema
✗ = 不相容（需要拆分/轉換節點）
```

**v1.1 新增**：BLOCK → MATERIAL 自動拆出 `block.getMaterial()`，允許隱式轉換。
MATERIAL → BLOCK 不允許隱式轉換（缺少 blockId 和 texture），必須經過 BlockCreator。

### 14.2 特殊連接規則

1. **多對一**：一個輸入端口只接受一條連線（最後連接覆蓋之前的）
2. **一對多**：一個輸出端口可連接到多個輸入端口（fan-out）
3. **循環檢測**：連線前檢查是否形成環路，拒絕建立環路連線
4. **中斷線上值**：斷開連線後，輸入端口恢復為預設值
5. **自動轉換**：FLOAT→INT 時插入虛擬 Round 節點，節點上顯示「≈」標記
6. **BLOCK→MATERIAL 隱式轉換**（v1.1）：連接時自動插入虛擬 Unwrap 節點，提取 `RMaterial`

### 14.3 預設節點圖範例

#### 範例 1：「水平懸臂應力分析」

```
[MaterialConstant]──CONCRETE──→[RCFusion]──RC_MATERIAL──→[SupportPath]──stressField──→[StressVisualizer]
                                    ↑                                         ↑
[MaterialConstant]──REBAR────────────┘                               [ColorMap: BlueRed]
                                    ↑
[PhiSlider: 0.8]──phiTens──────────┘
[PhiSlider: 0.6]──phiShear─────────┘
```

#### 範例 2：「Ultra 品質渲染管線」

```
[QualityPreset: Ultra]─┬─shadowRes(4096)──→[ShadowConfig]
                       ├─ssaoEnabled(true)──→[SSAO_GTAO: GTAO mode]──aoTex──→[PipelineOrder]
                       ├─ssrEnabled(true)───→[SSR]──────────────ssrTex──→[PipelineOrder]
                       ├─taaEnabled(true)───→[TAA]──────────────taaTex──→[PipelineOrder]
                       ├─bloomEnabled(true)─→[Bloom]────────────bloom──→[PipelineOrder]
                       └─lodMax(1024)───────→[LODConfig]───┬──→[LODLevel 0: FULL]
                                                           ├──→[LODLevel 1: HIGH]
                                                           ├──→[LODLevel 2: MEDIUM + SVDAG]
                                                           └──→[LODLevel 3: LOW + SVDAG]
```

#### 範例 3：「強化橡木」材質調配（v1.1 新增）

```
[VanillaBlockPicker: oak_planks]─┬─baseMaterial──→[PropertyTuner]──tunedMaterial──→[BlockCreator]──→[BlockPreview3D]
                                 │                  rcomp=15.0                      "reinforced_oak"
                                 │                  rtens=16.0                      tint=#D4A373
                                 │                  density=650                           │
                                 │                                                        ▼
                                 └─block────────────────────────────→[BlockCreator].baseTexture
                                                                                          │
                                                                                    [RecipeAssigner]
                                                                                     Shaped 3×3
```

#### 範例 4：「鋼石複合板」雙方塊混合（v1.1 新增）

```
[VanillaBlockPicker: stone_bricks]──block──→[BlockBlender]──blendedBlock──→[BlockCreator]──→[BlockLibrary]
                                               ratio=0.7                    "steel_stone"    匯出 .brblock
[VanillaBlockPicker: iron_block]────block──→[BlockBlender]                  Checkerboard
                                               mode=Geometric
```

---

*報告結束*

**v1.1 下一步**：確認材質調配（B5）設計後，可從 Phase N1 開始實作，
或在主 MVP 完成後優先插入 B5 材質調配 MVP（~12h）。
