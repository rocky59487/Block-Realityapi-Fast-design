# Block Reality Research Report v4 — 嚴格審查結果

審查日期：2026-03-28

## 一、報告「System Component Matrix」表與程式碼現實的重大矛盾

報告最後的 **System Component Matrix** 表格是最嚴重的問題。該表格聲稱多個系統「Not started」，但程式碼中已存在實作：

| 系統 | 報告聲稱狀態 | 程式碼實際狀態 | 判定 |
|------|-------------|--------------|------|
| Memory Optimization | Not started | `BRMemoryOptimizer.java` (708 行) 已存在 | 報告錯誤 |
| Threading | Not started | `BRThreadedMeshBuilder.java` (758 行) 已存在 | 報告錯誤 |
| Multi-Viewport | Not started | `BRViewportManager.java` (783 行) 已存在 | 報告錯誤 |
| Radial UI | Not started | `BRRadialMenu.java` (829行) + `PieMenuScreen.java` 已存在 | 報告錯誤 |
| Selection Tools | Not started | `BRSelectionEngine.java` (876 行) 已存在 | 報告錯誤 |
| Blueprint System | Not started | `Blueprint.java` + `BlueprintIO.java` + `BlueprintNBT.java` 已存在且有測試 | 報告錯誤 |
| TAA | Not started | `taaShader` 已宣告在 BRShaderEngine，`BRRenderConfig` 有完整 TAA 設定 | 報告錯誤 |

結論：報告的 Roadmap 和 Matrix 表格嚴重過時，14 個系統中有 7 個標記為「Not started」但實際已有實作。

## 二、報告內文「已存在」聲明的驗證（P0 核心系統）

| 報告聲明 | 驗證結果 | 判定 |
|----------|---------|------|
| "BRLODEngine already implements hysteresis-based LOD selection" | `BRLODEngine.java` (1,426行) — 確認有 `selectByDistanceBidirectional()` 方法 | 真實 |
| "BRShaderEngine already implements Cook-Torrance BRDF with GGX NDF and Schlick-GGX geometry" | `BRShaderEngine.java` (3,808行) — 確認有完整 GLSL：`DistributionGGX()`, `GeometrySchlickGGX()`, `fresnelSchlick()` | 真實 |
| "BRRenderPipeline already follows Iris multi-pass deferred architecture" | `BRRenderPipeline.java` (1,132行) — 確認有 Shadow→GBuffer→Deferred→Composite→Final 真實 GL 呼叫 | 真實 |
| "BRAnimationEngine already implements GeckoLib core patterns" | `BRAnimationEngine.java` (625行) + `BoneHierarchy.java` (919行) + `AnimationController.java` (817行) | 真實 |
| "ssaoShader already exists" | `BRShaderEngine.java` 中確認有 `private static BRShaderProgram ssaoShader` 及初始化邏輯 | 真實 |

## 三、虛假/誇大功能識別

### 3.1 Voxel Cone Tracing — 名不副實

- 報告聲稱：參考 NVIDIA GTC 2012 的 Voxel Cone Tracing GI
- 程式碼實際：`BRGlobalIllumination.java` (360行) 實作的是 SSGI（螢幕空間全域光照），非真正的 Voxel Cone Tracing
- 註解中提到 Crassin 2012 只是作為「靈感來源」，實際是完全不同的技術
- 判定：技術名稱誤導，實作層級遠低於聲稱

### 3.2 GPU Compute Shader Skinning — 未實作

- 報告聲稱：應該用 compute shader 做骨骼蒙皮
- 程式碼實際：使用傳統 vertex shader 上傳 bone matrices (`setUniformMat4Array`)，非 compute shader
- 判定：報告建議的 compute skinning 並未實作，現有方案是標準方式

### 3.3 Nanite-style Meshlet/Cluster — 完全不存在

- 報告聲稱：可採用 Nanite 的 128-triangle meshlet cluster hierarchy
- 程式碼實際：零相關程式碼，僅在 `BRAsyncComputeScheduler.java` 註解中提到 "Nanite Hi-Z"
- 判定：報告的建議項，完全未實作

### 3.4 Variable Rate Shading (VRS) — 完全不存在

- 報告聲稱：未來可用 VRS 達到 10-30% 效能提升
- 程式碼實際：零相關程式碼
- 判定：報告正確標記為 future/Vulkan-only，但程式碼中無任何準備工作

### 3.5 Mesh Shaders (GL 4.6 fast path) — 完全不存在

- 報告聲稱：參考 Nvidium 的 mesh shader
- 程式碼實際：零 mesh shader 程式碼，整個渲染管線基於 GL 3.3
- 判定：報告中標記 "Not started"，正確

### 3.6 Vulkan RT — 完全不存在

- 報告標記 "Not started"，正確。零 Vulkan 程式碼

## 四、報告 Tone Mapping 表格的內部矛盾

報告第 3.2.2 節的表格寫 Anti-Aliasing 為 "Not implemented"，但 `BRShaderEngine.java` 第 51 行明確有 `private static BRShaderProgram taaShader;`，`BRRenderConfig.java` 有 `TAA_ENABLED = true` 及完整的 Halton jitter 設定。

報告內部自相矛盾：同一份報告中，TAA 既被標記為 "Not implemented" 又有對應程式碼存在。

## 五、已確認完成且有測試的核心系統

以下系統經抽查確認為真實實作（非空殼），且有 JUnit 5 測試（共 31 個測試檔案、492 個 `@Test`）：

| 系統 | 位置 | 測試 |
|------|------|------|
| ForceEquilibriumSolver | physics/ | 2 個測試類 (26 tests) |
| BeamStressEngine | physics/ | BeamStressEngineTest |
| UnionFindEngine | physics/ | UnionFindEngineTest (35 tests) |
| DefaultMaterial | material/ | DefaultMaterialTest (19 tests) |
| DynamicMaterial | material/ | DynamicMaterialTest (56 tests) |
| CustomMaterial | material/ | CustomMaterialTest |
| BlockTypeRegistry | material/ | BlockTypeRegistryTest (23 tests) |
| Blueprint/NBT | blueprint/ | BlueprintNBTTest |
| CollapseManager | collapse/ | CollapseManagerTest |
| ChiselSystem | chisel/ | ChiselSystemTest (32 tests) |
| SidecarBridge | sidecar/ | SecurityTest |
| GreedyMesher | render/optimization/ | GreedyMesherTest |

## 六、無法驗證的項目

由於審查環境無法下載 Gradle 8.8 發行版，以下項目需在本地環境驗證：

1. 所有 63 個渲染相關 Java 檔案是否能通過編譯
2. 31 個測試檔案的 492 個測試是否全部通過
3. MctoNurbs-review TypeScript sidecar 是否能 `npm test` 通過

## 七、總結

| 類別 | 數量 | 說明 |
|------|------|------|
| 報告聲稱「已完成」且確認真實 | 4 | BRLODEngine, BRShaderEngine, BRRenderPipeline, BRAnimationEngine |
| 報告聲稱「Not started」但已有實作 | 7 | Memory Opt, Threading, Multi-Viewport, Radial UI, Selection, Blueprint, TAA |
| 名不副實/誇大的功能 | 2 | Voxel Cone Tracing (實際是 SSGI), GPU Compute Skinning (實際是 vertex shader) |
| 報告正確標記為未完成/未來 | 3 | Nanite Meshlet, VRS, Vulkan RT |
| 報告內部自相矛盾 | 1 | TAA 同時被標記為 "Not implemented" 和有對應 shader |

核心問題：報告的 System Component Matrix 和 Implementation Roadmap 嚴重過時（7/14 項狀態錯誤），需要立即更新。報告在技術研究層面是高品質的，但其對 Block Reality 現有系統的狀態追蹤不可靠。
