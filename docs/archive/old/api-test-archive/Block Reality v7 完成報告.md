# Block Reality v7 計畫執行報告

**執行日期**: 2026-03-25
**範圍**: P0 (編譯修復) → P1 (高影響力) → P2 (中影響力)
**目標分數**: 9.2 → 9.7 / 10

---

## P0 — 編譯修復 (3/3 完成)

| 編號 | 任務 | 狀態 |
|------|------|------|
| C1 | SPHStressEngine 缺少 4 個 import | ✅ 已修復 |
| C2 | ModuleRegistry 重複 import | ✅ 已修復 |
| C3 | build.gradle 加入 JUnit 5 + JSR-305 | ✅ 已修復 |

## P1 — 高影響力改善 (5/5 完成)

| 編號 | 任務 | 狀態 | 說明 |
|------|------|------|------|
| H1 | Chunk unload 纜索清理 | ✅ | ChunkEventHandler + ICableManager.removeChunkCables + LoadPathEngine.onChunkUnload + RBlockEntity.chunkUnloading |
| H2 | JSR-305 執行緒安全標註 | ✅ | @ThreadSafe: SPH, ModuleRegistry, DefaultCableManager, DefaultCuringManager, UnionFindEngine; @NotThreadSafe: LoadPathEngine, ForceEquilibriumSolver; @Immutable: BeamElement, CableElement |
| H3 | SPH CompletableFuture 鏈式重構 | ✅ | .whenComplete → .exceptionally + .thenAccept，空結果不排程主線程 |
| H4 | 纜索基礎物理 tick | ✅ | 與 M1 合併，完整 XPBD 模擬 |
| H5 | RCFusionDetector 魔術數字外部化 | ✅ | 已確認全部從 BRConfig 讀取，無需修改 |

## P2 — 中影響力改善 (6/6 完成)

| 編號 | 任務 | 狀態 | 說明 |
|------|------|------|------|
| M1 | XPBD 距離約束升級 | ✅ | CableNode + CableState + DefaultCableManager XPBD solver（與 H4 合併實作） |
| M2 | SOR warm-start 快取 | ✅ | ForceEquilibriumSolver 加入 WARM_START_CACHE + LRU 驅逐 |
| M3 | SidecarBridge 安全測試 | ✅ | 6 個測試覆蓋路徑穿越、符號連結、null、白名單外路徑 |
| M4 | DefaultCuringManager 整合測試 | ✅ | 14 個測試覆蓋 tick、進度、計數、提前移除、邊界條件 |
| M5 | BlockType 動態擴展 | ✅ | BlockTypeRegistry（ConcurrentHashMap）+ BlockType.isKnownType() + 24 個測試 |
| M6 | VanillaMaterialMap 擴展測試 | ✅ | 重寫為參數化測試，覆蓋全 11 木材、16 色混凝土、玻璃、磚、砂、黑曜石、基岩 + 材料參數驗證 |

---

## 新增/修改檔案清單

### 新增檔案 (7)

| 檔案 | 套件 | 說明 |
|------|------|------|
| `CableNode.java` | physics | XPBD 纜索模擬節點 |
| `CableState.java` | physics | 纜索模擬狀態（包裝 CableElement + 節點鏈） |
| `ChunkEventHandler.java` | event | Forge ChunkEvent.Unload 處理器 |
| `BlockTypeRegistry.java` | material | 動態方塊類型註冊表 |
| `SidecarBridgeSecurityTest.java` | sidecar (test) | SidecarBridge 路徑安全測試 |
| `DefaultCuringManagerTest.java` | spi (test) | 養護管理器整合測試 |
| `BlockTypeRegistryTest.java` | material (test) | 動態類型註冊表測試 |

### 重大修改檔案 (8)

| 檔案 | 修改內容 |
|------|---------|
| `DefaultCableManager.java` | 完全重寫：CableState 存儲 + XPBD 物理 tick + chunk unload |
| `ForceEquilibriumSolver.java` | SOR warm-start cache + @NotThreadSafe + keys() 修復 |
| `SPHStressEngine.java` | import 修復 + @ThreadSafe + CompletableFuture 重構 |
| `LoadPathEngine.java` | onChunkUnload() + @NotThreadSafe |
| `RBlockEntity.java` | chunkUnloading flag + onChunkUnloaded() |
| `ICableManager.java` | removeChunkCables() 新增 |
| `BlockType.java` | isKnownType() + Javadoc 更新 |
| `VanillaMaterialMapTest.java` | 完全重寫為參數化測試 |

### 輕微修改檔案 (6)

| 檔案 | 修改 |
|------|------|
| `ModuleRegistry.java` | 移除重複 import + @ThreadSafe |
| `build.gradle` | JUnit 5 + JSR-305 依賴 |
| `UnionFindEngine.java` | @ThreadSafe |
| `DefaultCuringManager.java` | @ThreadSafe |
| `BeamElement.java` | @Immutable |
| `CableElement.java` | @Immutable |

---

## 關鍵技術決策

### XPBD 物理模型
- **Compliance α̃ = 1e-6 / dt²** — 鋼索級剛性，與迭代次數解耦
- **5 次約束迭代** — 精度/性能平衡
- **速度阻尼 0.98** — 每 tick 2% 能量損失，防止振盪
- **NaN/Inf 保護** — 數值發散時自動回退到前一幀位置

### BlockType 擴展策略
- **保留 enum** — 核心 5 種類型（switch 穩定性 + 序列化向後相容）
- **ConcurrentHashMap Registry** — CI 模組可註冊新類型
- **名稱衝突防護** — 禁止註冊與 enum 同名的擴展
- **統一查詢** — `resolveStructuralFactor()` 同時解析核心和擴展

### CableTensionEvent 決策
- DefaultCableManager 不持有 ServerLevel 引用 → 無法 post Forge event
- 改為日誌記錄斷裂事件，由上層（TickHandler）負責 event posting

---

## 語法驗證結果

| 檢查項目 | 結果 |
|----------|------|
| 所有 Java 檔案 import 正確 | ✅ |
| 方法簽名匹配介面 | ✅ |
| 括號/泛型平衡 | ✅ |
| String.format 格式正確 | ✅ |
| ConcurrentHashMap.keys() 修復 | ✅ (→ keySet().iterator().next()) |

**最終狀態**: 全部 14 項任務完成，0 個已知編譯錯誤。
