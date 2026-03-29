package com.blockreality.api.client;

import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 客戶端應力快取 — v3fix §1.8
 *
 * 存放從伺服器同步過來的應力數據，供 StressHeatmapRenderer 讀取。
 *
 * 線程安全設計：
 *   - ConcurrentHashMap：Netty IO 線程寫入，渲染線程讀取
 *   - LRU 上限：MAX_CACHE_SIZE 筆（防止記憶體溢出）
 *   - 維度切換時清空（ClientStressPacketHandler 呼叫 clearCache）
 */
@OnlyIn(Dist.CLIENT)
@javax.annotation.concurrent.ThreadSafe // ConcurrentHashMap between Netty IO and render
public class ClientStressCache {

    /** 最大快取筆數 — 超過時移除最舊的（簡易 LRU） */
    private static final int MAX_CACHE_SIZE = 4096;

    /** 應力快取：BlockPos → stress level [0.0, 2.0] */
    private static final ConcurrentHashMap<BlockPos, Float> stressCache = new ConcurrentHashMap<>();

    /** 訪問時間戳記：BlockPos → last access time (ms) */
    private static final ConcurrentHashMap<BlockPos, Long> accessTime = new ConcurrentHashMap<>();

    /**
     * 取得快取的不可變視圖（供渲染器讀取）。
     */
    public static Map<BlockPos, Float> getCache() {
        return Collections.unmodifiableMap(stressCache);
    }

    /**
     * 合併應力數據（來自伺服器同步封包）。
     * 移除 stress ≤ 0 的條目（方塊已安全/移除）。
     */
    public static void mergeStressData(Map<BlockPos, Float> incoming) {
        long now = System.currentTimeMillis();
        for (Map.Entry<BlockPos, Float> entry : incoming.entrySet()) {
            BlockPos pos = entry.getKey();
            if (entry.getValue() <= 0.0f) {
                stressCache.remove(pos);
                accessTime.remove(pos);
            } else {
                stressCache.put(pos, entry.getValue());
                accessTime.put(pos, now);
            }
        }

        // 真實 LRU：超過上限時批次清理最舊訪問的條目
        if (stressCache.size() > MAX_CACHE_SIZE) {
            evictLowStress();
        }
    }

    /**
     * 更新單一方塊的應力值。
     */
    public static void updateStress(BlockPos pos, float stress) {
        long now = System.currentTimeMillis();
        if (stress <= 0.0f) {
            stressCache.remove(pos);
            accessTime.remove(pos);
        } else {
            stressCache.put(pos.immutable(), stress);
            accessTime.put(pos.immutable(), now);
        }

        if (stressCache.size() > MAX_CACHE_SIZE) {
            evictLowStress();
        }
    }

    /**
     * 移除特定方塊的應力數據。
     */
    public static void removeStress(BlockPos pos) {
        stressCache.remove(pos);
        accessTime.remove(pos);
    }

    /**
     * 清空快取（維度切換、斷線時）。
     */
    public static void clearCache() {
        stressCache.clear();
        accessTime.clear();
    }

    /**
     * 快取大小。
     */
    public static int size() {
        return stressCache.size();
    }

    /**
     * 真實 LRU 清理策略：移除最舊訪問的條目。
     *
     * ★ T-2 fix: 使用時間戳記排序，移除最舊訪問的條目直到達到目標大小。
     * 效能：複製 accessTime 並排序（O(n log n)），但僅在超過上限時執行。
     */
    private static void evictLowStress() {
        int targetSize = MAX_CACHE_SIZE * 3 / 4;

        // ★ 快照排序後的 key 列表，避免在迭代 ConcurrentHashMap 時同時修改
        var keysToEvict = accessTime.entrySet().stream()
            .sorted(java.util.Map.Entry.comparingByValue())
            .map(java.util.Map.Entry::getKey)
            .collect(Collectors.toList());

        // 從最舊的條目開始移除，直到達到目標大小
        for (BlockPos pos : keysToEvict) {
            if (stressCache.size() <= targetSize) break;
            stressCache.remove(pos);
            accessTime.remove(pos);
        }
    }
}
