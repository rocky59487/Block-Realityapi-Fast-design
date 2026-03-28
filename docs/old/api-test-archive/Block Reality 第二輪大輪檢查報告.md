# Block Reality API — 第二輪大輪檢查報告

**日期**: 2026-03-24
**審核範圍**: 全部 69 個 Java 原始檔
**本輪重點**: 代碼品質審核 + 第一輪報告 7 項行動完成度驗證

---

## 一、第一輪報告完成度驗證

| # | 行動項目 | 狀態 | 說明 |
|---|---------|------|------|
| 1 | JSON 資料驅動材質映射 (VanillaMaterialMap) | ✅ 已完成 | 260+ 原版方塊對應，JSON 配置，singleton 架構 |
| 2 | Teardown 式錨定源 BFS | ✅ 已完成 | `UnionFindEngine.validateLocalIntegrity()` 增量式檢查，與 CollapseManager 整合 |
| 3 | AD-7 Epoch 快取回收 | ✅ 已完成 | `evictStaleEntries()` 每 200 ticks 驅逐，閾值 64 epoch |
| 4 | RStructure 執行期資料類別 | ✅ 已完成 | record 型態，含 healthScore/isStable/isCritical 輔助方法 |
| 5 | RMaterial.getMaxSpan() | ✅ 已完成 | default 方法，`clamp(sqrt(Rtens)×2, 1, 64)`，已整合到 SupportPathAnalyzer |
| 6 | AnchorPathSyncPacket + 客戶端渲染 | ✅ 已完成 | S→C 封包 + AnchorPathCache + AnchorPathRenderer (DEBUG_LINES) |
| 7 | Euler-Bernoulli 梁應力引擎 | ✅ 已完成 | BeamElement + BeamStressEngine，異步 CompletableFuture |

**結論：第一輪 7 項行動全數完成。**

---

## 二、本輪發現問題與修復

本輪全面審核 69 個 Java 檔案，發現並修復 **7 個問題**：

### 問題 1：CollapseManager 缺少 enqueueCollapse 方法 (編譯失敗)

- **嚴重度**: 🔴 致命 (編譯無法通過)
- **位置**: `CollapseManager.java`
- **原因**: `BlockPhysicsEventHandler` 呼叫 `CollapseManager.enqueueCollapse(level, floatingBlocks)` 但方法不存在
- **修復**: 新增 `enqueueCollapse(ServerLevel, Set<BlockPos>)` 方法，發送 `RStructureCollapseEvent` 後逐一加入 collapseQueue

### 問題 2：BeamElement 死碼

- **嚴重度**: 🟡 低 (不影響行為)
- **位置**: `BeamElement.java` 第 62 行
- **原因**: `E = weaker.getRcomp() * 1e9` 立即被第 64 行 `E = weaker.getRcomp() * 1e6 * 1000` 覆蓋（數值相同但為死碼）
- **修復**: 移除多餘的第 62 行

### 問題 3：BeamStressEngine 潛在 NPE

- **嚴重度**: 🟠 中等
- **位置**: `BeamStressEngine.java` `captureStructure()` 第 147 行
- **原因**: `rbe.getMaterial()` 可能返回 null，後續 `mat.getDensity()` 會 NPE
- **修復**: 新增 `if (mat == null) mat = DefaultMaterial.STONE;`

### 問題 4：VanillaMaterialMap 脆弱的 fromId 偵測

- **嚴重度**: 🟠 中等
- **位置**: `VanillaMaterialMap.java` `loadDefaults()`
- **原因**: 原始邏輯用 `DefaultMaterial.fromId()` 回傳值是否為 CONCRETE 來判斷 ID 是否有效，但 fromId fallback 就是 CONCRETE，導致合法 "concrete" 映射被誤判
- **修復**: 改用 HashMap 查表 materialId → DefaultMaterial，null = 未知 ID → fallback STONE

### 問題 5：ServerTickHandler 呼叫不存在的 API

- **嚴重度**: 🔴 致命 (編譯錯誤)
- **位置**: `ServerTickHandler.java`
- **原因**: Forge 1.20.1 的 `TickEvent.ServerTickEvent` 沒有 `getServer()` 方法
- **修復**: 改用 `ServerLifecycleHooks.getCurrentServer()` 靜態取得

### 問題 6：AnchorPathRenderer 渲染 API 錯誤

- **嚴重度**: 🔴 致命 (編譯錯誤)
- **位置**: `AnchorPathRenderer.java`
- **原因**: 使用 `tesselator.end()` 而非 Forge 1.20.1 正確模式
- **修復**: 改為 `BufferUploader.drawWithShader(buffer.end())`，與現有 StressHeatmapRenderer 一致

### 問題 7：多處無用 import

- **嚴重度**: ⚪ 微不足道
- **位置**: BeamStressEngine (`BRConfig`), UnionFindEngine (`BRBlocks`), VanillaMaterialMap (缺 `HashMap`)
- **修復**: 移除無用 import，補上缺失 import

---

## 三、架構健康度評估

### 良好之處

1. **模組化清晰**：physics / material / collapse / network / client / event 各 package 職責分明
2. **執行緒安全**：VanillaMaterialMap 用 ConcurrentHashMap，AnchorPathCache 用 volatile 引用交換
3. **效能意識**：養護檢查 20 tick 間隔、快取驅逐 200 tick 間隔、BeamStressEngine 異步執行
4. **防禦性程式設計**：null check、空集合檢查、渲染距離限制
5. **Forge 1.20.1 慣例**：正確使用 `@SubscribeEvent`、`@Mod.EventBusSubscriber`、`PoseStack` 等

### 需注意事項

1. **BeamStressEngine 執行緒安全**：`captureStructure()` 在主線程呼叫 `level.getBlockState()`/`getBlockEntity()` 是正確的，但要確保 snapshot 的 RMaterial 物件是不可變的（目前 DefaultMaterial 是 enum，安全）
2. **AnchorPathSyncPacket 大小**：大型結構的 BFS 路徑可能產生巨大封包，未來考慮加入大小限制或分片
3. **VanillaMaterialMap 260+ 預設值**：JSON 預設值寫在程式碼中，首次啟動才寫入檔案，可運作但 JSON 檔會很大

---

## 四、待編譯驗證

以上所有修復已套用至原始碼。請執行：

```
.\gradlew.bat compileJava
```

確認編譯通過後，本輪大輪檢查正式結案。

---

## 五、下一步建議

1. **單元測試**：BeamStressEngine 和 VanillaMaterialMap 是新的核心邏輯，應撰寫測試
2. **封包大小限制**：AnchorPathSyncPacket 加入 MAX_PATH_SIZE 保護
3. **進入 construction 模組開發**：API 層的 7 項行動已全數完成，可開始 §3.3/§3.5/§3.6 的 `com.blockreality.construction.*` 模組
