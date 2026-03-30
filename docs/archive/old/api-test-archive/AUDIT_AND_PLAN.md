# Block Reality — 全面審計報告 & 開發計劃

**日期：** 2026-03-24
**專案：** Block Reality API (Minecraft Forge 1.20.1-47.2.0)
**檔案總數：** 62 個 Java 檔案

---

## 一、v3fix 章節完成度總覽

| 章節 | 名稱 | 狀態 | 對應檔案 |
|------|------|------|----------|
| §0.1–0.4 | 開發環境建置 | ✅ 完成 | build.gradle, mods.toml |
| §1.1 | R氏材料系統 | ✅ 完成 | RMaterial, DefaultMaterial, DynamicMaterial, BlockType |
| §1.2 | RBlock/BlockEntity | ✅ 完成 | RBlock, RBlockEntity, BRBlocks, BRBlockEntities |
| §1.2.5 | 快照層架構 | ✅ 完成 | RBlockState, RWorldSnapshot, SnapshotBuilder, ResultApplicator |
| §1.3 | Union-Find 引擎 | ✅ 完成 | UnionFindEngine, IStructureEngine, StructureResult |
| §1.4 | RC 節點融合 | ✅ 完成 | RCFusionDetector, IFusionDetector, FusionResult |
| §1.5 | 連續性錨定檢測 | ✅ 完成 | AnchorContinuityChecker, IAnchorChecker, AnchorResult |
| §1.6 | 支撐路徑分析 | ✅ 完成 | SupportPathAnalyzer, LoadPathEngine, StressField |
| §1.7 | SPH 應力引擎 | ✅ 完成 | SPHStressEngine |
| §1.8 | 應力熱圖渲染 | ✅ 完成 | StressHeatmapRenderer, ClientStressCache, StressSyncPacket |
| §2.1 | CLI 指令系統 | ✅ 完成 | FdCommandRegistry + 9 個 Command 類別 |
| §2.2 | 三視角 CAD 介面 | ✅ 完成 | FastDesignScreen, OpenCadScreenPacket, ClientSetup |
| §2.3 | 藍圖格式定義 | ✅ 完成 | Blueprint, BlueprintNBT, BlueprintIO |
| §2.4 | TypeScript Sidecar | ✅ 完成 | NurbsExporter, SidecarBridge |
| §3.1 | 藍圖全息投影 | ✅ 完成 | HologramState, HologramRenderer, HologramSyncPacket, HologramCommand |
| §3.2 | 施工工序狀態機 | ✅ 完成 | ConstructionPhase, ConstructionZone, ConstructionZoneManager, ConstructionEventHandler, ConstructionCommand |
| §3.3 | RC 工法實作 | ⚠️ 部分完成 | RCFusionDetector 已有蜂窩邏輯，缺 ConcreteHoneycombGenerator、CuringBlockEntity |
| §3.4 | 坍方系統整合 | ✅ 完成 | CollapseManager, RStructureCollapseEvent, BlockPhysicsEventHandler, ServerTickHandler |
| §3.5 | PBD 鋼索物理 | ❌ 未開始 | 需建立 RopeNode, VerletRope, RopeManager, RopeRenderer |
| §3.6 | R氏應力掃描儀 | ❌ 未開始 | 需建立 StressScannerItem, AnchorPathHighlighter, ScannerNetworkHandler |
| §4.1–4.4 | 整合測試與優化 | 📋 方法論 | 非程式碼，壓測腳本/除錯指南 |
| §5.1–5.4 | 高三專題包裝 | 📋 文件 | 論文結構/實驗設計/Demo腳本 |

**進度：** 18/21 程式碼章節完成（85.7%），剩餘 §3.3 補完 + §3.5 + §3.6

---

## 二、程式碼審計結果

### 🔴 HIGH — 必須立即修復

**BUG-H1：BlockPhysicsEventHandler.java — null-check 競態**
```
位置：BlockPhysicsEventHandler.java:85-89
問題：getSupportParent() 呼叫兩次，第二次時值可能已改變
修法：用局部變數原子化讀取
```

**BUG-H2：HologramSyncPacket.java — NBT decode 缺 null guard**
```
位置：HologramSyncPacket.java:89 → buf.readNbt() 可能 return null
影響：BlueprintNBT.read(null) 將 NPE
修法：加 null 檢查提前 return
```

### 🟡 MEDIUM — 架構一致性問題

**ARCH-M1：SPHStressEngine 未實作 IStressEngine 介面**
```
問題：IStressEngine.solve(RWorldSnapshot, BlockPos, float) 無對應實作
SPHStressEngine 走 Forge 事件驅動，不走 snapshot 模式
處理方案：新增 adapter 類別或保持現況並標記 TODO
```

**ARCH-M2：RCFusionDetector 未實作 IFusionDetector 介面**
```
問題：IFusionDetector.detect(RWorldSnapshot) 設計為 snapshot 模式
RCFusionDetector.checkAndFuse() 直接操作 ServerLevel
處理方案：同上，snapshot 版本留給未來需求
```

### 🟢 LOW — 不影響執行

- AnchorContinuityChecker:89 — `rbs == null` 永遠不成立（getBlock 不回傳 null）
- BlueprintIO:241 — 未知 materialId 靜默回退 CONCRETE，缺 debug log
- FastDesignScreen:310 — getMapColor() 傳 null 參數（try-catch 已保護）

### ✅ 全數通過的類別（48+ 個檔案）

所有 registry、material、blueprint、physics core、snapshot、event handler（除 BUG-H1）、command、client rendering、config、network（除 BUG-H2）、SPH、executor 類別均通過靜態分析。

---

## 三、開發計劃 — 剩餘階段

### Phase A：Bug 修復（預估 30 分鐘）

| # | 任務 | 檔案 |
|---|------|------|
| A1 | 修復 BlockPhysicsEventHandler null-check 競態 | BlockPhysicsEventHandler.java |
| A2 | 修復 HologramSyncPacket NBT null guard | HologramSyncPacket.java |
| A3 | 請用戶跑 `gradlew compileJava` 驗證零錯誤 | — |

### Phase B：§3.3 RC 工法補完（預估 2–3 小時）

v3fix §3.3 需要三個核心機制，目前只有機制 1（鋼筋間距 + 蜂窩機率）已嵌入 RCFusionDetector。

| # | 任務 | 新建檔案 | 說明 |
|---|------|----------|------|
| B1 | 蜂窩弱點生成器 | `construction/ConcreteHoneycombGenerator.java` | 澆灌時掃描區域，根據 rebar spacing 判定蜂窩位置 |
| B2 | 養護計時 BlockEntity | `construction/CuringBlockEntity.java` | tick 計數到 curing_ticks=2400，未完成前 Rcomp×0.3 |
| B3 | 養護 BlockEntity 註冊 | `registry/BRBlockEntities.java` 修改 | DeferredRegister 新增 CURING_BLOCK_ENTITY |
| B4 | 養護完成回呼 | 修改 AnchorContinuityChecker | 養護完成 → 觸發錨定重算 |
| B5 | BRConfig 新增養護參數 | `config/BRConfig.java` 修改 | curingTicks, curingPenalty 等 |

### Phase C：§3.5 PBD 鋼索物理（預估 3–4 小時）

全新模組，4 個新檔案。

| # | 任務 | 新建檔案 | 說明 |
|---|------|----------|------|
| C1 | 鋼索質點類別 | `rope/RopeNode.java` | 位置、前一位置、質量、是否固定 |
| C2 | Verlet 積分引擎 | `rope/VerletRope.java` | tick 更新 + 距離約束迭代 3–5 次 + 斷裂檢測 |
| C3 | 全局鋼索管理器 | `rope/RopeManager.java` | ConcurrentHashMap 管理所有 VerletRope 實例 |
| C4 | 鋼索渲染器 | `rope/RopeRenderer.java` | RenderLevelStageEvent + LineRenderer |
| C5 | Config 參數 | BRConfig 修改 | ropeGravity, ropeBreakForce, ropeIterations |
| C6 | 指令整合 | FdCommandRegistry 修改 | /fd rope create, /fd rope remove |
| C7 | 註冊 + 事件掛接 | BlockRealityMod 修改 | RopeManager tick 掛到 ServerTickHandler |

### Phase D：§3.6 R氏應力掃描儀（預估 2–3 小時）

手持物品 + 兩種掃描模式 + 網路封包。

| # | 任務 | 新建檔案 | 說明 |
|---|------|----------|------|
| D1 | 掃描儀物品類別 | `scanner/StressScannerItem.java` | useOn() 右鍵掃描，Shift+右鍵切模式 |
| D2 | 錨定路徑高亮 | `scanner/AnchorPathHighlighter.java` | Client-side BFS 高亮（綠/紅） |
| D3 | 掃描儀網路封包 | `scanner/ScannerNetworkPacket.java` | Server → Client 同步掃描結果 |
| D4 | Item 註冊 | BRBlocks 修改 | DeferredRegister 註冊 STRESS_SCANNER item |
| D5 | BRNetwork 新增封包 | BRNetwork 修改 | 註冊 ScannerNetworkPacket |
| D6 | ClientSetup 掛接 | ClientSetup 修改 | 註冊 AnchorPathHighlighter 渲染 |

### Phase E：整合驗證（預估 1 小時）

| # | 任務 |
|---|------|
| E1 | 全量 `gradlew compileJava` 零錯誤 |
| E2 | 靜態交叉引用覆核（所有新增類別 vs 現有類別） |
| E3 | 檢查所有 DeferredRegister 無 ID 衝突 |
| E4 | 檢查所有 Network Packet discriminator 無重號 |

---

## 四、建議執行順序

```
Phase A (Bug Fix) → 用戶驗證編譯
    ↓
Phase B (§3.3 RC 工法補完) → 編譯驗證
    ↓
Phase C (§3.5 鋼索物理) → 編譯驗證
    ↓
Phase D (§3.6 掃描儀) → 編譯驗證
    ↓
Phase E (全量整合驗證)
```

每個 Phase 結束後請用戶跑 `.\gradlew.bat compileJava` 確認無新錯誤再進入下一 Phase。

---

## 五、檔案總量預估

| 類別 | 現有 | 新增 | 修改 |
|------|------|------|------|
| Java 檔案 | 62 | ~8 | ~6 |
| 最終檔案數 | ~70 | — | — |
