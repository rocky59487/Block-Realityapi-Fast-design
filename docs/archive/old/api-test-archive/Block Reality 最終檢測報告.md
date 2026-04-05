# Block Reality API — 最終嚴格檢測報告

**日期**：2026-03-24
**審核等級**：🔴 嚴厲挑毛病級（Nitpick-grade）
**審核範圍**：69 Java 原始檔、v3fix 手冊、想法文件、網路公開演算法
**審核視角**：CTO 級 Code Review，假設所有缺陷都會在 Production 爆炸

---

## 目錄

1. [程式碼乾淨度審核](#一程式碼乾淨度審核)
2. [網路更佳演算法比對](#二網路更佳演算法比對)
3. [程式碼靈活性與模組連接性](#三程式碼靈活性與模組連接性)
4. [v3fix 手冊 + 想法文件 差異分析](#四v3fix-手冊--想法文件-差異分析)
5. [未來模組所需但未實作的 API](#五未來模組所需但未實作的-api)
6. [額外發現：我認為需要檢查的](#六額外發現我認為需要檢查的)
7. [總評與行動清單](#七總評與行動清單)

---

## 一、程式碼乾淨度審核

### 🔴 致命問題（3 項）

#### CRITICAL-1：RBlockEntity 同步競態條件
- **位置**：`RBlockEntity.java` 第 197-233 行
- **問題**：`pendingSync` 和 `deferredFlushScheduled` 是 volatile 但未在 synchronized 區塊中操作。多執行緒同時呼叫 `syncToClient()` → `scheduleDeferredFlush()` 時，可能產生重複的 deferred flush 任務。
- **後果**：爆量網路封包，TPS 驟降
- **修復方向**：改用 `AtomicBoolean.compareAndSet()` 保護 scheduling flag

#### CRITICAL-2：ClientStressCache 非真 LRU，記憶體無上限
- **位置**：`ClientStressCache.java` 第 41-54 行
- **問題**：`evictLowStress()` 用 ConcurrentHashMap 迭代驅逐，但 ConcurrentHashMap 無保證迭代順序，無法實現真正的 LRU。當大量方塊應力值皆高於閾值時，快取永遠不會縮小。
- **後果**：長時間執行的客戶端記憶體持續膨脹，最終 OOM
- **修復方向**：改用 `LinkedHashMap(accessOrder=true)` + `synchronized`，或實作帶時間戳的驅逐策略

#### CRITICAL-3：HologramState 靜態可變狀態無同步保護
- **位置**：`HologramState.java` 第 24-28 行
- **問題**：`blueprint`、`origin`、`offset`、`rotationY`、`visible` 全部是靜態可變欄位，主執行緒寫入、渲染執行緒讀取，無任何 volatile 或 synchronized。
- **後果**：渲染執行緒讀取到半寫入的狀態 → 渲染崩潰或視覺錯亂
- **修復方向**：所有欄位加 volatile，或改用不可變 record + volatile 引用交換

---

### 🟠 高嚴重度（6 項）

| # | 問題 | 位置 | 後果 |
|---|------|------|------|
| H-1 | 多處異常被吞掉（silent catch） | FastDesignScreen:309, FdCommandRegistry:383, BlueprintIO:43 | 錯誤無法追蹤，debug 成本 ×10 |
| H-2 | CollapseManager.collapseQueue 無大小上限 | CollapseManager:39 | 大型結構坍塌時 queue 無限膨脹 → OOM |
| H-3 | FastDesignScreen.getBlockColor() null 安全漏洞 | FastDesignScreen:66 | blueprint.getBlocks() 返回 null 時 NPE |
| H-4 | 魔術數字散佈（顏色值、距離、閾值） | AnchorPathRenderer:28-34, StressHeatmapRenderer:36-49 | 維護困難，修改需全文搜索 |
| H-5 | FdCommandRegistry 單一類別處理 8+ 指令 | FdCommandRegistry.java 全檔 | God Class，違反 SRP |
| H-6 | DefaultMaterial 缺少 @Override 標注 | DefaultMaterial:50-54 | 違反 Java 最佳實踐，IDE 無法驗證介面契約 |

---

### 🟡 中嚴重度（8 項）

| # | 問題 | 位置 |
|---|------|------|
| M-1 | 過長方法（>50 行）| BeamStressEngine.computeBeamStress(), LoadPathEngine.propagateLoadDown() |
| M-2 | 短變數名 h/v/sx/sy/ex/ey/t | FastDesignScreen:152-177, StressHeatmapRenderer:149 |
| M-3 | 不一致的 null 檢查模式 | BlueprintIO:226 vs FdCommandRegistry:155 |
| M-4 | 渲染程式碼重複（AnchorPathRenderer ≈ HologramRenderer） | addQuad() 方法簽名完全一致 |
| M-5 | FdCommandRegistry 直接操作 level.setBlock() 無抽象 | FdCommandRegistry:167-183 |
| M-6 | 公開 API 缺少 Javadoc | PlayerSelectionManager, RBlockEntity batch methods |
| M-7 | 125000 魔術數字重複出現 | FdCommandRegistry:129 和 169 |
| M-8 | 錯誤訊息前綴不一致 §c[FD] / §c[BR] / §c[BR-CI] | 各 Command 類別 |

---

### ⚪ 低嚴重度 / 吹毛求疵（7 項）

| # | 問題 |
|---|------|
| L-1 | 例外捕捉範圍過寬（catch Exception vs 具體類型）|
| L-2 | 中英文註解混用（國際化不友好）|
| L-3 | enum 建構子 vs class 建構子大括號風格不一致 |
| L-4 | 使用 ★ 特殊符號標記修復（某些編輯器無法渲染）|
| L-5 | AnchorPathCache.lastUpdateTick 命名誤導（實際是 System.currentTimeMillis）|
| L-6 | StressHeatmapRenderer 應力-顏色映射用 if-else 而非宣告式 gradient |
| L-7 | LoadPathEngine 方向優先陣列 hardcoded 而非可配置 |

---

### 乾淨度總評分：**5.5 / 10**

> 核心物理引擎品質尚可，但周邊程式碼（渲染、指令、快取）品質明顯下降。3 個致命競態條件必須在上線前修復。

---

## 二、網路更佳演算法比對

### 🔴 應該替換的演算法

#### 1. BFS 負載路徑分析 → Gustave 力平衡求解器

| 對比項 | 目前實作 | Gustave (github.com/vsaulue/Gustave) |
|--------|---------|--------------------------------------|
| 方法 | BFS 加權搜尋 | 牛頓第一定律力平衡 |
| 精確度 | 啟發式，無法處理多支撐最佳分配 | 數學完備解 |
| 懸臂/水平結構 | 需 maxSpan hack | 自然支持 |
| 效能 | O(N) per query | 8000 方塊 < 1 秒 |
| 移植可行性 | — | 高（C++ header-only → Java 線性代數）|

**結論**：Gustave 的力平衡方法在物理正確性上完勝目前的 BFS。建議作為 v2 引擎候選。

#### 2. Union-Find + Epoch Rebuild → Heavy-Light Decomposition

| 對比項 | 目前實作 | HLD (cp-algorithms.com) |
|--------|---------|--------------------------|
| 刪除操作 | O(k) 懶惰重建 | O(log² N) 路徑查詢 |
| 子樹聚合 | 不支持 | 原生支持 |
| 實作複雜度 | 中 | 中（比 Link-Cut Tree 簡單）|
| 移植可行性 | — | 高 |

**結論**：HLD 在刪除節點（方塊破壞）場景下比 epoch rebuild 快一個數量級。中期升級目標。

#### 3. Euler-Bernoulli 梁元素 → Voxelyze 複合材料梁

| 對比項 | 目前實作 | Voxelyze (github.com/jonhiller/Voxelyze) |
|--------|---------|-------------------------------------------|
| 材料模型 | 單一弱材料取 E | 雙端材料加權平均 |
| 力學模型 | 軸力/彎矩/剪力基礎 | 完整應變能追蹤 |
| 成熟度 | 新寫 | 學術論文驗證，LGPL 授權 |
| 效能 | 未驗證 | 8000 voxels < 1s |
| 移植可行性 | — | 高（C++ → Java 直譯）|

**結論**：Voxelyze 的梁元素計算更完整，特別是複合材料（RC 節點）的應力分布。

---

### 🟠 值得研究的替代方案

| 演算法 | 來源 | 用途 | 優勢 | 可行性 |
|--------|------|------|------|--------|
| Euler Tour Trees | Stanford CS166 | 動態樹子樹聚合 | 快速子結構應力加總 | 中 |
| 增量圖演算法 | ACM SIGMOD'21 | 方塊破壞後差量重算 | 避免全量 BFS | 高 |
| Matrix-free Voxel FEM | arXiv:0911.3884 | 3D 有限元 | 真正的應力張量場 | 中（可能 overkill）|
| Corotational FEM | Berkeley 2009 | 即時變形 | 遊戲引擎已驗證 | 中 |

---

### 🟢 目前實作合理的部分

| 演算法 | 評價 |
|--------|------|
| RC 融合公式 (φ_tens/φ_shear/comp_boost) | 合理簡化，與 ACI 318 精神一致 |
| SPH 應力引擎 (BFS 加權) | 對遊戲場景足夠，真 SPH 太重 |
| 26-connectivity Union-Find | 正確的體素連通度選擇 |
| Teardown 式增量 BFS | 方向正確，只是效能可用 HLD 提升 |

---

## 三、程式碼靈活性與模組連接性

### 整體靈活性評分：**4 / 10** 🔴

### 致命耦合問題

#### 1. 靜態單例依賴鏈（無法替換引擎）
```
BlockPhysicsEventHandler
    ├→ LoadPathEngine（static 方法，無介面）
    ├→ RCFusionDetector（static 方法，無介面）
    ├→ UnionFindEngine.validateLocalIntegrity（static 方法）
    └→ CollapseManager.enqueueCollapse（static 方法）
```
**問題**：Construction Intern 無法注入自己的物理邏輯或攔截事件鏈。

#### 2. 渲染器硬編碼註冊
```java
// ClientSetup.ClientForgeEvents.onRenderLevel():
StressHeatmapRenderer.onRenderLevelStage(event);  // 硬編碼
HologramRenderer.onRenderLevelStage(event);        // 硬編碼
AnchorPathRenderer.render(event);                   // 硬編碼
```
**問題**：新模組無法註冊自己的渲染層（如施工進度視覺化）。

#### 3. 指令註冊無外掛機制
```java
// BlockRealityMod.onRegisterCommands():
SnapshotTestCommand.register(dispatcher);  // 硬編碼 ×9
```
**問題**：每個新模組都必須修改主類別才能加入指令。

#### 4. BlockType 是封閉 enum（4 值）
```java
public enum BlockType { PLAIN, REBAR, CONCRETE, RC_NODE }
```
**問題**：Construction Intern 需要 `ANCHOR_PILE`、`FORMWORK`、`REBAR_CAGE` 等新類型，無法擴展。

#### 5. 事件系統過於貧乏
- 全專案僅 1 個自定義 Forge Event：`RStructureCollapseEvent`
- 缺少：`StressUpdateEvent`、`LoadPathChangedEvent`、`FusionCompletedEvent`、`CuringProgressEvent`
- **後果**：模組間通訊只能靠直接方法呼叫，完全違反事件驅動架構

---

### 缺失的關鍵抽象層（8 項）

| # | 缺失介面 | 誰需要 | 急迫度 |
|---|---------|--------|--------|
| 1 | `ILoadPathManager` | Construction Intern 需攔截負載傳遞 | 🔴 |
| 2 | `IMaterialRegistry` | 兩個模組都需要安全的材料查詢/註冊 | 🔴 |
| 3 | `IBlockTypeRegistry` | CI 需要自定義方塊類型 | 🔴 |
| 4 | `ICuringManager` | CI 需要每方塊養護計時 | 🔴 |
| 5 | `ICommandProvider` | 兩個模組都需要註冊子指令 | 🟠 |
| 6 | `IRenderLayerProvider` | 兩個模組都需要自訂渲染層 | 🟠 |
| 7 | `IUndoRedoManager` | FD CAD 介面核心需求 | 🟠 |
| 8 | `IMachineryCoordinator` | CI 重機具系統 | 🟡 |

---

## 四、v3fix 手冊 + 想法文件 差異分析

### v3fix 手冊規定但未實作

| # | v3fix 規定 | 目前狀態 | 嚴重度 |
|---|-----------|---------|--------|
| 1 | `CustomMaterial` 帶 Builder pattern：`builder(id).rcomp().rtens().build()` | ❌ 不存在。僅有 `DynamicMaterial.ofRCFusion()` 工廠方法，非 Builder 模式 | 🟠 |
| 2 | `Vector3i` 不可變座標類別（取代 BlockPos 在純計算層） | ❌ 直接用 `BlockPos`，純計算層與 Minecraft API 未解耦 | 🟠 |
| 3 | `RBlockData` 不可變快照記錄 | ✅ `RBlockState` 存在但欄位命名不同（用 rComp/rTens/rShear 而非 rcomp/rtens/rshear） | 🟡 |
| 4 | `RWorldSnapshot.getChangedPositions()` | ❌ `RWorldSnapshot` 沒有差異追蹤功能 | 🟠 |
| 5 | `WorldSnapshotBuilder.captureBox(level, min, max)` | ✅ 存在 | ✅ |
| 6 | `WorldSnapshotBuilder.captureNeighborhood(level, center)` 26-neighbor radius=2 | ❌ 未實作（只有 capture 和 captureBox） | 🟡 |
| 7 | `ResultApplicator.applyStressWithRetry()` 帶 max 3 retries | ❌ ResultApplicator 無 retry 機制 | 🟠 |
| 8 | `ResultApplicator.processFailedUpdates()` 跨 tick 重試 | ❌ 不存在 | 🟠 |
| 9 | `ResultApplicator.validateMainThread(ServerLevel)` | ❌ 不存在 | 🟠 |
| 10 | UnionFindEngine `componentLocks` 細粒度鎖 | ❌ 使用 ConcurrentHashMap 但無 per-component ReentrantLock | 🟠 |
| 11 | UnionFindEngine `rebuildComponent` 帶 CAS 保護防並發重建 | ❌ 無 CAS 保護 | 🟠 |
| 12 | UnionFindEngine `nodeEpoch` 應為 Long（不是 Integer） | ⚠️ 目前用 int，v3fix 規定 Long/AtomicLong | 🟡 |
| 13 | `RBlockEntity.setStressLevelBatch()` + `flushSync()` 批次同步 | ⚠️ 方法存在但 v3fix 要求 50ms 同步節流，需驗證是否實作 | 🟡 |
| 14 | ChunkEventHandler 帶 PROCESSING_CHUNKS 去重 Set | ❌ 不存在獨立的 ChunkEventHandler 類別 | 🟠 |
| 15 | SidecarBridge ReadWriteLock + ConcurrentHashMap | ⚠️ SidecarBridge 存在但需驗證鎖實作 | 🟡 |

---

### 想法文件規定但 API 未準備

| # | 想法文件描述 | API 準備度 | 差距 |
|---|------------|-----------|------|
| 1 | R氏應力掃描儀（Item + Screen，熱圖/錨定模式切換） | ❌ 無自定義 Item 註冊系統 | 需要新增 BRItems 註冊 + 掃描儀 Item 類別 |
| 2 | 施工狀態機：開挖→錨定樁→鋼筋→模板→澆灌→養護 | ⚠️ ConstructionPhase enum 存在但缺少：開挖追蹤、模板系統、澆灌事件 | 核心流程框架在，細節 API 空缺 |
| 3 | 養護計時：tick counter，未養護完成 Rcomp 折減 | ❌ 只有 zone-level，無 per-block | 需全新 ICuringManager |
| 4 | 重機具：塔式起重機 + 鋼索物理 | ❌ 完全空白 | 需要 Entity 系統 + PBD rope |
| 5 | AnchorPathVisualizer：R氏掃描儀模式下高亮鋼筋鏈 | ⚠️ AnchorPathRenderer 存在但非 Item 觸發 | 需要與掃描儀 Item 整合 |
| 6 | 錨定樁方塊（玩家手動標記） | ❌ 無 ANCHOR_PILE 方塊類型 | BlockType enum 需擴展 |
| 7 | 蜂窩方塊（Rcomp × 0.6 的劣質節點） | ⚠️ RCFusionDetector 有 honeycomb 機率但生成的是 DynamicMaterial，非獨立方塊 | 語意正確但視覺表現缺失 |
| 8 | TypeScript Sidecar → DC + PCA + TRR → OBJ / NURBS | ⚠️ SidecarBridge + NurbsExporter 存在 | 需驗證完整性 |
| 9 | 藍圖加密格式 | ❌ 目前藍圖為明文 NBT | 低優先度 |

---

## 五、未來模組所需但未實作的 API

### Construction Intern 模組需要（13 項缺失）

| # | 需求 | 缺失的 API | 急迫度 |
|---|------|-----------|--------|
| 1 | 施工階段驗證鉤子 | `IPhaseValidator.canTransition(from, to)` | 🔴 |
| 2 | 每方塊養護計時 | `ICuringManager.startCuring(pos, profile)` | 🔴 |
| 3 | 開挖記錄追蹤 | `IExcavationTracker.recordExcavation(pos)` | 🔴 |
| 4 | 錨定樁方塊類型 | BlockType 擴展 `ANCHOR_PILE` | 🔴 |
| 5 | 模板系統 | 全新 Formwork 子系統 | 🔴 |
| 6 | 混凝土澆灌事件 | `ConcretePouredEvent(Region, volume)` | 🔴 |
| 7 | 負載變化回調 | `ILoadUpdateListener.onLoadChanged(pos, delta)` | 🔴 |
| 8 | 應力閾值事件 | `StressThresholdEvent(pos, level, threshold)` | 🔴 |
| 9 | 重機具座標系統 | `IMachineryCoordinator` | 🔴 |
| 10 | 鋼索 PBD 物理 | Verlet rope entity 系統 | 🔴 |
| 11 | R氏掃描儀 Item | BRItems 註冊 + custom Item class | 🟠 |
| 12 | 融合完成回調 | `FusionCompletedEvent(pos, material)` | 🟠 |
| 13 | 養護完成後自動觸發錨定驗算 | 目前 RCFusion 只在 place 觸發 | 🟠 |

### Fast Design 模組需要（6 項缺失）

| # | 需求 | 缺失的 API | 急迫度 |
|---|------|-----------|--------|
| 1 | Undo/Redo 系統 | `IUndoRedoManager` | 🔴 |
| 2 | 批次方塊放置 API | `BatchBlockPlacer.placeMany()` | 🔴 |
| 3 | 藍圖差異追蹤 | `IBlueprintDiffTracker` | 🔴 |
| 4 | 材料相容性驗證 | `IMaterialRegistry.canPair(a, b)` | 🔴 |
| 5 | 指令外掛註冊 | `ICommandProvider` | 🟠 |
| 6 | 渲染層外掛 | `IRenderLayerProvider` | 🟠 |

---

## 六、額外發現：我認為需要檢查的

### 6.1 效能地雷

#### 🔴 VanillaMaterialMap 260+ 預設值全部在程式碼中
- 首次啟動時 `generateDefaults()` 寫入巨大 JSON 到 config/
- 每次 `getMaterial()` 查 ConcurrentHashMap — 效能可接受
- **但**：260 行 `put()` 呼叫在 `loadDefaults()` 中是同步阻塞的，commonSetup 延遲約 50-100ms
- **建議**：改用 static initializer 或 lazy initialization

#### 🔴 BeamStressEngine 異步結果未回收
- `CompletableFuture<BeamStressResult>` 回傳後，如果呼叫端忽略結果，Future 物件永遠不被 GC 回收（直到 executor 關閉）
- **建議**：加入 timeout + `orTimeout(5, SECONDS)`

#### 🟠 SnapshotBuilder radius=16 的記憶體消耗
- 半徑 16 的球形快照 ≈ 17,157 方塊，每個 `RBlockState` ≈ 80 bytes
- 單次快照 ≈ 1.3 MB
- 多個同時觸發（多人伺服器）→ 記憶體壓力
- **建議**：加入全域快照計數器，超過閾值時排隊

### 6.2 安全性問題

#### 🟠 SidecarBridge 外部行程注入風險
- `ProcessBuilder` 啟動 Node.js 子行程，路徑從 config 讀取
- 如果 config 被惡意修改，可執行任意命令
- **建議**：路徑白名單驗證 + 檔案雜湊驗證

#### 🟠 BlueprintIO 路徑穿越風險
- `save()`/`load()` 接受玩家輸入的檔案名
- 如果名稱包含 `../`，可能寫入/讀取任意位置
- **建議**：在 `sanitizeFilename()` 中加入路徑穿越檢查

### 6.3 Forge 1.20.1 相容性風險

#### 🟡 AFTER_TRANSLUCENT_BLOCKS 渲染階段
- `AnchorPathRenderer` 和 `StressHeatmapRenderer` 都使用此渲染階段
- Forge 1.20.1 的渲染管線在某些 OptiFine/Iris 組合下可能跳過此階段
- **建議**：新增 config 開關允許切換到 AFTER_SOLID_BLOCKS

#### 🟡 BlockEntity 同步頻率
- v3fix 規定 50ms 最小同步間隔
- 如果單一 tick 內多個方塊被破壞（TNT 爆炸），可能產生同步風暴
- **建議**：實作 batch sync 機制（收集一個 tick 內所有變更，統一同步）

### 6.4 測試覆蓋率

#### 🔴 零單元測試
- 整個專案沒有任何 `@Test` 方法
- `build.gradle` 宣告了 JUnit 5 依賴但從未使用
- **最高優先修復**：至少為以下核心邏輯撰寫測試：
  - UnionFindEngine: find/union/remove/rebuild
  - BeamElement: 應力計算正確性
  - RCFusionDetector: 融合公式
  - VanillaMaterialMap: 材質對應
  - SupportPathAnalyzer: 路徑搜尋

### 6.5 日誌品質

#### 🟡 日誌等級不一致
- 某些錯誤用 `LOGGER.warn()`（應該用 `error()`）
- 某些 debug 資訊用 `LOGGER.info()`（應該用 `debug()`）
- 無結構化日誌（缺少 context 如 structureId、blockPos）
- **建議**：統一日誌規範，加入結構化 context

---

## 七、總評與行動清單

### 各維度評分

| 維度 | 分數 | 說明 |
|------|------|------|
| 程式碼乾淨度 | **5.5/10** | 核心尚可，周邊粗糙，3 個致命競態 |
| 演算法先進度 | **6/10** | BFS 負載路徑和 Union-Find epoch 可升級，RC 融合公式合理 |
| 靈活性/模組性 | **4/10** | 嚴重耦合，8 個關鍵抽象缺失，僅 1 個自定義 Event |
| v3fix 手冊符合度 | **6.5/10** | 核心架構遵循，15 項 v3fix 細節未實作 |
| 想法文件符合度 | **5/10** | API 層基本完成，但 CI/FD 所需鉤子幾乎為零 |
| 未來擴展準備度 | **3/10** | 兩個模組加起來 19 項 API 缺失，6 項是 Critical |
| 測試覆蓋率 | **0/10** | 零測試 |
| **綜合** | **4.3/10** | |

---

### 🔴 必須立即修復（開發阻塞級）

| # | 行動 | 預估工時 | 影響 |
|---|------|---------|------|
| 1 | 修復 RBlockEntity 同步競態（AtomicBoolean） | 1h | 防止封包風暴 |
| 2 | 修復 ClientStressCache 真 LRU | 2h | 防止客戶端 OOM |
| 3 | 修復 HologramState volatile/record | 1h | 防止渲染崩潰 |
| 4 | CollapseManager 加入 MAX_QUEUE_SIZE | 0.5h | 防止坍塌 OOM |
| 5 | 撰寫核心單元測試（5 個測試類別） | 8h | 品質底線 |

### 🟠 開始模組開發前必須完成

| # | 行動 | 預估工時 | 影響 |
|---|------|---------|------|
| 6 | 提取 ILoadPathManager 介面 | 3h | CI 模組基礎 |
| 7 | 建立 IMaterialRegistry + IBlockTypeRegistry | 4h | 兩模組共用 |
| 8 | 實作事件系統（StressUpdateEvent、LoadChangedEvent、FusionCompletedEvent） | 4h | 事件驅動架構 |
| 9 | 建立 ICommandProvider 外掛機制 | 2h | 指令模組化 |
| 10 | 建立 IRenderLayerProvider 外掛機制 | 2h | 渲染模組化 |
| 11 | 補齊 v3fix 規定的 ResultApplicator retry 機制 | 2h | spec 一致性 |
| 12 | 補齊 v3fix 規定的 UnionFindEngine CAS + 細粒度鎖 | 3h | 並發安全 |
| 13 | 補齊 CustomMaterial Builder pattern | 1h | spec 一致性 |

### 🟡 中期優化

| # | 行動 | 預估工時 |
|---|------|---------|
| 14 | 研究並原型 Gustave 力平衡求解器 | 16h |
| 15 | 研究並原型 Voxelyze 複合材料梁 | 8h |
| 16 | 拆分 FdCommandRegistry 為獨立指令類別 | 3h |
| 17 | 提取渲染 utility（共用 addQuad 等） | 2h |
| 18 | 統一魔術數字為命名常數 | 2h |
| 19 | BlueprintIO 路徑穿越防護 | 1h |
| 20 | BeamStressEngine Future timeout | 1h |

---

### 關鍵結論

> **API 層的物理引擎核心是穩固的。** UnionFind + BFS + Euler-Bernoulli + RC Fusion 的組合在遊戲物理模擬中是合理且可運作的選擇。
>
> **但 API 的「API 性」嚴重不足。** 作為兩個未來模組的基礎，目前的程式碼更像是一個獨立應用程式而非可擴展的 API。缺少介面、事件、註冊機制，使得 Construction Intern 和 Fast Design 模組無法在不修改 API 核心的情況下正常運作。
>
> **零測試是最大的技術債。** 在沒有測試的情況下進行上述重構極度危險。建議先寫測試，再動架構。
>
> **演算法可以慢慢升級，但架構問題必須現在修。** Gustave/Voxelyze 是中長期目標，但 8 個缺失介面和 3 個致命競態必須在下一輪開發前解決。
