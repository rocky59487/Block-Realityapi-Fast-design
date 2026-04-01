# Block Reality — 任務手冊

> **版本**：v1.0 · 2026-04-01
> **來源文件**：implementation_plan.md · Block_Reality_完整審核報告.md · RT-RTX50-改進提案.md
> **適用對象**：全體開發成員

---

## 目錄

1. [任務總覽](#任務總覽)
2. [緊急修復（第 1 週）](#緊急修復第-1-週)
3. [UI / UX 改進（第 1-2 週）](#ui--ux-改進第-1-2-週)
4. [短期品質提升（第 3-4 週）](#短期品質提升第-3-4-週)
5. [Vulkan RT 升級計畫（Phase 0-8）](#vulkan-rt-升級計畫phase-0-8)
6. [長期規劃（第 5-18 週）](#長期規劃第-5-18-週)
7. [測試任務](#測試任務)
8. [風險追蹤](#風險追蹤)
9. [驗收標準](#驗收標準)

---

## 任務總覽

| 類別 | 任務數 | 預估總時間 |
|------|--------|------------|
| 🔴 緊急修復 | 10 | ~3 天 |
| 🎨 UI / UX | 3 | ~1 週 |
| 🟡 短期品質提升 | 12 | ~2 週 |
| ⚡ Vulkan RT 升級 | 9 個 Phase | 14-18 週 |
| 🟢 長期規劃 | 5 | ~4 週 |
| 🧪 測試補強 | 6 類 | ~2 週 |

---

## 緊急修復（第 1 週）

這些問題涉及工程正確性、學術誠信或安全性，必須在一切大型開發之前先行解決。

### 🔴 P1 — 修正樑彎矩計算公式

| 欄位 | 內容 |
|------|------|
| **位置** | `api/src/main/java/com/blockreality/api/physics/ForceEquilibriumSolver.java` |
| **問題** | 不平衡荷載彎矩使用 `L/4`，標準公式應為 `L/8` |
| **預估** | 2 小時 |
| **影響** | 工程安全計算錯誤，高估破壞臨界荷載 |
| **驗收** | `ForceEquilibriumSolverTest` 新增邊界案例確認彎矩值 |

### 🔴 P2 — 修正玻璃材料抗拉強度

| 欄位 | 內容 |
|------|------|
| **位置** | `api/src/main/java/com/blockreality/api/material/DefaultMaterial.java` |
| **問題** | `GLASS` 抗拉強度 `0.5 MPa`，實際應為 `30 MPa`（最低值） |
| **預估** | 1 小時 |
| **影響** | 玻璃結構物理計算嚴重失準 |
| **驗收** | 更新數值後執行 `./gradlew :api:test` 全數通過 |

### 🔴 P3 — 修正 BEDROCK 強度值溢位風險

| 欄位 | 內容 |
|------|------|
| **位置** | `api/src/main/java/com/blockreality/api/material/DefaultMaterial.java` |
| **問題** | `BEDROCK` 強度設為 `1e15`，極可能觸發 `float` 溢位導致 `NaN` |
| **預估** | 1 小時 |
| **解決方案** | 改為使用 `Float.MAX_VALUE / 2` 或特殊標記常數 `IS_INDESTRUCTIBLE = true` |
| **驗收** | 強度計算路徑加入 `isIndestructible()` 守衛，測試不出現 NaN |

### 🔴 P4 — 重命名 BFSConnectivityAnalyzer（學術誠信）

| 欄位 | 內容 |
|------|------|
| **位置** | `api/src/main/java/com/blockreality/api/physics/BFSConnectivityAnalyzer.java` |
| **問題** | 類別名聲稱 Union-Find，實際實作為 BFS 連通性分析 |
| **預估** | 4 小時（含全域重命名） |
| **解決方案** | 重命名為 `BFSConnectivityAnalyzer`，或實作真正的 Union-Find 替換內部演算法 |
| **驗收** | 所有測試通過，Javadoc 與實際演算法描述一致 |

### 🔴 P5 — 修復 GL 狀態污染

| 欄位 | 內容 |
|------|------|
| **位置** | `api/src/main/java/com/blockreality/api/client/render/` |
| **問題** | 渲染管線修改 GL 狀態後未還原，與 OptiFine/Sodium 衝突 |
| **預估** | 8 小時 |
| **解決方案** | 每個 render pass 前後以 `GL11.glPushAttrib/glPopAttrib` 或手動保存/還原 GL 狀態 |
| **驗收** | 搭配 OptiFine 測試，Minecraft 日誌無 GL Error |

### 🔴 P6 — 加強網路封包驗證

| 欄位 | 內容 |
|------|------|
| **位置** | `api/src/main/java/com/blockreality/api/network/BRNetwork.java` |
| **問題** | 封包缺少發送者驗證，存在伺服器端作弊風險；封包大小無上限可致 OOM |
| **預估** | 4 小時 |
| **解決方案** | 伺服器端處理器加入 `player.hasPermissions()` 或距離/權限驗證；封包加入最大位元組數上限 |
| **驗收** | 嘗試發送超大封包時正確拒絕並記錄警告 |

### 🔴 P7 — RT Alpha Channel 輸出修正

| 欄位 | 內容 |
|------|------|
| **位置** | `api/src/main/java/com/blockreality/api/client/render/rt/BRVulkanRT.java`（GLSL 內嵌） |
| **問題** | `closesthit` shader 可能輸出 `vec4(color, 0.0)`，造成 RT 層完全透明 |
| **預估** | 2 小時 |
| **解決方案** | 確保 `imageStore(u_RTOutput, pixel, vec4(result, 1.0))`；加入 Alpha 固定為 1.0 |
| **驗收** | 截圖確認 RT 輸出圖層有可見顏色，非純透明 |

### 🔴 P8 — RT 效果開關補全

| 欄位 | 內容 |
|------|------|
| **位置** | `api/src/main/java/com/blockreality/api/client/render/rt/BRRTSettings.java` |
| **問題** | 預設僅 `enableRTAO = true`，缺少 `RT_SHADOWS` / `RT_GI` / `RT_REFLECTIONS` 開關 |
| **預估** | 2 小時 |
| **解決方案** | 新增 volatile boolean 欄位並接入 Shader Specialization Constants |
| **驗收** | `VulkanRTConfigNode.evaluate()` 可獨立切換各 RT 效果，截圖確認視覺差異 |

### 🔴 P9 — JSON 解析深度限制（安全）

| 欄位 | 內容 |
|------|------|
| **位置** | `MctoNurbs-review/src/rpc-server.ts` |
| **問題** | JSON 解析無深度限制，可遭受 JSON Bomb 攻擊 |
| **預估** | 2 小時 |
| **解決方案** | 使用自訂 reviver 計算嵌套深度，超過 `MAX_JSON_DEPTH = 16` 時拋出錯誤 |
| **驗收** | 發送深度 100 的 JSON 物件，RPC server 正確拒絕並回傳 error response |

### 🔴 P10 — 升級 Forge 版本

| 欄位 | 內容 |
|------|------|
| **位置** | `Block Reality/build.gradle` |
| **問題** | 使用 Forge `47.2.0`，建議升級至 `47.4.13`（含安全性修補） |
| **預估** | 4-8 小時（含迴歸測試） |
| **解決方案** | 修改 `build.gradle` 中 `forge_version`，執行完整測試套件確認無破壞 |
| **驗收** | `./gradlew build` 通過，`./gradlew test` 全數通過 |

---

## UI / UX 改進（第 1-2 週）

### 🎨 UI-1 — BRGraphicsSettingsScreen（Iris 風格設定介面）

| 欄位 | 內容 |
|------|------|
| **新建檔案** | `fastdesign/src/main/java/com/blockreality/fastdesign/client/gui/BRGraphicsSettingsScreen.java` |
| **設計參考** | Iris Shaders / OptiFine 設定介面風格 |
| **預估** | 3-4 天 |

**頁籤規格：**

| 頁籤 | 控制項 |
|------|--------|
| **基礎渲染 (General)** | 視距 (Render Distance) 滑桿、解析度縮放 (Resolution Scale) 滑桿 |
| **光影與細節 (Lighting & Shadows)** | 太陽角度、環境光遮蔽 (SSAO) 開關、泛光 (Bloom) 開關、景深 (DOF) 強度 |
| **極致光追 (Vulkan Ray Tracing)** | 總開關（啟用 TIER_3）、降噪器選擇（SVGF / NRD）、最大彈射次數、RT 陰影/反射/AO 各別開關 |

**後台連動：** 所有控制項直接呼叫 `BRRenderSettings` / `BRRTSettings`，即時生效，無需重啟。

**入口：** 在「選項 > 視訊設定」注入「Block Reality 設定」按鈕；左下角附「進入節點編輯器」快捷入口。

**驗收：**
- 三個頁籤均可正常切換
- 滑桿與開關調整後視覺效果即時反映
- 「進入節點編輯器」按鈕正確跳轉

---

### 🎨 UI-2 — NodeSearchPanel 分類樹狀選單

| 欄位 | 內容 |
|------|------|
| **修改檔案** | `fastdesign/src/main/java/com/blockreality/fastdesign/client/node/NodeSearchPanel.java` |
| **預估** | 2-3 天 |

**實作規格：**

| 步驟 | 說明 |
|------|------|
| 引入 `CategoryHeaderItem` | 可摺疊的分類標題列，帶 `▶` / `▼` 展開圖示 |
| 引入 `NodeEntryItem` | 單一節點項目，歸屬特定分類 |
| 預設行為 | 無搜尋字串時顯示分類樹（預設摺疊），有搜尋字串時恢復平鋪清單 |
| 中文化類別名 | 見下表 |

**類別名稱中文化對照：**

| 英文 key | 中文顯示 |
|----------|---------|
| `render` | `[視覺與渲染]` |
| `postfx` | `[後製效果]` |
| `math` | `[數學運算]` |
| `logic` | `[控制邏輯]` |
| `input` | `[輸入控制]` |
| `core` | `[核心系統]` |

**驗收：**
- 點擊分類標題可展開/摺疊子節點
- 搜尋字串存在時正確切回平鋪模式
- 節點雙擊可正常拉線加入節點圖

---

### 🎨 UI-3 — RT 光追深層排查與修復

此為「光影視覺無變化」問題的系統性除錯任務，與 P7、P8 協同進行。

| 步驟 | 負責區域 | 說明 |
|------|---------|------|
| 1 | `BRRTSettings` + Shader | 確認 RT_SHADOWS / RT_GI 開關已生效（→ P8） |
| 2 | `BRVulkanRT` GLSL | 強制 Alpha=1.0 輸出（→ P7） |
| 3 | `BRRenderTier` / GPU 偵測 | 確認 TIER_3 條件正確觸發，dispatch 有執行 |
| 4 | `BRVulkanBVH.updateTLAS()` | 加入除錯日誌，確認區塊體素資料已裝入 BVH |

**驗收：** 截圖對比 Vanilla vs RT 開啟，陰影 / AO / 反射有可見視覺差異。

---

## 短期品質提升（第 3-4 週）

### 🟡 M1 — 重構 ForceEquilibriumSolver（750 行拆分）

| 欄位 | 內容 |
|------|------|
| **位置** | `api/.../physics/ForceEquilibriumSolver.java` |
| **目標** | 主類別降至 300 行以下，拆出子類別 |
| **預估** | 3-5 天 |

**拆分建議：**

| 新類別 | 負責內容 |
|--------|---------|
| `SORSolverCore` | SOR 迭代核心演算法 |
| `WarmStartCache` | Warm start 快取管理（含大小限制，見 M2） |
| `BeamBendingCalculator` | 彎矩 / 剪力計算（含 P1 修正） |

---

### 🟡 M2 — BFSConnectivityAnalyzer 快取大小限制

| 欄位 | 內容 |
|------|------|
| **位置** | `api/.../physics/BFSConnectivityAnalyzer.java` |
| **問題** | 靜態 `WARM_START_CACHE` 無上限，長時間運行可致 OOM |
| **預估** | 4 小時 |
| **解決方案** | 改為 `LinkedHashMap` 搭配 `MAX_CACHE_SIZE = 1024`，LRU 淘汰策略 |
| **驗收** | 壓力測試中 heap 使用穩定不成長 |

---

### 🟡 M3 — GreedyMesher 記憶體分配優化

| 欄位 | 內容 |
|------|------|
| **位置** | `api/.../client/render/GreedyMesher.java` |
| **問題** | 每次 meshing 大量臨時陣列分配，GC 壓力高 |
| **預估** | 3-5 天 |
| **解決方案** | 引入 thread-local 陣列池（`ThreadLocal<int[]>`），避免重複分配 |
| **預期收益** | GC 暫停減少 50-70% |

---

### 🟡 M4 — 鎖定所有 Gradle 依賴版本

| 欄位 | 內容 |
|------|------|
| **位置** | `Block Reality/build.gradle` |
| **問題** | 動態版本範圍（如 `[6.0,6.2)`）造成構建不確定性 |
| **預估** | 2 小時 |
| **解決方案** | 鎖定至具體版本（如 `6.0.51`），加入 `gradle.lockfile` |

---

### 🟡 M5 — 調整測試容忍度

| 欄位 | 內容 |
|------|------|
| **位置** | 多個測試檔案 |
| **問題** | 誤差容忍度 `20%` 過於寬鬆，效能閾值 `5秒` 過高 |
| **目標** | 容忍度 → `5%`；效能閾值 → `1秒` |
| **預估** | 4 小時 |

---

### 🟡 M6 — 添加異常情況與並發測試

| 欄位 | 內容 |
|------|------|
| **位置** | `api/src/test/java/com/blockreality/api/` |
| **預估** | 2 天 |

**測試清單：**

| 測試類別 | 場景 |
|---------|------|
| `ForceEquilibriumSolverTest` | 零荷載、極大荷載、NaN 輸入 |
| `BFSConnectivityAnalyzerTest` | 空圖、全連通圖、單節點圖 |
| `DefaultMaterialTest` | 未知材料 ID fallback 行為 |
| `SidecarBridgeTest` | IPC 超時應拋出異常而非返回 null |
| 並發測試 | `IMaterialRegistry` 多線程讀寫安全性 |

---

### 🟡 M7 — 補充 @NonNull / @Nullable 標註

| 欄位 | 內容 |
|------|------|
| **位置** | 物理引擎公開 API、SPI 接口 |
| **預估** | 4 小時 |
| **工具** | 使用 `org.jetbrains.annotations` 或 JSR-305 |

---

### 🟡 M8 — DefaultMaterial 隱式 Fallback 行為改善

| 欄位 | 內容 |
|------|------|
| **位置** | `api/.../material/DefaultMaterial.java` |
| **問題** | `fromId()` 找不到材料時靜默回傳 `CONCRETE`，呼叫端難以察覺 |
| **預估** | 2 小時 |
| **解決方案** | 拋出 `UnknownMaterialException` 或回傳 `Optional<Material>`，呼叫端顯式處理 |

---

### 🟡 M9 — 長細比檢查（Johnson 拋物線公式）

| 欄位 | 內容 |
|------|------|
| **位置** | `api/.../physics/ForceEquilibriumSolver.java` 或新建 `ColumnBucklingCalculator` |
| **問題** | 歐拉屈曲公式在低長細比時不適用，應切換 Johnson 拋物線公式 |
| **預估** | 3 天 |
| **驗收** | 新增 `ColumnBucklingTest` 測試長細比閾值切換 |

---

### 🟡 M10 — 補充 LICENSE 與 CONTRIBUTING 文件

| 欄位 | 內容 |
|------|------|
| **新建** | `LICENSE`（建議 MIT 或 GPL-3.0）、`CONTRIBUTING.md` |
| **預估** | 1 小時 |

---

## Vulkan RT 升級計畫（Phase 0-8）

> **目標**：RTX 50（Blackwell）全路徑追蹤 + RTX 40（Ada）混合 RT 後備
> **總時程**：14-18 週
> 詳細技術規格參見 `RT-RTX50-改進提案.md`

### 硬體層級對應

| 層級 | GPU | 核心特性 |
|------|-----|---------|
| **TIER_BLACKWELL** | RTX 5070+ | Cluster BVH、ReSTIR DI/GI、NRD ReLAX、DLSS 4 MFG |
| **TIER_ADA** | RTX 4060+ | Standard BVH + OMM、DDGI、NRD ReBLUR、DLSS 3 FG |
| **TIER_LEGACY_RT** | RTX 3060+ | 現有管線（維持不變） |

---

### Phase 0 — 基礎設施升級（第 1 週）

| 任務 ID | 任務 | 修改位置 | 預估 |
|---------|------|---------|------|
| RT-0-1 | 新增 Blackwell 擴展偵測 | `BRVulkanDevice.java` | 2 天 |
| RT-0-2 | `BRAdaRTConfig` 升級為三層 Tier | `BRAdaRTConfig.java` | 1 天 |
| RT-0-3 | `BRRTSettings` 新增 ReSTIR/DDGI/DLSS/Cluster 欄位 | `BRRTSettings.java` | 1 天 |
| RT-0-4 | LWJGL 版本確認（Cluster AS 綁定） | `build.gradle` | 0.5 天 |

**完成驗證：** `./gradlew :api:jar` 通過；`BRAdaRTConfig` 在 RTX 40/50 正確偵測 Tier。

---

### Phase 1 — Mega Geometry / Cluster BVH（第 2-4 週）

| 任務 ID | 任務 | 修改 / 新建 | 預估 |
|---------|------|------------|------|
| RT-1-1 | 新建 `BRClusterBVH.java` | 新建 | 1 週 |
| RT-1-2 | 實作 `rebuildClusterBLAS()`；scratch buffer 16MB → 64MB | `VkAccelStructBuilder.java` | 1 週 |
| RT-1-3 | Blackwell shader 套件（`shaders/rt/blackwell/`） | 新建 GLSL | 1 週 |
| RT-1-4 | Opacity Micromap — `BROpacityMicromap.java` | 新建 | 1 週（可並行 Ph2） |

**完成驗證：** Cluster BVH 建構無 Vulkan validation error；`BRClusterBVHTest` 通過。

---

### Phase 2 — ReSTIR DI 直接光照（第 2-3 週，可與 Ph1 並行）

| 任務 ID | 任務 | 修改 / 新建 | 預估 |
|---------|------|------------|------|
| RT-2-1 | Light BVH 建構 `buildLightBVH()` | `BRRTEmissiveManager.java` | 1 週 |
| RT-2-2 | ReSTIR DI compute shader | 新建 `restir_di.comp.glsl` | 1 週 |
| RT-2-3 | Reservoir buffer 管理 | 新建 `BRReSTIRDI.java` | 0.5 週 |

---

### Phase 3 — ReSTIR GI 間接光照（第 5-7 週）

| 任務 ID | 任務 | 修改 / 新建 | 預估 |
|---------|------|------------|------|
| RT-3-1 | ReSTIR GI compute shader（4-8 rays/pixel） | 新建 `restir_gi.comp.glsl` | 1.5 週 |
| RT-3-2 | Java 管理層 | 新建 `BRReSTIRGI.java` | 0.5 週 |
| RT-3-3 | SVDAG 新增 `serializeForReSTIR()` | `BRSparseVoxelDAG.java` | 0.5 週 |

**前置：** Ph1 + Ph2 完成。

---

### Phase 4 — DDGI Probe System（RTX 40 GI，第 2-3 週，可與 Ph1/Ph2 並行）

| 任務 ID | 任務 | 修改 / 新建 | 預估 |
|---------|------|------------|------|
| RT-4-1 | DDGI probe 網格管理 | 新建 `BRDDGIProbeSystem.java` | 1 週 |
| RT-4-2 | DDGI shader（update + sample） | 新建 2 個 GLSL | 0.5 週 |
| RT-4-3 | Ada 管線整合（DDGI GI 路徑、NRD ReBLUR） | `VkRTPipeline.java` | 0.5 週 |

---

### Phase 5 — NRD 降噪升級（第 6-7 週）

| 任務 ID | 任務 | 修改 / 新建 | 預估 |
|---------|------|------------|------|
| RT-5-1 | 建構原生 `blockreality_nrd.dll/so` | `BRNRDNative.java` + CMake | 1 週 |
| RT-5-2 | BRNRDDenoiser 完整實作（ReLAX/ReBLUR/SIGMA） | `BRNRDDenoiser.java` | 1 週 |

**降噪路徑對照：**

| 訊號 | 演算法 |
|------|--------|
| ReSTIR DI 陰影 | SIGMA Shadow |
| ReSTIR GI 漫反射 | ReLAX Diffuse |
| ReSTIR GI 鏡面 | ReLAX Specular |
| RTAO / DDGI | ReBLUR |
| Fallback | SVGF（現有，保留） |

---

### Phase 6 — DLSS 4 整合（第 8-9 週）

| 任務 ID | 任務 | 修改 / 新建 | 預估 |
|---------|------|------------|------|
| RT-6-1 | DLSS 4 MFG（Blackwell 1→4 幀）| 新建 `BRDLSS4Manager.java` | 1 週 |
| RT-6-2 | DLSS 3 FG 後備（Ada 1→2 幀） | `BRDLSS4Manager.java` 分支 | 0.5 週 |
| RT-6-3 | 內部 1080p → DLSS SR 4K → MFG | 管線整合 | 0.5 週 |

---

### Phase 7 — 節點系統擴充（第 10 週）

在 `fastdesign/client/node/impl/render/pipeline/` 新增以下節點：

| 節點 | 職責 | 新建 |
|------|------|------|
| `ReSTIRConfigNode` | ReSTIR DI/GI 參數 | ✅ |
| `DDGIConfigNode` | DDGI probe 密度 / 更新頻率 | ✅ |
| `DLSSConfigNode` | DLSS 模式 / 品質 | ✅ |
| `MegaGeometryNode` | Cluster BVH 設定 | ✅ |
| `OMMConfigNode` | Opacity Micromap 啟停 | ✅ |
| `NRDConfigNode` | NRD 演算法選擇 | ✅ |

同時擴充 `VulkanRTConfigNode` 新增端口 + `RenderConfigBinder` 映射分支。

---

### Phase 8 — 整合與 Shader 重構（第 11-12 週）

**RTX 50 渲染 Pass 順序：**
```
GBUFFER → CLUSTER_BVH_UPDATE → RESTIR_DI → RESTIR_GI → NRD → DLSS_SR → DLSS_MFG → TONEMAP → UI
```

**RTX 40 渲染 Pass 順序：**
```
GBUFFER → BLAS_TLAS_UPDATE → RT_SHADOW_AO → DDGI_UPDATE → DDGI_SAMPLE → NRD → DLSS_SR → DLSS_FG → TONEMAP → UI
```

**完成驗證：** 三條管線路徑（Blackwell / Ada / Legacy）各自獨立運行無 crash；效能達到第十章基準。

---

## 長期規劃（第 5-18 週）

| 任務 | 預估 | 說明 |
|------|------|------|
| 實作真正 Union-Find 演算法 | 5-7 天 | 取代現有 BFS，提升連通性查詢效能 |
| 優化 GreedyMesher 記憶體分配 | 3-5 天 | → M3（GC 壓力） |
| 長細比檢查 + Johnson 公式 | 3 天 | → M9 |
| 建立 CI/CD 流程（GitHub Actions） | 4 小時 | 自動執行 `./gradlew test`、構建報告 |
| 補充完整 API 文件與架構圖 | 8 小時 | `docs/` 目錄補充 Mermaid 架構圖 |

---

## 測試任務

### 新增單元測試

| 測試類別 | 驗證重點 | 位置 |
|---------|---------|------|
| `BRClusterBVHTest` | Cluster key 計算、4×4 section 打包 | `api/src/test/` |
| `BROpacityMicromapTest` | OMM 資料生成正確性 | `api/src/test/` |
| `BRReSTIRDITest` | Reservoir 更新數學正確性 | `api/src/test/` |
| `BRDDGIProbeSystemTest` | Probe 網格索引、octahedral 映射 | `api/src/test/` |
| `VulkanRTConfigNodeTest` | 節點 evaluate → BRRTSettings 映射 | `fastdesign/src/test/` |
| `ForceEquilibriumSolverTest（補強）` | 零荷載、極大荷載、NaN、並發安全 | `api/src/test/` |

### GPU 整合測試（手動，需 RTX 硬體）

| 測試項目 | 通過條件 |
|---------|---------|
| Vulkan validation layer | 零 ERROR（WARNING 可接受） |
| BLAS/TLAS 建構 | SBT alignment 正確，無 crash |
| NRD JNI 載入失敗 | 正確回退 SVGF，日誌輸出警告 |
| DLSS SDK 不可用 | 正確禁用 frame gen |
| 三個 Tier 路徑 | 各自獨立運行無 crash |

### 回歸測試（每次 PR 前執行）

```bash
cd "Block Reality"
./gradlew test          # 全部 JUnit 5 測試
./gradlew :api:test     # 僅 API 模組
```

---

## 風險追蹤

| 風險 | 嚴重度 | 緩解策略 | 狀態 |
|------|--------|---------|------|
| `VK_NV_cluster_acceleration_structure` LWJGL 綁定不完整 | 🔴 高 | Phase 0 驗證；備案：手動 JNI | ⏳ 待確認 |
| DLSS 4 SDK Java 封裝不存在 | 🔴 高 | Streamline SDK + JNI；備案：純 DLSS SR | ⏳ 待確認 |
| NRD JNI 原生庫跨平台編譯 | 🟡 中 | SVGF 保留 fallback | ⏳ 待確認 |
| ReSTIR GI reservoir VRAM 過高 | 🟡 中 | VRAM < 8GB 時降至半解析度 | ⏳ 待確認 |
| Forge 1.20.1 GL context 與 VK swapchain 共存 | 🔴 高 | 維持 GL/VK interop 至 Ph6；Ph8 評估全 VK | ⏳ 持續監控 |
| Cluster BVH 與 BRVulkanBVH 整合衝突 | 🟡 中 | 雙路徑：Legacy/Ada 用原 BVH，Blackwell 用 Cluster | ⏳ 待確認 |
| DDGI probe 更新延遲造成 GI 閃爍 | 🟢 低 | 時域穩定化 + hysteresis 過濾 | ⏳ 待確認 |

---

## 驗收標準

### 里程碑 1 — 緊急修復完成（第 1 週末）

- [ ] P1-P10 全部完成
- [ ] `./gradlew test` 539 個測試全數通過
- [ ] 截圖確認 RT 輸出非全透明 / 全黑

### 里程碑 2 — UI 改進完成（第 2 週末）

- [ ] `BRGraphicsSettingsScreen` 三個頁籤功能正常
- [ ] `NodeSearchPanel` 分類樹狀選單展開/摺疊正常
- [ ] RT 光追有可見視覺差異（陰影 / AO）

### 里程碑 3 — 短期品質提升完成（第 4 週末）

- [ ] `ForceEquilibriumSolver` 拆分至 300 行以下
- [ ] 測試容忍度調整為 5%，效能閾值調整為 1 秒
- [ ] 異常情況測試與並發測試新增完成
- [ ] Forge 版本升級至 47.4.13

### 里程碑 4 — Phase 0-2 完成（第 5 週末）

- [ ] Blackwell / Ada / Legacy Tier 偵測正確
- [ ] ReSTIR DI 數學正確性測試通過
- [ ] `BRClusterBVHTest` 通過

### 里程碑 5 — Phase 3-6 完成（第 10 週末）

- [ ] ReSTIR GI 視覺無明顯噪點
- [ ] NRD 降噪輸出品質優於 SVGF
- [ ] DLSS SR 路徑可用（MFG 為可選增強）

### 里程碑 6 — 全部完成（第 18 週末）

- [ ] 三條管線路徑（Blackwell / Ada / Legacy）獨立運行無 crash
- [ ] RTX 5070 @1080p 渲染幀時間 ≤ 6.3ms
- [ ] RTX 4060 @1080p 渲染幀時間 ≤ 9.1ms
- [ ] `./gradlew test` 全數通過
- [ ] 物理系統回歸測試通過

---

*手冊版本：v1.0 · 整合自 implementation_plan.md、Block_Reality_完整審核報告.md、RT-RTX50-改進提案.md*
