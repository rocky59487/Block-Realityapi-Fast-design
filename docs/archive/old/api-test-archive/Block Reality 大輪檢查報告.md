# Block Reality API — 大輪檢查審計報告

**日期**: 2026-03-24
**範圍**: `com.blockreality.api.*` 全套件
**方法**: 交叉比對想法.docx + v3fix 手冊 + 外部模組/演算法搜尋

---

## 一、想法.docx vs API 實作完整度

### 1.1 已正確實作的功能

| 想法條目 | API 實作 | 狀態 |
|----------|----------|------|
| RMaterial 材料系統（12 種預設材料） | `DefaultMaterial` 枚舉 + `DynamicMaterial` record + `RMaterial` 介面 | ✅ 完整 |
| RC Fusion（鋼筋+混凝土→鋼筋混凝土） | `SupportPathAnalyzer.getConnectionMaterial()` 內聯實作 | ✅ 完整 |
| Anchor Continuity BFS | `UnionFindEngine` + `SupportPathAnalyzer` 錨定檢查 | ✅ 完整 |
| 三層架構 Snapshot→計算→ResultApplicator | `SnapshotBuilder` → `SPHStressEngine` / `SupportPathAnalyzer` → 寫回 | ✅ 完整 |
| 藍圖系統 (.brblp) | `Blueprint` + `BlueprintNBT` + `BlueprintIO` + `BlueprintCommand` | ✅ 完整 |
| 全息投影 | `HologramCommand` + `HologramSyncPacket` + 客戶端渲染 | ✅ 完整 |
| FastDesign CAD | `FastDesignScreen` + `SidecarBridge` JSON-RPC | ✅ 完整 |
| Support Tree（支撐樹） | `LoadPathEngine` O(H) 樹狀追蹤 | ✅ 完整 |
| 坍塌管理（批次 FallingBlock） | `CollapseManager` max 20/tick 限流 | ✅ 完整 |
| SPH 應力引擎（降級版距離衰減） | `SPHStressEngine` CompletableFuture 異步 | ✅ 完整 |
| BRConfig 參數表 | 所有 v3fix §4 參數已對齊 | ✅ 完整 |

### 1.2 部分缺失 — 需要補充的功能

#### ① RStructure 獨立運行時資料類

**想法描述**: RStructure 作為運行時結構概覽物件，包含 `nodeSet`、`compositeR`（結構綜合抗性）、`anchorPoints` 集合。

**現狀**: `Blueprint.BlueprintStructure` 有類似欄位，但這是序列化用的靜態資料。運行時物理計算中，結構資訊散布在 `UnionFindEngine`（連通分量）和 `SupportPathAnalyzer`（應力結果）之間，沒有統一的 `RStructure` 物件可供查詢。

**建議**: 新增 `com.blockreality.api.physics.RStructure` record：

```java
public record RStructure(
    int structureId,
    Set<BlockPos> nodeSet,
    double compositeR,      // 結構綜合抗性
    Set<BlockPos> anchorPoints,
    int blockCount,
    double totalLoad
) {}
```

用途：(1) 給 /br_stress 指令提供結構摘要，(2) 給未來的 Construction Intern 模組提供查詢介面。

**優先級**: 🟡 中 — 不影響核心物理，但影響模組間 API 清晰度。

#### ② AnchorPathVisualizer（錨定路徑視覺化）

**想法描述**: 客戶端渲染鋼筋→錨定點的 BFS 路徑，以半透明線段顯示。

**現狀**: 完全未實作。目前只有 `StressSyncPacket` 同步應力色彩，沒有路徑數據的同步封包。

**建議**: 分兩步實作：
1. 新增 `AnchorPathSyncPacket`（S→C），攜帶 `List<BlockPos>` 路徑節點
2. 客戶端 `AnchorPathRenderer` 在 `RenderLevelStageEvent` 繪製半透明線段

**優先級**: 🟢 低 — 開發輔助功能，非核心物理。

### 1.3 正確不在 API 中的功能（屬於後續模組）

| 想法條目 | 歸屬模組 |
|----------|----------|
| R氏應力掃描儀（手持物品） | Construction Intern |
| PBD/Verlet 繩索物理 | Construction Intern |
| 塔式起重機實體 | Construction Intern |
| 施工區域 6 階段狀態機 | Construction Intern（API 已有 `ConstructionCommand` 前端） |
| §3.3 鋼筋/模板/澆灌物品 | Construction Intern |
| §3.5 3D 列印機 | Construction Intern |
| §3.6 結構健康監測儀 | Construction Intern |

---

## 二、v3fix 手冊 vs API 實作正確性

### 2.1 架構決策驗證

| v3fix 決策 | 實作狀態 | 備註 |
|------------|----------|------|
| AD-1: BaseEntityBlock + BlockEntity | ✅ 正確 | `RBlock` extends `BaseEntityBlock`, `RBlockEntity` extends `BlockEntity` |
| AD-2: CompletableFuture + ExecutorService | ✅ 正確 | `SPHStressEngine` 用 ThreadPoolExecutor(1,2) + DiscardOldestPolicy |
| AD-3: JSON-RPC for Sidecar | ✅ 正確 | `SidecarBridge` stdio JSON-RPC 2.0 |
| AD-4: Verlet Integration for Ropes | ⬜ 未實作 | 正確，屬於 Construction Intern 模組 |
| AD-5: Custom Screen for CAD | ✅ 正確 | `FastDesignScreen` 正交 CAD |
| AD-6: Incremental BFS + Dirty Flag | ✅ 正確 | `UnionFindEngine` epoch + dirty flag |
| AD-7: Versioned Epoch for UF Deletion | 🟡 部分 | epoch 機制存在，但完整的 delete-with-epoch 回收模式未用上（見下） |

### 2.2 AD-7 Versioned Epoch 詳細分析

v3fix 描述的完整模式：當方塊被破壞時，標記 dirty + 遞增 epoch，下次查詢時才重建連通分量（lazy rebuild）。目前 `UnionFindEngine` 有 `epoch` 和 `dirty` 欄位，`markDirty()` 會遞增 epoch，但 `find()` / `union()` 的實際回收邏輯（檢測舊 epoch 的殭屍節點並清除）並未完整實作。

**影響**: 長時間運行的伺服器中，被破壞方塊的 UF 節點不會被清除，導致 `parentMap` 緩慢增長。

**建議**: 在 `find()` 中加入 epoch 檢查：
```java
// 若節點的 epoch < 當前 globalEpoch，且方塊已不存在，從 parentMap 移除
if (nodeEpoch < globalEpoch && !level.getBlockState(pos).is(BRBlocks.R_BLOCK.get())) {
    parentMap.remove(pos);
    return pos; // 孤立節點
}
```

**優先級**: 🟡 中 — 短期可用，但長期記憶體洩漏風險。

### 2.3 Config 參數對照

所有 `BRConfig` 參數已與 v3fix §4 Config Table 逐一比對，完全吻合：

- RC fusion: `phi_tens=0.8`, `phi_shear=0.6`, `comp_boost=1.1`, `rebar_spacing_max=3`, `honeycomb_prob=0.15`, `curing_ticks=2400` ✅
- Physics: `sph_trigger_radius=5`, `sph_max_particles=200`, `anchor_bfs_max_depth=64` ✅
- Structure: `bfs_max_blocks=2048`, `bfs_max_ms=50`, `snapshot_max_radius=20`, `scan_margin_default=4`, `cycle_detect_max_depth=8` ✅

### 2.4 公式驗證

| 公式 | v3fix 規格 | 實作 | 狀態 |
|------|-----------|------|------|
| 懸臂力矩 | M = W × D² | `SupportPathAnalyzer`: `moment = accumulatedWeight * dist * dist` | ✅ |
| 壓碎判定 | ΣW > Rcomp × 1e6 | `SupportPathAnalyzer`: `totalLoad > rcomp * 1e6` | ✅ |
| RC 融合 Rtens | φ_tens × (Rc_tens + Rs_tens) | `getConnectionMaterial()` 內聯計算 | ✅ |
| RC 融合 Rshear | φ_shear × (Rc_shear + Rs_shear) | 同上 | ✅ |
| RC 融合 Rcomp | comp_boost × max(Rc_comp, Rs_comp) | 同上 | ✅ |
| SPH 應力 | basePressure / d² × materialFactor / Rcomp | `SPHStressEngine.computeStress()` | ✅ |

---

## 三、外部演算法與功能移植建議

### 3.1 Voxelyze — Euler-Bernoulli 梁元素模型

**來源**: NASA/Cornell 開源 C++ 體素物理引擎
**原理**: 相鄰體素之間建立 Euler-Bernoulli 梁元素（6-DOF），計算軸力、剪力、彎矩。每個梁有截面慣性矩 I、彈性模量 E、截面積 A。

**對比現有系統**:
- 現行 `SupportPathAnalyzer` 用 Weighted BFS + 簡化懸臂公式，只考慮垂直荷載
- Euler-Bernoulli 能捕捉水平推力、斜向力矩、扭轉，物理精確度高一個數量級

**移植方案**:
```
RBlockState 新增: elasticModulus (E), momentOfInertia (I)
新增 BeamElement 類: 連接兩個相鄰 RBlockState
新增 BeamStressEngine: 遍歷所有 BeamElement 計算內力
```

**可行性評估**: 🟡 中等難度。主要挑戰：(1) Minecraft 方塊是離散的，梁元素需要定義在相鄰面之間，(2) 求解需要稀疏矩陣線性系統，計算量遠大於 BFS，需要異步+LOD 策略。

**建議**: 作為 `SupportPathAnalyzer` 的可選高精度後端，對小型結構（<500 blocks）啟用，大型結構仍用 BFS 快速近似。

**優先級**: 🔴 高 — 顯著提升物理真實度，是與其他物理模組最大的差異化賣點。

### 3.2 Teardown 風格連續結構完整性

**來源**: Teardown 遊戲（Dennis Gustafsson）
**原理**: 每次結構變化時，從錨定點做 flood fill，未被 flood fill 到的連通分量即為「懸浮」，立即觸發坍塌。持續重算而非事件觸發。

**對比現有系統**:
- 現行 `UnionFindEngine` 用 Union-Find + dirty flag，是惰性重算
- Teardown 是主動式重算，能更快發現斷裂

**移植方案**: 將 `UnionFindEngine.rebuildConnectedComponents()` 改為從錨定點出發的 BFS（而非遍歷所有方塊再找連通分量），未被 BFS 到達的即為懸浮。

```java
// 從所有 anchor 點出發做 multi-source BFS
Set<BlockPos> reachable = multiSourceBFS(anchorPoints);
// 不在 reachable 中的 = 需要坍塌
Set<BlockPos> floating = allNodes.minus(reachable);
```

**可行性評估**: 🟢 低難度。邏輯簡單，且現有 BFS 基礎設施已經完備。

**建議**: 替換 `UnionFindEngine` 的核心重建邏輯為 anchor-source BFS，可大幅簡化程式碼同時提升正確性。

**優先級**: 🔴 高 — 改動小、收益大。

### 3.3 Realistic Block Physics — 材料定義擴充

**來源**: Realistic Block Physics (Modrinth/CurseForge)
**特點**: 為每個原版方塊定義獨立的物理屬性（硬度→支撐力映射），包含洞穴加固演算法（cave strengthening）。

**對比現有系統**:
- 現行 `SnapshotBuilder.mapVanillaBlock()` 只映射 8 種方塊類型，其餘全部 fallback 到 STONE
- Realistic Block Physics 覆蓋了 100+ 種方塊

**移植方案**: 擴充 `mapVanillaBlock()` 的映射表，或改用 JSON 數據驅動的方塊→材料映射：

```json
// config/blockreality/vanilla_material_map.json
{
  "minecraft:oak_planks": "TIMBER",
  "minecraft:oak_stairs": "TIMBER",
  "minecraft:oak_slab": "TIMBER",
  "minecraft:oak_fence": "TIMBER",
  "minecraft:dark_oak_planks": "TIMBER",
  ...
  "minecraft:copper_block": "STEEL",
  "minecraft:waxed_copper_block": "STEEL",
  ...
}
```

**可行性評估**: 🟢 非常低難度。只是擴充數據，不改架構。

**建議**: 將硬編碼的 if-else 鏈改為 JSON 數據驅動，允許 modpack 作者自定義映射。

**優先級**: 🔴 高 — 最小工作量、最大覆蓋提升。

### 3.4 Block Physics Overhaul — 梁強度模型

**來源**: Block Physics Overhaul mod
**特點**: 引入「梁強度」概念，方塊水平延伸時有最大支撐距離，超過則斷裂。類似真實的樑跨距限制。

**對比現有系統**: 現行懸臂公式 `M = W × D²` 已經隱含了類似概念（距離平方衰減），但沒有明確的「最大跨距」參數。

**移植方案**: 在 `RMaterial` 介面新增 `getMaxSpan()` 方法：
```java
default int getMaxSpan() { return 8; } // 預設 8 格
```
`SupportPathAnalyzer` 在 BFS 中加入跨距檢查：若水平距離 > maxSpan，直接判定 CANTILEVER_BREAK。

**優先級**: 🟡 中 — 可作為懸臂公式的補充，提供更直覺的設計指引。

### 3.5 GPU 加速 BFS（未來優化）

**來源**: 多篇學術論文（Parallel BFS on GPU, NVIDIA CUDA samples）
**原理**: 將 BFS 的層級展開並行化，每層所有節點同時處理。

**現狀**: `UnionFindEngine` 的 BFS 是單線程 + BitSet，已經很快（2048 blocks / 50ms 預算）。

**評估**: 目前不需要。當結構規模超過 10K blocks 時可以考慮。Minecraft Forge 的 GPU 存取需要 OpenCL/Compute Shader，整合成本高。

**優先級**: ⚪ 擱置 — 目前效能足夠。

---

## 四、本輪已修復的問題

| 編號 | 問題 | 修復 |
|------|------|------|
| B-6 | BlueprintCommand 重複選取系統 | 委託給 PlayerSelectionManager |
| B-7 | ConstructionCommand 重複選取系統 | 委託給 PlayerSelectionManager |
| B-8 | HologramCommand 與 FdCommandRegistry Brigadier /fd 衝突 | 改為 buildHologramNode() 子樹模式 |
| B-9 | UnionFindEngine 硬編碼 BFS 限制 (512/15ms) 與 BRConfig 不一致 | 改為動態讀取 BRConfig |
| T-4 | SnapshotBuilder 遺漏 RBlockEntity 材料數據 | 新增 chunk.getBlockEntity() + translateWithEntity() |
| C-1 | PlayerSelectionManager 未使用的 import | 移除 StreamSupport |
| C-2 | MaterialInfoCommand /br_material set 無效 materialId 靜默回退 | 新增驗證邏輯 |

---

## 五、優先行動清單

### 立即可做（1-2 天）

1. **材料映射數據驅動化** — 將 `mapVanillaBlock()` 改為 JSON config，覆蓋 100+ 方塊
2. **Teardown 式 anchor-source BFS** — 替換 `UnionFindEngine` 重建邏輯
3. **AD-7 epoch 回收補全** — 在 `find()` 加入舊節點清除

### 中期改善（1-2 週）

4. **RStructure 運行時資料類** — 統一結構查詢介面
5. **RMaterial.getMaxSpan()** — 梁跨距限制
6. **AnchorPathSyncPacket** + 客戶端渲染

### 長期研發（1 個月+）

7. **Euler-Bernoulli 梁元素引擎** — 作為 SupportPathAnalyzer 的高精度替代
8. **GPU 加速 BFS**（僅在需要時）

---

## 六、結論

Block Reality API 的核心物理系統已經完整實作 v3fix 規格，與想法.docx 的 API 層級功能高度吻合。主要的改進空間在於：

1. **物理精確度** — 引入 Euler-Bernoulli 梁模型將是最大的技術躍進
2. **覆蓋度** — 原版方塊映射從 8 種擴充到 100+ 種，成本極低但效果顯著
3. **穩健性** — anchor-source BFS 和 epoch 回收能消除長期運行的潛在問題
4. **API 清晰度** — RStructure 作為模組間契約，為 Construction Intern 鋪路

建議下一步：先做 §五 的「立即可做」三項，花費最小但覆蓋了最高優先級的改善。
