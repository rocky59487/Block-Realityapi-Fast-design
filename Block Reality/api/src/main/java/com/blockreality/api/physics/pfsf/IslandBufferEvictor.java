package com.blockreality.api.physics.pfsf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LRU Island Buffer 驅逐器 — VRAM 壓力大時驅逐最久未使用的 island buffer。
 *
 * <h2>設計動機</h2>
 * 大地圖中 island 數量可達數百，但同一時間只有玩家附近的 island 需要 GPU 計算。
 * 遠處 island 的 GPU buffer 佔用 VRAM 但閒置。
 *
 * <h2>驅逐策略</h2>
 * <ol>
 *   <li>每次處理 island 時呼叫 {@link #touchIsland(int)} 更新 LRU 時戳</li>
 *   <li>每 N tick 呼叫 {@link #evictIfNeeded()}，若 VRAM 壓力 &gt; 70% 則驅逐最舊 island</li>
 *   <li>每次最多驅逐 3 個 island（避免 spike）</li>
 * </ol>
 */
public final class IslandBufferEvictor {

    private static final Logger LOGGER = LoggerFactory.getLogger("PFSF-Evictor");

    /** VRAM 壓力高於此值時開始驅逐 */
    private static final float EVICTION_PRESSURE_THRESHOLD = 0.70f;

    /** 每次驅逐檢查最多驅逐幾個 island */
    private static final int MAX_EVICTIONS_PER_CHECK = 3;

    /** 驅逐檢查間隔 (ticks) */
    private static final int CHECK_INTERVAL = 20;

    /** island 至少存活這麼多 tick 才會被驅逐（避免剛分配就被趕走） */
    private static final long MIN_AGE_TICKS = 100;

    // ─── LRU 追蹤 ───
    private final ConcurrentHashMap<Integer, Long> lastAccessTick = new ConcurrentHashMap<>();
    private long currentTick = 0;

    /**
     * 更新 island 的最後存取時戳。
     * 每次處理 island 時呼叫。
     */
    public void touchIsland(int islandId) {
        lastAccessTick.put(islandId, currentTick);
    }

    /**
     * 若 VRAM 壓力過高，驅逐最久未使用的 island buffer。
     *
     * @param vramMgr VRAM 預算管理器
     * @return 驅逐的 island 數量
     */
    public int evictIfNeeded(VramBudgetManager vramMgr) {
        float pressure = vramMgr.getPressure();
        if (pressure < EVICTION_PRESSURE_THRESHOLD) return 0;

        int evicted = 0;

        for (int i = 0; i < MAX_EVICTIONS_PER_CHECK; i++) {
            // 找到最久未使用的 island
            Map.Entry<Integer, Long> oldest = lastAccessTick.entrySet().stream()
                    .filter(e -> currentTick - e.getValue() > MIN_AGE_TICKS)
                    .min(Comparator.comparingLong(Map.Entry::getValue))
                    .orElse(null);

            if (oldest == null) break;

            int islandId = oldest.getKey();
            PFSFIslandBuffer buf = PFSFBufferManager.buffers.get(islandId);
            if (buf != null) {
                LOGGER.info("[PFSF] Evicting island {} (idle {} ticks, VRAM pressure={:.1f}%)",
                        islandId, currentTick - oldest.getValue(), pressure * 100);
                PFSFBufferManager.removeBuffer(islandId);
                evicted++;
            }
            lastAccessTick.remove(islandId);

            // 重新檢查壓力
            pressure = vramMgr.getPressure();
            if (pressure < EVICTION_PRESSURE_THRESHOLD) break;
        }

        return evicted;
    }

    /** 推進 tick 計數器 */
    public void tick() { currentTick++; }

    /** 取得驅逐檢查間隔 */
    public int getCheckInterval() { return CHECK_INTERVAL; }

    /** 重置所有追蹤狀態 */
    public void reset() {
        lastAccessTick.clear();
        currentTick = 0;
    }

    /** 移除已銷毀的 island 追蹤 */
    public void removeIsland(int islandId) {
        lastAccessTick.remove(islandId);
    }
}
