package com.blockreality.api.physics.pfsf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.blockreality.api.config.BRConfig;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

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
 *
 * <h2>複雜度（P1-B 優化）</h2>
 * 主索引 {@code lastAccessTick}（islandId → tick）加上有序輔助索引
 * {@code byTick}（tick → Set&lt;islandId&gt;），驅逐候選由
 * {@link TreeMap#firstEntry()} 取得，複雜度 O(log N)，
 * 取代原本全量 stream 的 O(N)。
 */
public final class IslandBufferEvictor {

    private static final Logger LOGGER = LoggerFactory.getLogger("PFSF-Evictor");

    /** VRAM 壓力高於此值時開始驅逐 */
    private static final float EVICTION_PRESSURE_THRESHOLD = 0.70f;

    /** 每次驅逐檢查最多驅逐幾個 island */
    private static final int MAX_EVICTIONS_PER_CHECK = 3;

    /** 驅逐檢查間隔 (ticks) */
    private static final int CHECK_INTERVAL = 20;

    /** island 至少存活這麼多 tick 才會被驅逐 — 由 BRConfig.getEvictorMinAgeTicks() 動態讀取 (P2-A) */
    // 原本是 private static final long MIN_AGE_TICKS = 100;

    // ─── LRU 追蹤（兩層結構，O(log N) 驅逐候選查詢） ───

    /** 主索引：islandId → 最後存取 tick（支援 touch 時定位舊桶） */
    private final Map<Integer, Long> lastAccessTick = new HashMap<>();

    /**
     * 有序輔助索引：tick → 在該 tick 被存取的 islandId 集合。
     * firstEntry() 即最久未使用的桶，驅逐時 O(log N)。
     */
    private final TreeMap<Long, Set<Integer>> byTick = new TreeMap<>();

    private long currentTick = 0;

    /**
     * 更新 island 的最後存取時戳。
     * 每次處理 island 時呼叫。O(log N)。
     */
    public void touchIsland(int islandId) {
        Long oldTick = lastAccessTick.put(islandId, currentTick);
        // 從舊桶移除
        if (oldTick != null) {
            Set<Integer> bucket = byTick.get(oldTick);
            if (bucket != null) {
                bucket.remove(islandId);
                if (bucket.isEmpty()) byTick.remove(oldTick);
            }
        }
        // 插入新桶
        byTick.computeIfAbsent(currentTick, k -> new HashSet<>()).add(islandId);
    }

    /**
     * 若 VRAM 壓力過高，驅逐最久未使用的 island buffer。
     * 驅逐候選由 {@link TreeMap#firstEntry()} 取得，O(log N)。
     *
     * @param vramMgr VRAM 預算管理器
     * @return 驅逐的 island 數量
     */
    public int evictIfNeeded(VramBudgetManager vramMgr) {
        float pressure = vramMgr.getPressure();
        if (pressure < EVICTION_PRESSURE_THRESHOLD) return 0;

        int evicted = 0;

        while (evicted < MAX_EVICTIONS_PER_CHECK && !byTick.isEmpty()) {
            Map.Entry<Long, Set<Integer>> oldestBucket = byTick.firstEntry();
            long idleTicks = currentTick - oldestBucket.getKey();
            if (idleTicks <= BRConfig.getEvictorMinAgeTicks()) break; // 最舊的都未達最小存活時間，停止

            int islandId = oldestBucket.getValue().iterator().next();
            PFSFIslandBuffer buf = PFSFBufferManager.buffers.get(islandId);
            if (buf != null) {
                LOGGER.info("[PFSF] Evicting island {} (idle {} ticks, VRAM pressure={:.1f}%)",
                        islandId, idleTicks, pressure * 100);
                PFSFBufferManager.removeBuffer(islandId);
                evicted++;
            }

            // 從兩個索引清除
            lastAccessTick.remove(islandId);
            oldestBucket.getValue().remove(islandId);
            if (oldestBucket.getValue().isEmpty()) byTick.remove(oldestBucket.getKey());

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
        byTick.clear();
        currentTick = 0;
    }

    /** 移除已銷毀的 island 追蹤 */
    public void removeIsland(int islandId) {
        Long tick = lastAccessTick.remove(islandId);
        if (tick != null) {
            Set<Integer> bucket = byTick.get(tick);
            if (bucket != null) {
                bucket.remove(islandId);
                if (bucket.isEmpty()) byTick.remove(tick);
            }
        }
    }
}
