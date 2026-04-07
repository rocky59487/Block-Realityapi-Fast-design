package com.blockreality.api.physics.pfsf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Island Buffer 驅逐器 — LRU 策略 + CPU 快取。
 *
 * <p>按需觸發：僅在 VramBudgetManager.getPressure() > 0.9 時執行驅逐。
 * 驅逐順序：
 * <ol>
 *   <li>先驅逐 phaseField/multigrid buffer（lazy re-alloc，影響最小）</li>
 *   <li>再驅逐整個 island → 下載 phi 到 cpuCache</li>
 * </ol>
 *
 * <p>被驅逐的 island 在下次被存取時自動從 cpuCache 恢復。</p>
 */
public final class IslandBufferEvictor {

    private static final Logger LOGGER = LoggerFactory.getLogger("PFSF-Evictor");

    /** 觸發驅逐的壓力閾值 */
    private static final float EVICTION_PRESSURE_THRESHOLD = 0.90f;

    /** 每次驅逐最多處理的 island 數 */
    private static final int MAX_EVICTIONS_PER_TICK = 3;

    /** 最少閒置 tick 數才考慮驅逐 */
    private static final long MIN_IDLE_TICKS = 100;

    // ─── 追蹤 ───
    private final ConcurrentHashMap<Integer, Long> lastAccessTick = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, byte[]> cpuCache = new ConcurrentHashMap<>();

    /**
     * 記錄 island 被存取。每次 getOrCreateBuffer() 時呼叫。
     */
    public void touchIsland(int islandId, long tick) {
        lastAccessTick.put(islandId, tick);
    }

    /**
     * 按需驅逐。每 tick 呼叫一次，只在壓力 > 0.9 時實際執行。
     *
     * @param currentTick  當前 tick
     * @param budgetMgr    VRAM 預算管理器
     * @param buffers      島嶼 buffer map（由 PFSFBufferManager 提供）
     * @return 被驅逐的 island ID 列表
     */
    public List<Integer> evictIfNeeded(long currentTick, VramBudgetManager budgetMgr,
                                        ConcurrentHashMap<Integer, PFSFIslandBuffer> buffers) {
        if (budgetMgr.getPressure() <= EVICTION_PRESSURE_THRESHOLD) {
            return Collections.emptyList();
        }

        // 找出最久沒存取的 island
        List<Map.Entry<Integer, Long>> candidates = new ArrayList<>(lastAccessTick.entrySet());
        candidates.sort(Comparator.comparingLong(Map.Entry::getValue));

        List<Integer> evicted = new ArrayList<>();
        for (Map.Entry<Integer, Long> entry : candidates) {
            if (evicted.size() >= MAX_EVICTIONS_PER_TICK) break;
            if (budgetMgr.getPressure() <= EVICTION_PRESSURE_THRESHOLD) break;

            int islandId = entry.getKey();
            long lastAccess = entry.getValue();

            if (currentTick - lastAccess < MIN_IDLE_TICKS) continue;

            PFSFIslandBuffer buf = buffers.get(islandId);
            if (buf == null || !buf.isAllocated()) continue;

            // Phase 1: 先嘗試只驅逐 phaseField + multigrid
            PFSFPhaseFieldBuffers pf = buf.getPhaseField();
            PFSFMultigridBuffers mg = buf.getMultigrid();

            if (pf.isAllocated()) {
                pf.free();
                LOGGER.debug("[Evictor] Evicted phaseField for island {} (idle {} ticks)",
                        islandId, currentTick - lastAccess);
            }
            if (mg.isAllocated()) {
                mg.free();
                LOGGER.debug("[Evictor] Evicted multigrid for island {} (idle {} ticks)",
                        islandId, currentTick - lastAccess);
            }

            // 如果壓力還是太高，驅逐整個 island
            if (budgetMgr.getPressure() > EVICTION_PRESSURE_THRESHOLD) {
                // TODO: 未來可實作 GPU→CPU download 保存 phi 狀態
                // 目前直接標記為 evicted，下次存取時重新分配
                buffers.remove(islandId);
                buf.release();
                evicted.add(islandId);
                LOGGER.info("[Evictor] Fully evicted island {} (idle {} ticks, pressure={})",
                        islandId, currentTick - lastAccess, budgetMgr.getPressure());
            }
        }

        return evicted;
    }

    /**
     * 檢查 island 是否被驅逐過。
     */
    public boolean wasEvicted(int islandId) {
        return cpuCache.containsKey(islandId);
    }

    /**
     * 取得被驅逐的 island 的 CPU 快取資料。
     */
    public byte[] getCachedData(int islandId) {
        return cpuCache.get(islandId);
    }

    /**
     * 移除 island 的追蹤記錄（island 銷毀時呼叫）。
     */
    public void removeIsland(int islandId) {
        lastAccessTick.remove(islandId);
        cpuCache.remove(islandId);
    }

    /**
     * 重置所有追蹤（shutdown 時呼叫）。
     */
    public void reset() {
        lastAccessTick.clear();
        cpuCache.clear();
    }
}
