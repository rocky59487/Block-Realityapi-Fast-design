package com.blockreality.api.client;

import net.minecraft.core.BlockPos;

import java.util.Collections;
import java.util.List;

/**
 * 客戶端錨定路徑快取 — 想法.docx AnchorPathVisualizer
 *
 * 儲存從伺服器同步的錨定 BFS 路徑數據。
 * 由 AnchorPathRenderer 讀取並渲染為半透明線段。
 *
 * ★ R6-10 fix: 每條路徑新增 isAnchored 狀態，
 * 有效路徑渲染為綠色、無效路徑渲染為紅色。
 *
 * 線程安全：paths 使用 volatile 引用交換，
 * 讀取端（渲染線程）只讀取 snapshot，不需要鎖。
 */
@javax.annotation.concurrent.ThreadSafe // volatile reference swaps for render thread
public class AnchorPathCache {

    /**
     * 路徑記錄 — 包含節點序列與錨定狀態。
     * ★ R6-10: 新增 isAnchored 欄位。
     *
     * @param nodes      路徑節點（從起點到終點）
     * @param isAnchored 路徑是否成功抵達錨定點
     */
    public record PathEntry(List<BlockPos> nodes, boolean isAnchored) {}

    /** 當前路徑數據 */
    private static volatile List<PathEntry> paths = Collections.emptyList();

    /** 路徑更新時間戳（game time），用於過期判定 */
    private static volatile long lastUpdateTick = 0;

    /** 路徑顯示持續時間 (ticks)：10 秒後自動消失 */
    private static final long PATH_DISPLAY_DURATION = 200;

    /**
     * ★ R6-10: 更新路徑數據（含錨定狀態）。
     */
    public static void updatePaths(List<PathEntry> newPaths) {
        paths = List.copyOf(newPaths);
        lastUpdateTick = System.currentTimeMillis();
    }

    /**
     * 向後相容：不帶錨定狀態的更新（全部視為已錨定）。
     */
    public static void updatePathsLegacy(List<List<BlockPos>> newPaths) {
        List<PathEntry> entries = new java.util.ArrayList<>(newPaths.size());
        for (List<BlockPos> p : newPaths) {
            entries.add(new PathEntry(p, true));
        }
        paths = List.copyOf(entries);
        lastUpdateTick = System.currentTimeMillis();
    }

    /**
     * 取得當前路徑數據（渲染線程讀取）。
     */
    public static List<PathEntry> getPathEntries() {
        long elapsed = System.currentTimeMillis() - lastUpdateTick;
        if (elapsed > PATH_DISPLAY_DURATION * 50) {
            return Collections.emptyList();
        }
        return paths;
    }

    /**
     * 向後相容：回傳純節點列表。
     */
    public static List<List<BlockPos>> getPaths() {
        List<PathEntry> entries = getPathEntries();
        if (entries.isEmpty()) return Collections.emptyList();
        List<List<BlockPos>> result = new java.util.ArrayList<>(entries.size());
        for (PathEntry e : entries) {
            result.add(e.nodes());
        }
        return result;
    }

    /**
     * 清除路徑（維度切換或世界卸載時呼叫）。
     */
    public static void clear() {
        paths = Collections.emptyList();
    }

    /**
     * 是否有路徑需要渲染。
     */
    public static boolean hasActivePaths() {
        return !getPathEntries().isEmpty();
    }
}
