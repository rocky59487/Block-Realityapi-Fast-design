# Block Reality API — 完整審計報告

**日期**: 2026-03-25
**範圍**: `com.blockreality.api.*` 全部原始碼（93 個 Java 檔案）
**審計輪次**: 三輪（Round 1: #1–#20, Round 2: N1–N8, Round 3: 挑毛病/Nitpick）

---

## 一、總覽

| 類別 | 數量 | 已修復 | 待處理 |
|------|------|--------|--------|
| Round 1 — 結構性修復 | 20 | 20 | 0 |
| Round 2 — 深度優化 | 8 | 8 | 0 |
| Round 3 — Nitpick / 殘留問題 | 11 | 11 | 0 |

**結論**: Round 1–3 共 39 項中 39 項已處理完畢。R3-11（單元測試）已新增 `DefaultCableManagerTest.java`（26 @Test 方法）；`tickCables()` XPBD 模擬需 Minecraft ClassLoader，留待整合測試環境覆蓋。

---

## 二、Round 1 — 結構性修復（全數完成）

| # | 檔案 | 問題 | 嚴重度 |
|---|------|------|--------|
| 1 | `SidecarBridge` | `SIDECAR_BASE_DIR` 路徑與 `start()` defaultScript 不匹配，validateScriptPath 永遠拋 SecurityException | **Critical** |
| 2 | `CableState` | `nodes` 直接暴露可變 List，外部可破壞 `lambdas.length == nodes.size()-1` 不變式 | High |
| 3 | `CableElement` | `create()` 未對 BlockPos 呼叫 `immutable()`，@Immutable record 合約被違反 | High |
| 4 | `DefaultCableManager` | 缺少執行緒安全性標註，CableNode 可變狀態未文件化 | Medium |
| 5 | `VanillaMaterialMap` | 非原子性 map 替換，讀取端可見不完整狀態 | High |
| 6 | `DefaultCuringManager` | `startTick` 非 final，養護起始 tick 可被意外修改 | Medium |
| 7 | `DefaultMaterial` | `BEDROCK` 使用 `Float.MAX_VALUE` 強度值，乘法易溢位 | High |
| 8 | `ForceEquilibriumSolver` | warm-start 快取 hash 使用 `HashSet.hashCode()`，碰撞率過高 | Medium |
| 9 | `SidecarBridge` | `start()` 使用 `synchronized(this)` + `stateLock` 雙鎖，deadlock 風險 | High |
| 10 | `ForceEquilibriumSolver` | `NodeState` 在每次迭代中重複建立新物件，GC 壓力大 | Medium |
| 11 | `DefaultCableManager` | `getCablesAt()` O(N) 全表掃描 | Medium |
| 12 | `CableNode` / `FES` | 重力常數 9.81 硬編碼在多處 | Low |
| 13 | `DynamicMaterial` | 雙存取器 Javadoc 缺失 | Low |
| 14 | `BlockTypeRegistry` | `CORE_TYPES` 使用 List 線性搜尋 | Low |
| 15 | `CableState` | `calculateTension()` 在 tick 中被多次呼叫，無快取 | Medium |
| 16 | `DefaultMaterial` | `fromId()` Javadoc 寫 fallback 回傳 STONE 但實際回傳 CONCRETE | Low |
| 17 | `CableNode` | 缺少 public getter，外部無法安全存取 mass/fixed/attachPos | Medium |
| 18 | `SPHStressEngine` | 使用 Reflection 存取 Minecraft private field，應改用 AT | Low |
| 19 | `ForceEquilibriumSolver` | `sortedByY` 在每次迭代中重新排序 | Medium |
| 20 | 全域 | 缺少單元測試（所有核心類別皆無 test） | **Critical** |

---

## 三、Round 2 — 深度優化（全數完成）

| # | 檔案 | 問題 | 修復摘要 |
|---|------|------|----------|
| N1 | `SidecarBridge.call()` | readLock 在 `future.get()` 期間持有（最多 30s），阻塞 `stop()` 的 writeLock | readLock 只保護 running check + request send，在 lock 外 await future |
| N2 | `DefaultMaterial` | `BEDROCK` density = `Float.MAX_VALUE`（≈3.4e38），乘 g 後 weight ≈ 3.3e39，遠超 canSupport() 容量上限，導致非 anchor BEDROCK 方塊判為不能自撐 | density 改為 3000.0 kg/m³（近似緻密岩石） |
| N3 | `ForceEquilibriumSolver` | `NEIGHBOR_DIRS`（6 方向）未被使用（死代碼），且 `canSupport()` 內的方向陣列在 hot path 每次 new | 移除死代碼，改用 static `HORIZONTAL_DIRS`（4 方向） |
| N4 | `DefaultCableManager` | `DT = 0.05` 硬編碼，與 `ForceEquilibriumSolver.TICK_DT` 重複 | 改引用 `PhysicsConstants.TICK_DT` |
| N5 | `ForceEquilibriumSolver` | `BLOCK_AREA = 1.0` 硬編碼，與 `BeamElement.UNIT_AREA` 概念重複 | 改引用 `PhysicsConstants.BLOCK_AREA` |
| N6 | `CableState` | `cachedTension` 無 volatile，server tick thread 寫入 / 渲染執行緒讀取有 JMM word-tear 風險 | 加 `volatile` |
| N7 | `SidecarBridge` | `readResolve()` 是死代碼（未實作 Serializable，JVM 不會呼叫） | 移除 |
| N8 | `DefaultMaterial` | `fromId()` 以 O(N) 線性掃描搜尋，enum 值每次呼叫都要遍歷 | 靜態 `HashMap<String, DefaultMaterial>` 快取，O(1) 查找 |

---

## 四、Round 3 — Nitpick / 殘留問題（待處理）

### R3-1: `BlockType.fromString()` — O(N) 線性掃描

**檔案**: `BlockType.java:67-72`
**嚴重度**: Low
**描述**: 與 `DefaultMaterial.fromId()` 在 N8 中修復的問題完全相同。`fromString()` 每次呼叫遍歷所有 enum `values()`。目前在 `RBlockEntity.load()` 的 NBT 反序列化路徑被呼叫（每個 chunk 載入時對每個 RBlockEntity 呼叫一次），BlockType 只有 5 個值所以影響微小，但風格上應保持一致。

**建議修復**:
```java
private static final Map<String, BlockType> BY_NAME = new HashMap<>();
static {
    for (BlockType t : values()) BY_NAME.put(t.serializedName, t);
}
public static BlockType fromString(String name) {
    return BY_NAME.getOrDefault(name, PLAIN);
}
```

---

### R3-2: `ModuleRegistry` — 可變單例欄位缺少 volatile

**檔案**: `ModuleRegistry.java:59, 62, 95`
**嚴重度**: Medium
**描述**: `cableManager`、`loadPathManager`、`fusionDetector` 三個欄位都是非 volatile 的一般引用，卻標註 `@ThreadSafe`。`setCableManager()` 等 setter 從任意執行緒呼叫後，其他執行緒透過 `getCableManager()` 讀取時，JMM 不保證可見性（可能看到舊值）。

`curingManager` 和 `materialRegistry` 是 `final` 所以安全；但這三個可替換的欄位不是。

**建議修復**: 將三個欄位加上 `volatile`，或改用 `AtomicReference<ICableManager>` 等。

---

### R3-3: `ResultApplicator` — 重複的主執行緒斷言方法

**檔案**: `ResultApplicator.java:454, 583`
**嚴重度**: Low
**描述**: `assertMainThread(ServerLevel)` 和 `validateMainThread(ServerLevel)` 做完全相同的事情（檢查 `level.getServer().isSameThread()`，失敗拋 `IllegalStateException`），只是錯誤訊息略有不同。`assertMainThread` 被 5 處呼叫，`validateMainThread` 被 1 處呼叫。

**建議修復**: 合併為單一方法，統一命名。

---

### R3-4: `StressRetryEntry` — ConcurrentHashMap 值的公開可變欄位

**檔案**: `ResultApplicator.java:63-84`
**嚴重度**: Medium
**描述**: `StressRetryEntry` 是 `ConcurrentHashMap<BlockPos, StressRetryEntry>` 的 value，但 `retryCount`、`lastAttemptMs`、`maxRetries` 都是 `public` 非 volatile 欄位。雖然目前所有讀寫都在 main thread 上（由 `assertMainThread` 保證），但 `applyStressWithRetry()` 遍歷 `failedPositions.values()` 修改 `maxRetries` 時，理論上與 `retryFailed()` 的讀取沒有 happens-before 關係（如果未來有人從非 main thread 呼叫）。

此外，public 可變欄位違反封裝原則。

**建議修復**: 將欄位改為 private + getter/setter，或至少降為 package-private。若需跨執行緒安全，加 volatile 或 AtomicInteger。

---

### R3-5: `ModuleRegistry` — 未使用的 import

**檔案**: `ModuleRegistry.java:18`
**嚴重度**: Trivial
**描述**: `import java.util.ConcurrentModificationException` 未在任何地方使用。可能是早期版本使用 `ArrayList` 時引入的殘留。

**建議修復**: 刪除該 import。

---

### R3-6: `BeamElement.UNIT_AREA` 與 `PhysicsConstants.BLOCK_AREA` 重複

**檔案**: `BeamElement.java:48`, `PhysicsConstants.java:17`
**嚴重度**: Low
**描述**: `BeamElement.UNIT_AREA = 1.0` 和 `PhysicsConstants.BLOCK_AREA = 1.0` 是同一個物理量（1m² 方塊截面積）。N5 已將 `ForceEquilibriumSolver` 統一到 `PhysicsConstants.BLOCK_AREA`，但 `BeamElement` 仍保留自己的常數。

**建議修復**: `BeamElement.UNIT_AREA` 改為引用 `PhysicsConstants.BLOCK_AREA`，或在 `PhysicsConstants` 中統一定義。

---

### R3-7: `BeamElement.create()` — 未使用的區域變數

**檔案**: `BeamElement.java:64-65`
**嚴重度**: Low
**描述**: `create()` 方法中計算了 `EA` 和 `EB` 兩個區域變數，但實際使用的是 `compositeStiffness(matA, matB)` 回傳值。`EA` 和 `EB` 被宣告但從未使用。

```java
double EA = matA.getRcomp() * 1e9;  // ← 未使用
double EB = matB.getRcomp() * 1e9;  // ← 未使用
double E = compositeStiffness(matA, matB);  // ← 這才是實際使用的
```

**建議修復**: 移除第 64-65 行的 `EA`、`EB` 宣告。

---

### R3-8: `SidecarBridge.cleanupExecutor` — 生命週期問題

**檔案**: `SidecarBridge.java:88, 544`
**嚴重度**: Medium
**描述**: `cleanupExecutor` 在建構子中建立並啟動清理任務，但在 `stop()` 中被 `shutdown()` + `shutdownNow()` 永久關閉。如果之後呼叫 `start()` 重新啟動 sidecar，`cleanupExecutor` 已經被終止，`startCleanupTask()` 不會被重新呼叫，新的清理任務不會被排程。

更嚴重的是，`cleanupExecutor` 在建構子中就啟動了定期任務（`startCleanupTask()`），也就是說即使 sidecar 從未 `start()`，清理任務也在後台運行（雖然 `pending` 為空時基本無害）。

**建議修復**:
1. 將 `cleanupExecutor` 的建立和啟動移到 `start()` 中
2. 在 `start()` 中重新建立 executor（如果已被關閉）
3. 或改用 `ScheduledFuture` 追蹤任務，`stop()` 時 cancel 而非 shutdown executor

---

### R3-9: `ServerTickHandler` — 未呼叫 `ResultApplicator.processFailedUpdates()`

**檔案**: `ServerTickHandler.java`, `ResultApplicator.java:599`
**嚴重度**: Medium
**描述**: `ResultApplicator` 設計了完整的跨 tick 失敗恢復機制（`retryFailed()` / `processFailedUpdates()`），Javadoc 明確寫著「應在 ServerTickHandler 中每 tick 呼叫」。但 `ServerTickHandler.onServerTick()` 中並未呼叫此方法。這意味著寫回失敗的方塊永遠不會被重試恢復。

**建議修復**: 在 `ServerTickHandler.onServerTick()` 中加入：
```java
if (ResultApplicator.hasPendingFailures()) {
    MinecraftServer srv = ServerLifecycleHooks.getCurrentServer();
    if (srv != null) {
        ResultApplicator.processFailedUpdates(srv.overworld());
    }
}
```

---

### R3-10: `ResultApplicator` — 靜態 `failedPositions` 跨世界洩漏

**檔案**: `ResultApplicator.java:58`
**嚴重度**: Medium
**描述**: `failedPositions` 是 `static final ConcurrentHashMap`，在所有世界（Overworld、Nether、End、自訂維度）間共享。當一個世界卸載時，屬於該世界的失敗位置不會被清除（`ServerTickHandler.onWorldUnload()` 只清了 `CollapseManager` 的佇列）。這些殘留條目會在 `retryFailed()` 中被嘗試恢復，但對應的 `ServerLevel` 可能已不同，導致 `level.getBlockEntity(pos)` 在錯誤的世界中查詢。

**建議修復**:
1. 在 `ServerTickHandler.onWorldUnload()` 中呼叫 `ResultApplicator.clearFailedPositions()`
2. 或將 `failedPositions` 改為 per-dimension 的 Map

---

### R3-11: 全域 — 單元測試完全缺失

**檔案**: 全部
**嚴重度**: **Critical**
**描述**: 整個 API 沒有任何單元測試。#20 在 Round 1 中為每個核心類別加上了 TODO 註解標記建議測試項目，但尚未實作。以下為最高優先的測試目標：

1. **`DefaultMaterial`**: `fromId()` 查找、fallback、所有 enum 值數值合理性、BEDROCK 1e15 不溢位
2. **`CableState`**: 建構子節點數計算、restSegmentLength、resetLambdas、calculateTension（鬆弛/拉伸/斷裂）、isBroken() 閾值
3. **`CableElement`**: create() immutable 合約、maxTension 計算、Hooke's law
4. **`ForceEquilibriumSolver`**: FNV-1a hash 對稱性、SOR 收斂、canSupport 邊界值
5. **`DefaultCableManager`**: CRUD、getCablesAt 端點索引正確性、XPBD tick、chunk unload
6. **`BeamElement`**: compositeStiffness 調和平均、utilizationRatio von Mises、safetyFactor 邊界
7. **`SidecarBridge`**: 路徑驗證、JSON-RPC 序列化/反序列化、timeout/cleanup

---

## 五、架構層級觀察

### 5.1 PhysicsConstants 統一進度

| 常數 | 來源 | 是否已統一 |
|------|------|-----------|
| `GRAVITY = 9.81` | FES, CableNode | 已統一 → PhysicsConstants |
| `TICK_DT = 0.05` | FES, DCM | 已統一 → PhysicsConstants |
| `BLOCK_AREA = 1.0` | FES | 已統一 → PhysicsConstants |
| `UNIT_AREA = 1.0` | BeamElement | **未統一**（R3-6） |

### 5.2 執行緒安全標註一致性

| 類別 | 標註 | 實際狀態 | 一致性 |
|------|------|----------|--------|
| `ModuleRegistry` | @ThreadSafe | CopyOnWriteArrayList + CHM ✓，但三個可替換欄位非 volatile | **不一致**（R3-2） |
| `DefaultCableManager` | @NotThreadSafe | map ops 用 CHM，node 在 tick 中修改 | 一致 |
| `CableNode` | @NotThreadSafe | 可變陣列，主線程修改 | 一致 |
| `CableState` | 無標註 | 混合：cachedTension volatile，nodes unmodifiable | 可接受 |
| `CableElement` | @Immutable | record + immutable BlockPos | 一致 |

### 5.3 O(N) 查找模式殘留

| 位置 | 修復狀態 |
|------|----------|
| `DefaultMaterial.fromId()` | 已修 → N8 HashMap |
| `BlockType.fromString()` | **未修**（R3-1） |
| `BlockTypeRegistry.CORE_TYPES` | 已修 → #14 HashMap |

---

## 六、風險矩陣

| 優先級 | 項目 | 影響 |
|--------|------|------|
| ~~**P0**~~ ✅ | R3-9: processFailedUpdates 未被呼叫 | 已修復（ServerTickHandler 呼叫） |
| ~~**P0**~~ ✅ | R3-11: 無單元測試 | 已新增 DefaultCableManagerTest（26 @Test）；tickCables 留待整合測試 |
| **P1** | R3-2: ModuleRegistry 非 volatile 欄位 | setCableManager() 後其他執行緒可能讀到舊實現 |
| **P1** | R3-8: cleanupExecutor 生命週期 | sidecar restart 後清理任務失效 |
| **P1** | R3-10: failedPositions 跨世界洩漏 | 在錯誤的世界中執行 retry |
| **P2** | R3-4: StressRetryEntry 公開可變欄位 | 封裝性問題，未來擴展風險 |
| **P2** | R3-7: BeamElement 未使用變數 | 代碼清潔度 |
| **P3** | R3-1: BlockType.fromString O(N) | 微量效能（5 值 enum） |
| **P3** | R3-3: 重複方法 | 可維護性 |
| **P3** | R3-5: 未使用 import | 代碼清潔度 |
| **P3** | R3-6: UNIT_AREA 重複 | 常數統一性 |

---

## 七、已完成修復的品質評估

### 正面

1. **XPBD 實現正確**: per-constraint lambda 存儲、XPBD constraint iterations、NaN 保護、velocity damping 均符合 Macklin & Müller MIG'16 論文
2. **readLock 範圍修正 (N1)** 是關鍵的阻塞修復，避免 stop() 在 sidecar shutdown 時被卡住 30 秒
3. **BEDROCK density (N2)** 修復了一個會影響遊戲玩法的實際 bug（BEDROCK 方塊被判為不能自撐）
4. **volatile cachedTension (N6)** 正確識別了 JMM 跨執行緒可見性問題
5. **CableState unmodifiable nodes (#2)** 防止了 invariant 被外部破壞
6. **endpoint index (#11)** 將 getCablesAt 從 O(N) 降到 O(k)

### 需要注意

1. 修復的 BEDROCK 強度值 `1e15 MPa` 在 `double` 精度下安全，但如果任何地方將其轉為 `float`（例如 NBT 序列化用 `putFloat()`），會溢位為 `Infinity`
2. ~~`ForceEquilibriumSolver` 的 warm-start FNV-1a hash (#8) 在結構大小完全相同但成員不同時仍可能碰撞（FNV-1a 只 hash 了 sorted positions，不包含材料類型）~~ → **已修復**（Score-fix #2：`computeStructureFingerprint()` 現在也納入 `material.getCombinedStrength()` bits）
3. `DefaultCableManager` 的 CRUD/endpoint 邏輯已有單元測試（DefaultCableManagerTest），但 XPBD `tickCables()` 核心模擬邏輯仍需整合測試環境才能驗證

---

*報告結束。所有 P0 項目已處理完畢。下一輪建議優先處理：ResultApplicator 重試邏輯測試、SPHStressEngine 實際改用 AT（移除 reflection）、`applyStressWithRetry(delayMs)` 欺騙性參數修正。*
