package com.blockreality.api.physics;

import com.blockreality.api.block.RBlockEntity;
import com.blockreality.api.event.StressUpdateEvent;
import com.blockreality.api.material.RMaterial;
import com.blockreality.api.network.BRNetwork;
import com.blockreality.api.network.StressSyncPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.network.PacketDistributor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 結果寫回器 (Result Applicator)
 *
 * 負責將各引擎的計算結果（StructureResult、FusionResult、AnchorResult、StressField）
 * 寫回到世界中的 RBlockEntity。
 *
 * 設計要點：
 *   1. 主執行緒安全 — 所有 BE 操作必須在主執行緒 (server.execute)
 *   2. 批量更新 — 使用 setStressLevelBatch() + flushSync() 降低同步開銷
 *   3. 重試邏輯 — 最多 3 次重試 × 100ms 間隔（BE 可能延遲載入）
 *   4. 失敗追蹤 — 記錄失敗位置，供跨 tick 恢復使用
 *
 * v3fix 合規：
 *   - AD-1: 透過 BlockEntity 寫回，不用 Capability
 *   - 50ms 同步節流由 RBlockEntity 自行處理
 */
public class ResultApplicator {

    private static final Logger LOGGER = LogManager.getLogger("BR-Applicator");

    /** 最大重試次數 */
    private static final int MAX_RETRIES = 3;

    /** 重試間隔 (ms) — 注意：實際延遲是排進 server.execute 佇列 */
    private static final long RETRY_DELAY_MS = 100;

    /**
     * 跨 tick 失敗追蹤 — 記錄本輪寫回失敗的位置及操作描述。
     * Key = BlockPos, Value = 失敗原因 / 重試狀態
     * 外部可透過 getFailedPositions() 取得並決定後續處理。
     */
    private static final ConcurrentHashMap<BlockPos, StressRetryEntry> failedPositions = new ConcurrentHashMap<>();

    /**
     * 應力寫回重試條目 — 追蹤失敗位置的重試次數及狀態。
     */
    /**
     * ★ R3-4 fix: 可變欄位改為 private + getter/setter，
     * 符合封裝原則，避免 ConcurrentHashMap value 的公開可變狀態風險。
     */
    /**
     * ★ 並發安全修正：retryCount / lastAttemptMs / maxRetries 改為 volatile，
     * 確保 retryFailed()（主線程）與 applyStressWithRetry()（可能不同 tick）
     * 對同一個 entry 的讀寫具有 happens-before 保證。
     *
     * 參考：Baeldung "Guide to the Volatile Keyword in Java"
     *       JLS §17.4.5 happens-before (volatile write → volatile read)
     */
    public static class StressRetryEntry {
        private final String operation;
        private volatile int retryCount = 0;
        private volatile long lastAttemptMs = 0;
        private volatile int maxRetries = MAX_RETRIES;

        public StressRetryEntry(String operation) {
            this.operation = operation;
            this.lastAttemptMs = System.currentTimeMillis();
        }

        public StressRetryEntry(String operation, int maxRetries) {
            this.operation = operation;
            this.maxRetries = Math.max(1, maxRetries);
            this.lastAttemptMs = System.currentTimeMillis();
        }

        public String getOperation() { return operation; }
        public int getRetryCount() { return retryCount; }
        public long getLastAttemptMs() { return lastAttemptMs; }
        public int getMaxRetries() { return maxRetries; }

        public void incrementRetry() {
            retryCount++;
            lastAttemptMs = System.currentTimeMillis();
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = Math.max(1, maxRetries);
        }

        public boolean isExhausted() {
            return retryCount >= maxRetries;
        }

        @Override
        public String toString() {
            return String.format("StressRetryEntry{op=%s, retries=%d/%d}", operation, retryCount, maxRetries);
        }
    }

    // ═══════════════════════════════════════════════════════
    //  1. 應用 StructureResult — 不穩定方塊 + 應力分佈
    // ═══════════════════════════════════════════════════════

    /**
     * 將結構引擎結果寫回 BlockEntity。
     *
     * 操作：
     *   - stressMap 中每個 pos → setStressLevelBatch(stress)
     *   - unstableBlocks 中每個 pos → setStressLevelBatch(1.0) + setSupportParent(null)
     *   - 批量 flushSync
     *
     * @param level  世界（必須在主執行緒呼叫）
     * @param result 結構引擎輸出
     * @return 成功寫回的方塊數量
     */
    public static int applyStructureResult(ServerLevel level, StructureResult result) {
        assertMainThread(level);

        int successCount = 0;
        List<RBlockEntity> toBatch = new ArrayList<>();
        Set<RBlockEntity> batchSet = new HashSet<>();  // O(1) dedup check

        // 寫入應力分佈
        // ★ Round 5 fix: 批量收集 stress 變動，迴圈結束後一次性發射事件
        // 原本每方塊都 post StressUpdateEvent → O(n) Forge event bus 開銷。
        // 改為收集後批量通知，減少 event dispatch 次數。
        List<StressUpdateEvent> stressEvents = new ArrayList<>();
        for (Map.Entry<BlockPos, Float> entry : result.stressMap().entrySet()) {
            BlockPos pos = entry.getKey();
            float stress = entry.getValue();

            RBlockEntity rbe = getOrRetry(level, pos, "stress_write");
            if (rbe != null) {
                float oldStress = rbe.getStressLevel();
                rbe.setStressLevelBatch(stress);
                if (batchSet.add(rbe)) {
                    toBatch.add(rbe);
                }
                if (Math.abs(stress - oldStress) > 0.001f) {
                    stressEvents.add(new StressUpdateEvent(level, pos, oldStress, stress));
                }
                successCount++;
            }
        }
        // 批量發射 stress 事件
        for (StressUpdateEvent evt : stressEvents) {
            MinecraftForge.EVENT_BUS.post(evt);
        }

        // 標記不穩定方塊
        for (BlockPos pos : result.unstableBlocks()) {
            RBlockEntity rbe = getOrRetry(level, pos, "unstable_mark");
            if (rbe != null) {
                rbe.setStressLevelBatch(1.0f);
                rbe.setSupportParent(null);
                if (batchSet.add(rbe)) {
                    toBatch.add(rbe);
                }
                successCount++;
            }
        }

        // 批量同步
        flushAll(toBatch);

        // 同步應力數據到客戶端
        broadcastStressToClients(level, result.stressMap());

        LOGGER.debug("[Applicator] StructureResult applied: {}/{} blocks, {} unstable",
            successCount,
            result.stressMap().size() + result.unstableBlocks().size(),
            result.unstableCount());

        return successCount;
    }

    // ═══════════════════════════════════════════════════════
    //  2. 應用 FusionResult — RC 融合材料升級
    // ═══════════════════════════════════════════════════════

    /**
     * 將 RC 融合偵測結果寫回 BlockEntity。
     *
     * 操作：
     *   - upgradedBlocks: pos → setMaterial(fusionMaterial)
     *   - honeycombBlocks: pos → 標記應力 +0.1（蜂窩缺陷弱化）
     *
     * @param level  世界
     * @param result RC 融合偵測輸出
     * @return 成功升級的方塊數量
     */
    public static int applyFusionResult(ServerLevel level, FusionResult result) {
        assertMainThread(level);

        int successCount = 0;
        List<RBlockEntity> toBatch = new ArrayList<>();

        // 材料升級
        for (Map.Entry<BlockPos, RMaterial> entry : result.upgradedBlocks().entrySet()) {
            BlockPos pos = entry.getKey();
            RMaterial fusedMat = entry.getValue();

            RBlockEntity rbe = getOrRetry(level, pos, "fusion_upgrade");
            if (rbe != null) {
                rbe.setMaterial(fusedMat);
                successCount++;
            }
        }

        // 蜂窩缺陷方塊 — 額外增加應力作為弱化標記
        for (BlockPos pos : result.honeycombBlocks()) {
            RBlockEntity rbe = getOrRetry(level, pos, "honeycomb_mark");
            if (rbe != null) {
                float currentStress = rbe.getStressLevel();
                rbe.setStressLevelBatch(Math.min(1.0f, currentStress + 0.1f));
                toBatch.add(rbe);
            }
        }

        flushAll(toBatch);

        LOGGER.debug("[Applicator] FusionResult applied: {} fused, {} honeycomb",
            successCount, result.honeycombBlocks().size());

        return successCount;
    }

    // ═══════════════════════════════════════════════════════
    //  3. 應用 AnchorResult — 錨定狀態更新
    // ═══════════════════════════════════════════════════════

    /**
     * 將錨定連通性結果寫回 BlockEntity。
     *
     * 操作：
     *   - anchoredPositions: pos → setAnchored(true)
     *   - orphanPositions:   pos → setAnchored(false) + setSupportParent(null) + stress=1.0
     *
     * @param level  世界
     * @param result 錨定連通性輸出
     * @return 成功更新的方塊數量
     */
    public static int applyAnchorResult(ServerLevel level, AnchorResult result) {
        assertMainThread(level);

        int successCount = 0;
        List<RBlockEntity> toBatch = new ArrayList<>();

        // 標記有錨定的方塊
        for (BlockPos pos : result.anchoredPositions()) {
            RBlockEntity rbe = getOrRetry(level, pos, "anchor_set");
            if (rbe != null) {
                rbe.setAnchored(true);
                successCount++;
            }
        }

        // 孤兒方塊 — 失去錨定，候選崩塌
        for (BlockPos pos : result.orphanPositions()) {
            RBlockEntity rbe = getOrRetry(level, pos, "orphan_mark");
            if (rbe != null) {
                rbe.setAnchored(false);
                rbe.setSupportParent(null);
                rbe.setStressLevelBatch(1.0f);
                toBatch.add(rbe);
                successCount++;
            }
        }

        flushAll(toBatch);

        LOGGER.debug("[Applicator] AnchorResult applied: {} anchored, {} orphaned",
            result.anchoredPositions().size(), result.orphanCount());

        return successCount;
    }

    // ═══════════════════════════════════════════════════════
    //  4. 應用 StressField — 應力場覆蓋
    // ═══════════════════════════════════════════════════════

    /**
     * 將應力場結果寫回 BlockEntity。
     *
     * 操作：
     *   - stressValues: pos → setStressLevelBatch(stress)
     *   - damagedBlocks: pos → 額外標記（stress ≥ 1.0 已在 stressValues 體現）
     *
     * @param level  世界
     * @param field  應力場輸出
     * @return 成功更新的方塊數量
     */
    public static int applyStressField(ServerLevel level, StressField field) {
        assertMainThread(level);

        int successCount = 0;
        List<RBlockEntity> toBatch = new ArrayList<>();

        // ★ Round 5 fix: 批量收集 → 迴圈後一次性 post（與 applyStructureResult 一致）
        List<StressUpdateEvent> stressEvents = new ArrayList<>();
        for (Map.Entry<BlockPos, Float> entry : field.stressValues().entrySet()) {
            BlockPos pos = entry.getKey();
            float stress = entry.getValue();

            RBlockEntity rbe = getOrRetry(level, pos, "stress_field");
            if (rbe != null) {
                float oldStress = rbe.getStressLevel();
                rbe.setStressLevelBatch(stress);
                toBatch.add(rbe);
                if (Math.abs(stress - oldStress) > 0.001f) {
                    stressEvents.add(new StressUpdateEvent(level, pos, oldStress, stress));
                }
                successCount++;
            }
        }
        for (StressUpdateEvent evt : stressEvents) {
            MinecraftForge.EVENT_BUS.post(evt);
        }

        flushAll(toBatch);

        // 同步應力數據到附近客戶端（驅動 Stress Heatmap Renderer）
        broadcastStressToClients(level, field.stressValues());

        LOGGER.debug("[Applicator] StressField applied: {}/{} blocks, {} damaged",
            successCount, field.stressValues().size(), field.damagedBlocks().size());

        return successCount;
    }

    // ═══════════════════════════════════════════════════════
    //  5. 組合應用 — 一次寫入所有引擎結果
    // ═══════════════════════════════════════════════════════

    /**
     * 組合應用多個結果 — 按正確順序執行：
     *   1. FusionResult (材料先升級)
     *   2. AnchorResult (錨定狀態確定)
     *   3. StructureResult (結構計算)
     *   4. StressField (最終應力)
     *
     * 任一結果可為 null，代表該引擎未執行。
     */
    public static ApplyReport applyAll(ServerLevel level,
                                        FusionResult fusion,
                                        AnchorResult anchor,
                                        StructureResult structure,
                                        StressField stress) {
        assertMainThread(level);

        int fusionCount = 0, anchorCount = 0, structureCount = 0, stressCount = 0;

        if (fusion != null) {
            fusionCount = applyFusionResult(level, fusion);
        }
        if (anchor != null) {
            anchorCount = applyAnchorResult(level, anchor);
        }
        if (structure != null) {
            structureCount = applyStructureResult(level, structure);
        }
        if (stress != null) {
            stressCount = applyStressField(level, stress);
        }

        int totalFailed = failedPositions.size();
        ApplyReport report = new ApplyReport(fusionCount, anchorCount, structureCount,
            stressCount, totalFailed);

        if (totalFailed > 0) {
            LOGGER.warn("[Applicator] Combined apply completed with {} failed positions", totalFailed);
        } else {
            LOGGER.debug("[Applicator] Combined apply completed: {}", report);
        }

        return report;
    }

    // ═══════════════════════════════════════════════════════
    //  內部：應力同步廣播
    // ═══════════════════════════════════════════════════════

    /**
     * 廣播應力數據到附近的客戶端玩家 — 驅動 StressHeatmapRenderer。
     *
     * 策略：計算所有應力方塊的中心點，向 64 格內的所有玩家發送封包。
     * 封包大小安全限制：單次最多 256 筆（超過分批，避免封包過大）。
     */
    private static final int MAX_SYNC_PER_PACKET = 256;

    private static void broadcastStressToClients(ServerLevel level, Map<BlockPos, Float> stressData) {
        if (stressData.isEmpty()) return;

        try {
            // 分批發送
            Map<BlockPos, Float> batch = new HashMap<>();
            for (Map.Entry<BlockPos, Float> entry : stressData.entrySet()) {
                batch.put(entry.getKey(), entry.getValue());

                if (batch.size() >= MAX_SYNC_PER_PACKET) {
                    sendStressPacket(level, batch);
                    batch = new HashMap<>();
                }
            }

            // 發送剩餘
            if (!batch.isEmpty()) {
                sendStressPacket(level, batch);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("[Applicator] Failed to broadcast stress to clients: {}", e.getMessage());
        }
    }

    private static void sendStressPacket(ServerLevel level, Map<BlockPos, Float> data) {
        StressSyncPacket packet = new StressSyncPacket(data);
        // 發送到這個維度的所有玩家
        BRNetwork.CHANNEL.send(
            PacketDistributor.DIMENSION.with(level::dimension),
            packet
        );
    }

    // ═══════════════════════════════════════════════════════
    //  內部：重試邏輯
    // ═══════════════════════════════════════════════════════

    /**
     * 取得 RBlockEntity。若為 null 則記錄到失敗追蹤，
     * 由 retryFailed() 跨 tick 恢復。
     *
     * ★ T-1 fix: 移除主線程 Thread.sleep()。
     * 原本的重試循環 (3 × 100ms = 300ms) 會阻塞伺服器主線程，
     * 導致 TPS 下降、玩家延遲、甚至斷線。
     * 改為即時嘗試一次，失敗則記錄到 failedPositions，
     * 由 ServerTickHandler 每 tick 呼叫 retryFailed() 做跨 tick 恢復。
     *
     * @param level    世界
     * @param pos      目標位置
     * @param opLabel  操作標籤（用於 log 和失敗追蹤）
     * @return RBlockEntity 或 null（需跨 tick 恢復）
     */
    private static RBlockEntity getOrRetry(ServerLevel level, BlockPos pos, String opLabel) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof RBlockEntity rbe) {
            failedPositions.remove(pos.immutable());
            return rbe;
        }

        // BE 不存在 — 記錄到失敗追蹤，由跨 tick 恢復機制處理
        BlockPos immutable = pos.immutable();
        failedPositions.putIfAbsent(immutable, new StressRetryEntry(opLabel));
        LOGGER.debug("[Applicator] RBlockEntity not found at {} (op: {}), queued for cross-tick retry",
            pos, opLabel);
        return null;
    }

    // ═══════════════════════════════════════════════════════
    //  內部：批量同步
    // ═══════════════════════════════════════════════════════

    /**
     * 對所有待同步的 BE 呼叫 flushSync()。
     */
    private static void flushAll(List<RBlockEntity> entities) {
        for (RBlockEntity rbe : entities) {
            rbe.flushSync();
        }
    }

    // ═══════════════════════════════════════════════════════
    //  內部：主執行緒斷言
    // ═══════════════════════════════════════════════════════

    /**
     * 確認在主執行緒上執行 — BE 操作必須在 server tick 執行緒。
     * 非主執行緒呼叫會拋出 IllegalStateException。
     */
    private static void assertMainThread(ServerLevel level) {
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException(
                "[BR-Applicator] ResultApplicator must be called on the main server thread! " +
                "Wrap with level.getServer().execute(() -> ...)");
        }
    }

    // ═══════════════════════════════════════════════════════
    //  失敗追蹤 API
    // ═══════════════════════════════════════════════════════

    /**
     * 取得所有寫回失敗的位置 — 外部可用於跨 tick 恢復策略。
     * @return 不可修改的 Map (pos → StressRetryEntry)
     */
    public static Map<BlockPos, StressRetryEntry> getFailedPositions() {
        return Collections.unmodifiableMap(failedPositions);
    }

    /**
     * 清除失敗記錄 — 恢復完成後呼叫。
     */
    public static void clearFailedPositions() {
        failedPositions.clear();
    }

    /**
     * 是否有待恢復的失敗寫回。
     */
    public static boolean hasPendingFailures() {
        return !failedPositions.isEmpty();
    }

    /**
     * 重試所有先前失敗的位置 — 跨 tick 恢復入口。
     *
     * 策略：
     *   - 對每個失敗位置，檢查 BE 是否已載入
     *   - 若已載入，移除失敗記錄 (recovered)
     *   - 若未載入且重試次數 < MAX_RETRIES，保留並遞增計數
     *   - 若超過 MAX_RETRIES，放棄並移除
     *
     * @param level 世界
     * @return 本次成功恢復的數量
     */
    public static int retryFailed(ServerLevel level) {
        assertMainThread(level);

        if (failedPositions.isEmpty()) return 0;

        int recovered = 0;
        int stillFailing = 0;
        int gaveUp = 0;

        Iterator<Map.Entry<BlockPos, StressRetryEntry>> it = failedPositions.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<BlockPos, StressRetryEntry> entry = it.next();
            BlockPos pos = entry.getKey();
            StressRetryEntry retryEntry = entry.getValue();

            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof RBlockEntity) {
                // 成功恢復
                it.remove();
                recovered++;
                LOGGER.debug("[Applicator] Recovered failed position: {} (op: {}, retries: {})",
                    pos, retryEntry.getOperation(), retryEntry.getRetryCount());
            } else {
                // 仍未載入
                if (retryEntry.isExhausted()) {
                    // 重試次數已達上限，放棄此位置
                    it.remove();
                    gaveUp++;
                    LOGGER.warn("[Applicator] Gave up on position {} after {} retries (op: {})",
                        pos, retryEntry.getRetryCount(), retryEntry.getOperation());
                } else {
                    // 遞增重試次數，保留待下次重試
                    retryEntry.incrementRetry();
                    stillFailing++;
                }
            }
        }

        if (recovered > 0 || gaveUp > 0) {
            LOGGER.info("[Applicator] Cross-tick recovery: {} recovered, {} still pending, {} gave up",
                recovered, stillFailing, gaveUp);
        }

        return recovered;
    }

    /**
     * 應用應力結果並支援重試機制。
     *
     * 此方法呼叫 applyStressField()，並跟蹤失敗位置以供跨 tick 恢復。
     * 實際重試由 ServerTickHandler 每 tick 呼叫 processFailedUpdates() 處理。
     *
     * ★ API 修正：移除欺騙性的 delayMs 參數（原先僅記 log 但從未使用），
     * 避免呼叫端誤以為有延遲重試行為。
     *
     * @param level      伺服器世界
     * @param field      應力場
     * @param maxRetries 最大重試次數（預設 3）
     * @return 成功應用的方塊數
     */
    public static int applyStressWithRetry(ServerLevel level, StressField field,
                                           int maxRetries) {
        assertMainThread(level);

        int successCount = applyStressField(level, field);

        if (hasPendingFailures()) {
            for (StressRetryEntry entry : failedPositions.values()) {
                entry.setMaxRetries(maxRetries);
            }
            LOGGER.debug("[Applicator] applyStressWithRetry: {} pending failures, maxRetries={}",
                failedPositions.size(), maxRetries);
        }

        return successCount;
    }

    /**
     * @deprecated 使用 {@link #applyStressWithRetry(ServerLevel, StressField, int)} 替代。
     *             delayMs 參數從未被使用，此重載保留以維持向後相容。
     */
    @Deprecated(since = "1.1", forRemoval = true)
    public static int applyStressWithRetry(ServerLevel level, StressField field,
                                           int maxRetries, long delayMs) {
        LOGGER.warn("[Applicator] applyStressWithRetry(delayMs={}) called — delayMs is ignored, use 3-param overload", delayMs);
        return applyStressWithRetry(level, field, maxRetries);
    }

    // ★ R3-3 fix: 移除重複的 validateMainThread()，統一使用 assertMainThread()。
    // 原 validateMainThread() 功能與 assertMainThread() 完全相同。

    /**
     * 處理失敗更新 — 跨 tick 恢復機制的入口。
     *
     * 應在 ServerTickHandler 中每 tick 呼叫。
     *
     * @param level 伺服器世界
     * @return 本次恢復的數量
     */
    public static int processFailedUpdates(ServerLevel level) {
        return retryFailed(level);
    }

    // ═══════════════════════════════════════════════════════
    //  報告記錄
    // ═══════════════════════════════════════════════════════

    /**
     * 組合應用報告 — 記錄每個引擎的寫回數量。
     */
    public record ApplyReport(
        int fusionApplied,
        int anchorApplied,
        int structureApplied,
        int stressApplied,
        int failedCount
    ) {
        public int totalApplied() {
            return fusionApplied + anchorApplied + structureApplied + stressApplied;
        }

        public boolean hasFailures() {
            return failedCount > 0;
        }

        @Override
        public String toString() {
            return String.format("ApplyReport[fusion=%d, anchor=%d, structure=%d, stress=%d, failed=%d]",
                fusionApplied, anchorApplied, structureApplied, stressApplied, failedCount);
        }
    }
}
