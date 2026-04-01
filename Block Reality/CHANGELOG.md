# Block Reality API — CHANGELOG

## [1.2.0] — 2026-03-25 (Round 5 — API 完整性 & 修復清查)

### 物理引擎 (Physics)

- **BFSConnectivityAnalyzer.rebuildConnectedComponents(ServerLevel)** 補齊實作：掃描已加載 chunk 中所有 RBlockEntity，建立完整連通分量。舊的無參數版本保留為安全降級入口
- **RCFusionDetector** 蜂窩判定從 `Math.random()` 改為 **FNV-1a 確定性 hash**（基於雙方 BlockPos），保證伺服器重啟後融合結果一致、跨執行緒安全、自動化測試可重現
- **LoadPathEngine.traceLoadPath()** 環路偵測從 `List.contains()` O(n) 改為 `HashSet` O(1)
- **ResultApplicator** StressUpdateEvent 改為 **批量收集後一次性發射**，減少 Forge event bus dispatch 開銷（applyStructureResult + applyStressField 兩條路徑）

### 並發安全 (Concurrency)

- **PhysicsExecutor.start()/shutdown()** 加入 `synchronized`，修復 check-then-act 競態條件；`submit()` 新增 null snapshot 防護 + volatile 單次讀取
- **CollapseManager** 佇列從 `ArrayDeque`（非線程安全）改為 `ConcurrentLinkedDeque`；補齊遺漏的 `Deque`/`ArrayDeque`/`Set`/`HashSet` import
- **SidecarBridge** RPC ID 計數器加入 `Integer.MAX_VALUE` 溢位保護（`updateAndGet` 正數循環）

### API 設計 (API Design)

- **DynamicMaterial.ofRCFusion()** 新增 null 檢查（`Objects.requireNonNull`）+ 負值材料參數防禦
- **DynamicMaterial.ofCustom()** 新增 id 非空驗證、density > 0 驗證、負強度 clamp 到 0

### Forge 合規 (Forge Compliance)

- **ServerTickHandler** 移除養護系統的主世界（Overworld）限制 — 建築可存在於所有維度

### 測試覆蓋率 (Test Coverage)

- 新增 **DynamicMaterialTest** — 56 @Test：ofRCFusion 公式、蜂窩懲罰、ofCustom 驗證、RMaterial 預設方法、輸入驗證
- 新增 **BFSConnectivityAnalyzerTest** — 30+ @Test：PhysicsResult/CachedResult record、epoch 管理、快取驅逐（AD-7）、BFS 核心演算法（孤立方塊、邊界錨定、空氣阻斷）

---

## [1.1.0] — 2026-03-25 (Round 4 — 大幅強化)

### 物理引擎 (Physics)

- **RMaterial 介面擴充**：新增 `getYoungsModulusPa()` 覆寫、`getYieldStrength()`、`getPoissonsRatio()`、`getShearModulusPa()` — 所有材料計算統一從 RMaterial 取真實工程值
- **DefaultMaterial 真實工程數據**：12 種材料的楊氏模量/泊松比/降伏強度改用 Eurocode 2 / AISC / EN 338 參考值，鋼材 E 從 350 GPa（近似偏差 75%）修正為 200 GPa
- **BeamElement compositeStiffness()** 改用 `getYoungsModulusPa()` 取代 `Rcomp × 1e9` 近似，消除鋼材 75%/木材 50% 偏差
- **BeamElement eulerBucklingLoad()** 加入有效長度係數 K=0.7（AISC C-A-7.1），挫屈力從 `π²EI/L²` 修正為 `π²EI/(KL)²`
- **ForceEquilibriumSolver** 移除節點級早期終止，改為全局殘差收斂（Gauss-Seidel 理論要求），消除非對稱誤差累積風險
- **FNV-1a warm-start hash** 納入 `material.getCombinedStrength()` bits（Score-fix #2），防止同形異材結構假碰撞

### 並發安全 (Concurrency)

- **StressRetryEntry** `retryCount`/`lastAttemptMs`/`maxRetries` 改為 `volatile`，保證跨執行緒 happens-before
- **applyStressWithRetry()** 移除未使用的 `delayMs` 參數（欺騙性 API），新增 3 參數重載 + 舊簽名標記 `@Deprecated`

### Forge 合規 (Forge Compliance)

- **AccessTransformer 實作**：新增 `META-INF/accesstransformer.cfg`，將 `Explosion.radius` (f_46024_) 改為 public
- **build.gradle** 加入 `accessTransformer` 配置
- **SPHStressEngine.getExplosionRadius()** 從純反射改為 AT 直接存取 + 反射 fallback

### API 設計 (API Design)

- **ModuleRegistry** Javadoc 統一說明 static 門面模式為刻意設計決策

### 測試覆蓋率 (Test Coverage)

- 新增 **DefaultMaterialTest** — 42 @Test：fromId、數值合理性、楊氏模量真實值、泊松比、剪力模量、BEDROCK 不溢位、isDuctile/maxSpan
- 新增 **CableStateTest** — 20 @Test：節點數計算、restSegmentLength、resetLambdas、calculateTension、isBroken、unmodifiable 防禦、volatile cachedTension
- 新增 **ResultApplicatorTest** — 18 @Test：StressRetryEntry 封裝/exhausted/volatile、failedPositions API、ApplyReport record、跨線程可見性概念驗證
- 新增 **DefaultCableManagerTest** — 26 @Test（Round 3.5）：normalizePair、CRUD、endpoint index、chunk 清理、count 一致性

---

## [1.0.1] — 2026-03-25 (Round 1–3 — 審計修復)

### Round 1 — 結構性修復 (20 fixes)

- #1 BlockType enum O(1) fromString 快取
- #2 CableState nodes unmodifiable view
- #3 RMaterial getYoungsModulusPa() default method
- #4 StructureResult/FusionResult/AnchorResult/StressField records
- #5 FNV-1a warm-start fingerprint (long 替代 int)
- #6 LRU LinkedHashMap 替代隨機驅逐
- #7 getMaterialFactor() 直接讀取 BlockType.structuralFactor
- #8 ForceEquilibriumSolver FNV-1a hash
- #9 SidecarBridge synchronized 移除 (deadlock fix)
- #10 NodeState mutable class 替代 record
- #11 getCablesAt endpoint index O(1)
- #12 PhysicsConstants 統一常數
- #13–#20 CableNode/VanillaMaterialMap/SidecarBridge/BeamElement 修復

### Round 2 — 深度優化 (8 fixes)

- N1 readLock 範圍修正（stop() 不再阻塞 30s）
- N2 BEDROCK density 修正（3000 kg/m³）
- N3 HORIZONTAL_DIRS 靜態常數
- N4 DT 引用 PhysicsConstants.TICK_DT
- N5 BLOCK_AREA 引用 PhysicsConstants
- N6 cachedTension volatile
- N7 SPH TODO 注釋修正
- N8 DefaultMaterial fromId O(1) HashMap

### Round 3 — Nitpick (11 fixes)

- R3-1 BlockType.fromString static HashMap
- R3-2 ModuleRegistry volatile 欄位
- R3-3 移除重複 validateMainThread()
- R3-4 StressRetryEntry 封裝
- R3-5 移除未使用 import
- R3-6 UNIT_AREA 引用 PhysicsConstants
- R3-7 BeamElement 移除未使用變數
- R3-8 SidecarBridge cleanupExecutor 延遲初始化
- R3-9 ServerTickHandler 呼叫 processFailedUpdates
- R3-10 onWorldUnload 清除 failedPositions
- R3-11 DefaultCableManager 單元測試
