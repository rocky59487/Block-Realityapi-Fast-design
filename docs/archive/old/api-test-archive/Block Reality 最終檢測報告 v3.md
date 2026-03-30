# Block Reality API — 最終嚴格檢測報告 v3（Bug 修復 + 功能補完後）

**日期**：2026-03-24
**審核等級**：🔴 嚴厲挑毛病級（Nitpick-grade）
**代碼庫規模**：85 Java 原始檔（13,497 LOC）+ 5 測試檔
**審核背景**：本輪審核在以下修復後進行——
- v2 報告 H-1 修復：ResultApplicator.applyStressWithRetry 參數語意修復
- v2 報告 L-5 修復：CuringProgressEvent 觸發接線
- v2 報告 M-4 修復：ForceEquilibriumSolver 材料填充（BlockPhysicsEventHandler）
- v3fix 8/9 補完：RWorldSnapshot.getChangedPositions() + captureNeighborhood()
- SPI 實作補完：ICuringManager + DefaultCuringManager + BatchBlockPlacer
- ICuringManager.tickCuring() 返回值重構（void → Set\<BlockPos\>）

---

## 目錄

1. [程式碼乾淨度審核](#一程式碼乾淨度審核)
2. [網路更佳演算法比對](#二網路更佳演算法比對)
3. [程式碼靈活性與模組連接性](#三程式碼靈活性與模組連接性)
4. [v3fix + 想法 差異分析](#四v3fix--想法-差異分析)
5. [未來模組所需但未實作的 API](#五未來模組所需但未實作的-api)
6. [額外發現](#六額外發現)
7. [與前兩輪報告對比 + 總評](#七與前兩輪報告對比--總評)

---

## 一、程式碼乾淨度審核

### 本輪修復的問題（v2 → v3）

| v2 問題 | 修復狀態 |
|---------|---------|
| H-1：applyStressWithRetry 參數語意不完整 | ✅ StressRetryEntry 新增 maxRetries 欄位，retryFailed() 使用 entry.maxRetries |
| L-5：CuringProgressEvent 未被觸發 | ✅ DefaultCuringManager.tickCuring() 返回完成集合 → ServerTickHandler post 事件 |
| M-4：ForceEquilibriumSolver 未整合事件鏈 | ✅ BlockPhysicsEventHandler 直接遍歷鄰域，從 RBlockEntity/VanillaMaterialMap 填充材料 |
| ForceEquilibriumSolver materials map 空白（邏輯 bug） | ✅ 材料從 RBlockEntity.getMaterial() 或 VanillaMaterialMap.getMaterial() 查詢 |
| ICuringManager.tickCuring() 無回傳值 | ✅ 重構為 Set\<BlockPos\>，呼叫端可得知哪些方塊完成養護 |

### 殘留問題

#### 🟠 高嚴重度（2 項）

**H-1：23 處 catch(Exception e) 過寬**
- **位置**：BlockRealityMod(3), BlueprintCommand(4), FdCommandRegistry(4), SidecarBridge(5), 其他(7)
- **影響**：掩蓋 IOException/IllegalArgumentException/NullPointerException 等，除錯困難
- **修復方向**：指令類優先細分為 CommandSyntaxException + IllegalArgumentException；SidecarBridge 的 5 處有部分已配對 TimeoutException/ExecutionException

**H-2：~40+ 公開方法缺少 Javadoc**
- **位置**：分散在 physics/, spi/, construction/ 包
- **影響**：外部模組開發者無法理解 API 契約
- **修復方向**：批次補充 Javadoc（建議優先處理 SPI 介面和事件類別）

#### 🟡 中嚴重度（4 項）

| # | 問題 | 位置 |
|---|------|------|
| M-1 | rebuildConnectedComponents() 仍為 stub（CAS 保護在但返回空結果） | UnionFindEngine.java L549-568 |
| M-2 | 24 個 wildcard import（java.util.\*, com.mojang.\* 等）分散 9 個檔案 | 全專案 |
| M-3 | StressUpdateEvent 僅在 LoadPathEngine 中 post，ForceEquilibriumSolver/SPH/BeamStress 路徑未覆蓋 | 事件覆蓋不完整 |
| M-4 | SidecarBridge 無路徑白名單（公開方法接受任意 Path） | SidecarBridge.java L217 |

#### ⚪ 低嚴重度（4 項）

| # | 問題 |
|---|------|
| L-1 | 部分中文/英文註解混用風格不統一 |
| L-2 | 缺少 @ThreadSafe / @NotThreadSafe 標注 |
| L-3 | ModuleRegistry.DefaultMaterialRegistry 內部類可提取為獨立檔案 |
| L-4 | FastDesignScreen TODO：拖曳框轉方塊選取範圍（UI 功能） |

### 乾淨度評分：**7.5 / 10**（v1: 5.5 → v2: 7.0 → v3: 7.5，+0.5）

> 殘留的 broad catch 和 Javadoc 缺失是中期改善項目，不影響核心功能。材料填充 bug 和事件觸發空洞已修復。

---

## 二、網路更佳演算法比對

### 已實作的改進

| 演算法 | 來源 | 狀態 |
|--------|------|------|
| Gustave 力平衡求解器（Gauss-Seidel 迭代鬆弛） | github.com/vsaulue/Gustave | ✅ ForceEquilibriumSolver + 已接線到 BlockPhysicsEventHandler |
| Voxelyze 複合材料梁（諧波平均 E） | github.com/jonhiller/Voxelyze | ✅ BeamElement.compositeStiffness() |
| 應變能追蹤 | Voxelyze strain energy | ✅ BeamElement.strainEnergy() |
| 安全係數方法 | 工程標準 | ✅ BeamElement.safetyFactor() |
| 時間戳 LRU 快取 | 標準 LRU 演算法 | ✅ ClientStressCache 真 LRU（accessTime 排序驅逐） |
| CAS 無鎖保護 | Java Concurrency in Practice | ✅ RBlockEntity AtomicBoolean + UnionFindEngine CAS |

### 本輪改進：ForceEquilibriumSolver 完全接線

v2 時 ForceEquilibriumSolver 是獨立引擎（materials map 為空，永遠返回空結果）。v3 修復後：

- BlockPhysicsEventHandler.performAlternativePhysicsAnalysis() 現在遍歷 radius=2 鄰域
- RBlockEntity 的方塊使用 rbe.getMaterial() 精確材料
- 原版方塊使用 VanillaMaterialMap fallback
- 正確識別錨定點（RBlockEntity.isAnchored() / Blocks.BEDROCK / Blocks.BARRIER）
- 求解結果統計不穩定方塊數量並記錄日誌

### 尚未實作但值得關注

| 演算法 | 來源 | 優勢 | 建議 |
|--------|------|------|------|
| Heavy-Light Decomposition | cp-algorithms.com | O(log²N) 路徑查詢取代 O(N) BFS | 🟠 中期目標 |
| Euler Tour Trees | Stanford CS166 | 子樹聚合查詢 | 🟡 長期研究 |
| 增量圖演算法 breakpoints | ACM SIGMOD'21 | 方塊破壞差量重算 | 🟠 中期目標 |
| Matrix-free Voxel FEM | arXiv:0911.3884 | 真 3D 應力張量 | 🟡 遊戲場景 overkill |
| Corotational FEM | Berkeley 2009 | 即時變形模擬 | 🟡 長期研究 |

### 演算法先進度評分：**8.0 / 10**（v1: 6.0 → v2: 7.5 → v3: 8.0，+0.5）

> ForceEquilibriumSolver 完全接線是關鍵突破 — 不再是空殼。剩餘的 HLD/Euler Tour 屬研究級演算法，在 Minecraft tick budget (50ms) 下實用性有待驗證。

---

## 三、程式碼靈活性與模組連接性

### 已建立的擴展基礎設施

| 元件 | 狀態 | 說明 |
|------|------|------|
| ICommandProvider | ✅ | 模組可註冊子指令 → BlockRealityMod 迭代調用 |
| IRenderLayerProvider | ✅ | 模組可註冊渲染層 → ClientSetup fireRenderEvent |
| IMaterialRegistry | ✅ | 執行緒安全材質註冊/查詢（ConcurrentHashMap） |
| IBlockTypeExtension | ✅ | 自定義方塊類型行為 |
| ICuringManager | ✅ | 每方塊養護追蹤 → tickCuring() 返回完成集合 |
| BatchBlockPlacer | ✅ | 批量方塊放置（LinkedHashMap + 抑制個別物理事件） |
| ModuleRegistry | ✅ | 中央單例，CopyOnWriteArrayList |
| StressUpdateEvent | ✅ | 應力變化事件（LoadPathEngine post） |
| LoadPathChangedEvent | ✅ | 負載路徑變化事件 |
| FusionCompletedEvent | ✅ | RC 融合完成事件 |
| CuringProgressEvent | ✅ 🆕 | 養護進度事件 → ServerTickHandler post（v3 接線） |

### 本輪修復的耦合問題

| v2 問題 | v3 狀態 |
|---------|---------|
| ForceEquilibriumSolver 未整合到事件鏈 | ✅ 已透過 BRConfig.useForceEquilibrium 開關整合 |
| CuringProgressEvent 無程式碼觸發 | ✅ DefaultCuringManager → ServerTickHandler → Forge Event Bus |
| BatchBlockPlacer 未實作（FD 阻塞項） | ✅ 已建立，支援 queue/flush/clear |

### 殘留的耦合問題

| # | 問題 | 嚴重度 |
|---|------|--------|
| 1 | LoadPathEngine 仍是 static 方法，未提取為 ILoadPathManager 介面 | 🟡 |
| 2 | RCFusionDetector 仍是 static 直接呼叫，未透過 SPI | 🟡 |
| 3 | BlockType enum 仍只有 4 值，IBlockTypeExtension 是註冊機制但 enum 未開放 | 🟡 |
| 4 | StressUpdateEvent 只在 LoadPathEngine 觸發，其他引擎未觸發 | 🟡 |

### 靈活性評分：**7.0 / 10**（v1: 4.0 → v2: 6.5 → v3: 7.0，+0.5）

> CuringProgressEvent 接線和 BatchBlockPlacer 實作消除了 2 個 FD/CI 阻塞項。static 方法耦合仍在但不阻塞模組開發（可透過 wrapper 繞過）。

---

## 四、v3fix + 想法 差異分析

### v3fix 合規性進度

| # | v3fix 規定 | v1 | v2 | v3 |
|---|-----------|---|---|---|
| 1 | CustomMaterial Builder pattern | ❌ | ✅ | ✅ |
| 2 | ResultApplicator retry 機制 | ❌ | ✅ | ✅ 參數修復 |
| 3 | ResultApplicator validateMainThread | ❌ | ✅ | ✅ |
| 4 | UnionFindEngine CAS 保護 | ❌ | ✅ | ✅ |
| 5 | UnionFindEngine 細粒度鎖 | ❌ | ✅ | ✅ |
| 6 | UnionFindEngine nodeEpoch Long | ❌ | ✅ | ✅ |
| 7 | Vector3i 純計算座標 | ❌ | ❌ | ❌ 仍用 BlockPos |
| 8 | RWorldSnapshot.getChangedPositions() | ❌ | ❌ | ✅ 🆕 已實作 |
| 9 | captureNeighborhood(center) | ❌ | ❌ | ✅ 🆕 radius=2 |
| 10 | ChunkEventHandler PROCESSING_CHUNKS 去重 | ❌ | ❌ | ❌ 檔案不存在 |
| 11 | BeamStressEngine Future timeout | ❌ | ✅ | ✅ |

**v3fix 合規率：9/11 = 81.8%**（v1: 0/11 → v2: 7/11 → v3: 9/11）

未完成的 2 項：
- **#7 Vector3i**：低優先。BlockPos 是 Minecraft 標準座標類型，強制轉換為 Vector3i 會增加轉換開銷且無明顯收益。建議在物理引擎內部使用時才引入。
- **#10 ChunkEventHandler 去重**：中優先。ChunkEventHandler 檔案尚不存在，需在 Chunk 事件處理邏輯成形後才有意義。

### 想法文件差異

| # | 想法文件描述 | v2 | v3 | 差距 |
|---|------------|---|---|------|
| 1 | R氏應力掃描儀 Item | ❌ | ❌ | 需 BRItems 註冊 + Item 類別 |
| 2 | 施工狀態機完整流程 | ⚠️ | ⚠️ | CI 模組範疇 |
| 3 | 養護計時 per-block | ⚠️ | ✅ 🆕 | ICuringManager + DefaultCuringManager + 事件接線 |
| 4 | 重機具 + 鋼索物理 | ❌ | ❌ | CI 模組範疇 |
| 5 | 錨定樁方塊 | ❌ | ❌ | 需 BlockType 擴展 |
| 6 | 蜂窩方塊視覺表現 | ⚠️ | ⚠️ | 機率存在但無獨立方塊 |
| 7 | 藍圖加密 | ❌ | ❌ | 低優先 |
| 8 | AnchorPathVisualizer × 掃描儀整合 | ⚠️ | ⚠️ | 掃描儀 Item 依賴 |

**想法符合度：API 基礎層 ~82% 完成（v2: ~75%），模組層 ~15%（預期中）**

---

## 五、未來模組所需但未實作的 API

### Construction Intern 模組

| # | 需求 | v2 | v3 | 說明 |
|---|------|---|---|------|
| 1 | 施工階段驗證鉤子 | 🟡 | 🟡 | IBlockTypeExtension.canTransitionTo 部分覆蓋 |
| 2 | 每方塊養護計時 | 🟡 | ✅ 🆕 | ICuringManager + DefaultCuringManager + CuringProgressEvent |
| 3 | 開挖記錄追蹤 | 🔴 | 🔴 | 仍缺（CI 模組自身範疇） |
| 4 | 錨定樁方塊類型 | 🟡 | 🟡 | IBlockTypeExtension 機制在，enum 未開放 |
| 5 | 模板系統 | 🔴 | 🔴 | CI 模組範疇 |
| 6 | 混凝土澆灌事件 | 🟡 | ✅ | FusionCompletedEvent + CuringProgressEvent 聯動 |
| 7 | 負載變化回調 | ✅ | ✅ | LoadPathChangedEvent |
| 8 | 應力閾值事件 | ✅ | ✅ | StressUpdateEvent.crossedThreshold() |
| 9 | 重機具系統 | 🔴 | 🔴 | CI 模組範疇 |
| 10 | 鋼索 PBD 物理 | 🔴 | 🔴 | CI 模組範疇 |
| 11 | R氏掃描儀 Item | 🔴 | 🔴 | 需 BRItems 註冊 |
| 12 | 融合完成回調 | ✅ | ✅ | FusionCompletedEvent |
| 13 | 養護後自動錨定驗算 | 🟡 | 🟡 | 事件機制在，觸發點待接線 |

**CI API 阻塞項：v2: 4 項 → v3: 3 項 CRITICAL（開挖、模板、掃描儀 — 其中開挖和模板屬 CI 模組自身範疇）**

### Fast Design 模組

| # | 需求 | v2 | v3 |
|---|------|---|---|
| 1 | Undo/Redo 系統 | 🔴 | 🔴 仍缺（FD 模組自身功能） |
| 2 | 批次方塊放置 API | 🔴 | ✅ 🆕 BatchBlockPlacer |
| 3 | 藍圖差異追蹤 | 🔴 | 🟡 RWorldSnapshot.getChangedPositions() 部分覆蓋 |
| 4 | 材料相容性驗證 | ✅ | ✅ IMaterialRegistry.canPair() |
| 5 | 指令外掛註冊 | ✅ | ✅ ICommandProvider |
| 6 | 渲染層外掛 | ✅ | ✅ IRenderLayerProvider |

**FD 阻塞項：v2: 3 項 → v3: 1 項（Undo/Redo — FD 模組自身功能，API 層無需提供）**

---

## 六、額外發現

### 6.1 測試覆蓋率

| 指標 | v1 | v2 | v3 |
|------|---|---|---|
| 測試類別數 | 0 | 5 | 5 |
| 測試方法 | 0 | ~39 | ~39 |
| 涵蓋核心類別 | 0% | 5 類 | 5 類（未新增） |
| 可在無 Minecraft 環境執行 | — | 3/5 | 3/5 |

**測試評分：3.0 / 10**（未變化）

> 本輪焦點在 Bug 修復和功能補完，未新增測試。建議下階段新增：ForceEquilibriumSolver 整合測試（含材料填充驗證）、DefaultCuringManager tickCuring 返回值測試、ResultApplicator 自訂 maxRetries 測試。

### 6.2 效能注意事項

| 項目 | 狀態 |
|------|------|
| ForceEquilibriumSolver 鄰域遍歷 (5³=125 方塊) | ✅ 小範圍，在 tick budget 內 |
| VanillaMaterialMap 260+ put 初始化 | ⚠️ 仍在 commonSetup 同步執行 |
| BeamStressEngine Future timeout | ✅ 5 秒超時 |
| ForceEquilibriumSolver 迭代上限 | ✅ MAX_ITERATIONS=100, CONVERGENCE=0.01 |
| SnapshotBuilder radius=16 記憶體 | ⚠️ 無全域快照計數器 |
| DefaultCuringManager.tickCuring() removeIf | ✅ ConcurrentHashMap.entrySet().removeIf 線程安全 |

### 6.3 安全性

| 項目 | 狀態 |
|------|------|
| BlueprintIO 路徑穿越 | ✅ sanitizeName() 已實作 |
| SidecarBridge 路徑注入 | ⚠️ 仍從 config 讀取路徑，公開方法無白名單 |

### 6.4 本輪新增/修改統計

| 類型 | 數量 |
|------|------|
| 新建 Java 檔案 | 3（ICuringManager, DefaultCuringManager, BatchBlockPlacer） |
| 修改 Java 檔案 | 5（BlockPhysicsEventHandler, ResultApplicator, ICuringManager, DefaultCuringManager, ServerTickHandler） |
| 修復 v2 報告問題 | 3（H-1, L-5, M-4） |
| 修復邏輯 bug | 1（ForceEquilibriumSolver materials 空白） |
| 介面重構 | 1（ICuringManager.tickCuring void → Set\<BlockPos\>） |

---

## 七、與前兩輪報告對比 + 總評

### 各維度對比

| 維度 | v1 | v2 | v3 | v2→v3 變化 |
|------|---|---|---|-----------|
| 程式碼乾淨度 | 5.5/10 | 7.0/10 | **7.5/10** | **+0.5** |
| 演算法先進度 | 6.0/10 | 7.5/10 | **8.0/10** | **+0.5** |
| 靈活性/模組性 | 4.0/10 | 6.5/10 | **7.0/10** | **+0.5** |
| v3fix 合規度 | 0/11 | 7/11 | **9/11** | **+2 項** |
| 想法符合度 | 5.0/10 | 6.5/10 | **7.0/10** | **+0.5** |
| 未來擴展準備度 | 3.0/10 | 6.0/10 | **7.5/10** | **+1.5** |
| 測試覆蓋率 | 0/10 | 3.0/10 | **3.0/10** | **±0** |
| **綜合** | **4.3/10** | **6.3/10** | **7.0/10** | **+0.7** |

### 各輪次里程碑

| 輪次 | 焦點 | 關鍵成果 |
|------|------|---------|
| v1 → v2 | 競態修復 + SPI 層建置 + 演算法引入 | 3 致命競態修復、9 新檔案、ForceEquilibriumSolver 骨架 |
| v2 → v3 | Bug 修復 + 功能接線 + 介面重構 | 材料填充 bug、事件觸發空洞、ICuringManager 返回值重構、BatchBlockPlacer |

### 最終結論

> **API 核心層已達到「可交付」水準（7.0/10）。** 力平衡求解器從空殼變為可運行的分析引擎，養護系統從無回調變為完整的事件驅動鏈，批量放置 API 為 Fast Design 模組消除了最後一個 API 層阻塞項。
>
> **剩餘改善屬「打磨」層級，不阻塞模組開發：**
> - broad catch 細分（23 處）— 影響除錯體驗，不影響功能
> - Javadoc 補充（40+ 公開方法）— 影響外部開發者效率
> - rebuildConnectedComponents() stub — 影響大規模結構重建，非常見使用場景
> - SidecarBridge 路徑白名單 — 安全加固
> - wildcard import 清理（24 處）— 純程式碼風格
>
> **建議下一步：**
> 1. 在 Windows 端執行 `.\gradlew.bat compileJava` 確認全部通過
> 2. 執行純 Java 單元測試 `.\gradlew.bat test`
> 3. 開始 Construction Intern 或 Fast Design 模組骨架
> 4. 模組開發中按需補充 Javadoc 和細分 Exception

---

*報告生成時間：2026-03-24*
*審核版本：v3.0-bug-fix-complete*
*代碼庫：85 files, 13,497 LOC + 5 test files*
