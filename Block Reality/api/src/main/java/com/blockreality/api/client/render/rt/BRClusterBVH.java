package com.blockreality.api.client.render.rt;

import com.blockreality.api.client.rendering.vulkan.BRAdaRTConfig;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * BRClusterBVH — Blackwell Cluster Acceleration Structure 管理器。
 *
 * <h3>概念</h3>
 * <p>Blackwell GPU（RTX 50xx / SM10+）支援 {@code VK_NV_cluster_acceleration_structure} 擴充，
 * 允許將相鄰幾何體打包為「Cluster BLAS」，由硬體在 BVH 遍歷時動態解壓縮。
 * 相較於傳統每 section 一個 BLAS 的方式，Cluster BVH 可顯著降低 TLAS instance 數量
 * 並提升 BVH 建構效率。
 *
 * <h3>Section 到 Cluster 的映射</h3>
 * <pre>
 * Cluster 大小 = {@value #CLUSTER_SIZE} × {@value #CLUSTER_SIZE} sections（每 section = 16×16 方塊）
 *
 * clusterX = floor(sectionX / CLUSTER_SIZE)
 * clusterZ = floor(sectionZ / CLUSTER_SIZE)
 *
 * 每個 Cluster 覆蓋 64×64 方塊（XZ 平面），Y 軸不合併
 * （Minecraft 的 section Y 範圍差異太大，合併後 AABB 過深影響 BVH 品質）
 * </pre>
 *
 * <h3>TLAS 縮減效果</h3>
 * <pre>
 * 傳統：N sections → N TLAS instances
 * Cluster：N sections → ceil(N / CLUSTER_SIZE²) TLAS instances（最多縮減 16×）
 * </pre>
 *
 * <h3>非 Blackwell GPU 的行為</h3>
 * <p>當 {@link BRAdaRTConfig#isBlackwellOrNewer()} 為 false 時，
 * 此類別的所有方法立即返回，不做任何操作。
 * 呼叫者（{@link com.blockreality.api.client.rendering.vulkan.VkAccelStructBuilder}）
 * 在 Ada / Legacy 路徑時改用 section-level BLAS。
 *
 * @see BRVulkanBVH
 * @see BRAdaRTConfig
 * @see com.blockreality.api.client.rendering.vulkan.VkAccelStructBuilder
 */
@OnlyIn(Dist.CLIENT)
public final class BRClusterBVH {

    private static final Logger LOGGER = LoggerFactory.getLogger("BR-ClusterBVH");

    // ════════════════════════════════════════════════════════════════════════
    //  常數
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 每個 Cluster 在 XZ 平面上包含的 section 邊長（N×N sections per cluster）。
     * 值為 4 表示每個 Cluster 最多包含 16 個 section（4×4）。
     *
     * <p>與 {@link com.blockreality.api.client.rendering.vulkan.VkAccelStructBuilder#CLUSTER_SIZE}
     * 保持一致（兩處均為 4）。
     */
    public static final int CLUSTER_SIZE = 4;

    /**
     * 每個 Cluster 最多包含的 section 數量（= CLUSTER_SIZE²）。
     * TLAS instance 縮減因子上限。
     */
    public static final int MAX_SECTIONS_PER_CLUSTER = CLUSTER_SIZE * CLUSTER_SIZE; // 16

    /**
     * 追蹤的最大 Cluster 數量（對應 {@link BRVulkanBVH#MAX_SECTIONS} / {@link #MAX_SECTIONS_PER_CLUSTER}）。
     */
    public static final int MAX_CLUSTERS = BRVulkanBVH.MAX_SECTIONS / MAX_SECTIONS_PER_CLUSTER; // 256

    /**
     * 非 Blackwell GPU 上 Cluster BVH 被停用時的 AABB 最小閾值。
     * 此值不在非 Blackwell 路徑使用，但作為 Javadoc 文件記錄。
     */
    private static final float AABB_MIN_THICKNESS = 0.1f;

    // ════════════════════════════════════════════════════════════════════════
    //  Singleton
    // ════════════════════════════════════════════════════════════════════════

    private static final BRClusterBVH INSTANCE = new BRClusterBVH();

    public static BRClusterBVH getInstance() {
        return INSTANCE;
    }

    private BRClusterBVH() {}

    // ════════════════════════════════════════════════════════════════════════
    //  ClusterEntry — 每個 Cluster 的狀態
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 單一 Cluster 的狀態記錄。
     *
     * <p>每個 Cluster 由其 (clusterX, clusterZ) 索引識別，
     * 追蹤成員 section 的 AABB 包圍盒並記錄最後建構幀。
     */
    public static final class ClusterEntry {
        /** Cluster X 索引（= floor(sectionX / CLUSTER_SIZE)） */
        public final int clusterX;
        /** Cluster Z 索引（= floor(sectionZ / CLUSTER_SIZE)） */
        public final int clusterZ;

        /** Cluster 的合併最小 AABB（世界空間，方塊單位） */
        public volatile float minX, minY, minZ;
        /** Cluster 的合併最大 AABB（世界空間，方塊單位） */
        public volatile float maxX, maxY, maxZ;

        /** 此 Cluster 是否需要重建（有成員 section 被更新） */
        public volatile boolean dirty = true;

        /** 最後重建的幀計數 */
        public volatile long lastBuildFrame = -1L;

        /** 此 Cluster 中已追蹤的 section 數量（0–16） */
        public volatile int sectionCount = 0;

        /** Cluster 內任一 section 是否含透明方塊（影響 OMM/opaque 旗標選擇） */
        public volatile boolean hasTransparent = false;

        ClusterEntry(int clusterX, int clusterZ) {
            this.clusterX = clusterX;
            this.clusterZ = clusterZ;
            // 初始化 AABB 為 Cluster 覆蓋範圍（基於 cluster 索引）
            this.minX = clusterX * CLUSTER_SIZE * 16.0f;
            this.minZ = clusterZ * CLUSTER_SIZE * 16.0f;
            this.maxX = this.minX + CLUSTER_SIZE * 16.0f;
            this.maxZ = this.minZ + CLUSTER_SIZE * 16.0f;
            this.minY = Float.MAX_VALUE;
            this.maxY = -Float.MAX_VALUE;
        }

        /**
         * 展開此 Cluster 的 AABB 以包含給定 section 的 Y 範圍。
         * 線程安全問題：呼叫者須確保不並發更新同一 ClusterEntry（
         * 由 {@link BRClusterBVH} 的 cluster-key 鎖定保證）。
         *
         * @param sectionMinY section 的最小 Y（方塊單位）
         * @param sectionMaxY section 的最大 Y（方塊單位）
         */
        void expandY(float sectionMinY, float sectionMaxY) {
            if (sectionMinY < this.minY) this.minY = sectionMinY;
            if (sectionMaxY > this.maxY) this.maxY = sectionMaxY;
        }

        /**
         * 建立供 {@link BRVulkanBVH#buildBLAS} 使用的 AABB 資料陣列。
         * 格式：[minX, minY, minZ, maxX, maxY, maxZ]（6 floats，1 primitive）。
         *
         * @return AABB float[6]，若 Y 範圍未初始化則 Y 使用 section-level 預設值（0–256）
         */
        public float[] toAabbData() {
            float y0 = (minY == Float.MAX_VALUE)  ? 0.0f   : minY;
            float y1 = (maxY == -Float.MAX_VALUE) ? 256.0f : maxY;
            return new float[] { minX, y0, minZ, maxX, y1, maxZ };
        }

        @Override
        public String toString() {
            return String.format("Cluster(%d,%d)[secs=%d, dirty=%b, frame=%d, AABB=(%.1f,%.1f,%.1f)-(%.1f,%.1f,%.1f)]",
                clusterX, clusterZ, sectionCount, dirty, lastBuildFrame,
                minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  狀態
    // ════════════════════════════════════════════════════════════════════════

    /** clusterKey → ClusterEntry */
    private final ConcurrentHashMap<Long, ClusterEntry> clusterMap = new ConcurrentHashMap<>();

    /** 目前 dirty cluster 數量（近似值，用於快速判斷） */
    private final AtomicInteger dirtyClusterCount = new AtomicInteger(0);

    /** 累計重建次數（效能監控） */
    private final AtomicLong totalRebuildCount = new AtomicLong(0);

    /** 目前幀計數（由 {@link #onFrameStart(long)} 更新） */
    private volatile long currentFrame = 0L;

    // ════════════════════════════════════════════════════════════════════════
    //  公開 API
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 將 section 座標轉換為 Cluster 索引。
     *
     * @param sectionX section X 座標（chunk-space）
     * @return Cluster X 索引
     */
    public static int sectionToClusterX(int sectionX) {
        return Math.floorDiv(sectionX, CLUSTER_SIZE);
    }

    /**
     * 將 section 座標轉換為 Cluster 索引。
     *
     * @param sectionZ section Z 座標（chunk-space）
     * @return Cluster Z 索引
     */
    public static int sectionToClusterZ(int sectionZ) {
        return Math.floorDiv(sectionZ, CLUSTER_SIZE);
    }

    /**
     * 計算 Cluster 的唯一 long key。
     * 高 32 位 = clusterX，低 32 位 = clusterZ（與 section key 格式相同但 key 空間不同）。
     *
     * @param clusterX Cluster X 索引
     * @param clusterZ Cluster Z 索引
     * @return 唯一 long key
     */
    public static long encodeClusterKey(int clusterX, int clusterZ) {
        return ((long)(clusterX & 0xFFFFFFFFL) << 32) | (clusterZ & 0xFFFFFFFFL);
    }

    /**
     * 從 Cluster key 解碼 clusterX。
     *
     * @param key {@link #encodeClusterKey} 的輸出
     * @return clusterX
     */
    public static int decodeClusterX(long key) {
        return (int)(key >> 32);
    }

    /**
     * 從 Cluster key 解碼 clusterZ。
     *
     * @param key {@link #encodeClusterKey} 的輸出
     * @return clusterZ
     */
    public static int decodeClusterZ(long key) {
        return (int)(key & 0xFFFFFFFFL);
    }

    /**
     * 通知 BRClusterBVH 某 section 的幾何已更新，需重建其所屬 Cluster BLAS。
     *
     * <p>非 Blackwell GPU 上此方法立即返回。
     *
     * @param sectionX    section X 座標
     * @param sectionZ    section Z 座標
     * @param sectionMinY section 的最小 Y（方塊單位，用於展開 Cluster AABB）
     * @param sectionMaxY section 的最大 Y（方塊單位）
     * @param hasTransparent 此 section 是否含透明方塊（玻璃/水/葉片）
     */
    public void onSectionUpdated(int sectionX, int sectionZ,
                                  float sectionMinY, float sectionMaxY,
                                  boolean hasTransparent) {
        if (!BRAdaRTConfig.isBlackwellOrNewer()) return;

        int cx = sectionToClusterX(sectionX);
        int cz = sectionToClusterZ(sectionZ);
        long key = encodeClusterKey(cx, cz);

        ClusterEntry entry = clusterMap.computeIfAbsent(key, k -> {
            if (clusterMap.size() >= MAX_CLUSTERS) {
                LOGGER.warn("[ClusterBVH] Cluster map full (max={}), skipping new cluster ({},{})",
                    MAX_CLUSTERS, cx, cz);
                return null;
            }
            LOGGER.debug("[ClusterBVH] New cluster ({},{}) created for section ({},{})",
                cx, cz, sectionX, sectionZ);
            return new ClusterEntry(cx, cz);
        });

        if (entry == null) return; // 容量已滿

        // 展開 Y AABB（含此 section 的高度範圍）
        entry.expandY(sectionMinY, sectionMaxY);

        // 更新透明狀態（OR 語意：任一 section 透明則整個 cluster 視為透明）
        if (hasTransparent) entry.hasTransparent = true;
        entry.sectionCount = Math.min(entry.sectionCount + 1, MAX_SECTIONS_PER_CLUSTER);

        // 標記為 dirty（需重建）
        if (!entry.dirty) {
            entry.dirty = true;
            dirtyClusterCount.incrementAndGet();
        }
    }

    /**
     * 通知 BRClusterBVH 某 section 已被移除（chunk 卸載）。
     *
     * <p>若 Cluster 因此變空，則從追蹤移除並銷毀對應 BLAS。
     *
     * @param sectionX section X 座標
     * @param sectionZ section Z 座標
     */
    public void onSectionRemoved(int sectionX, int sectionZ) {
        if (!BRAdaRTConfig.isBlackwellOrNewer()) return;

        int cx = sectionToClusterX(sectionX);
        int cz = sectionToClusterZ(sectionZ);
        long key = encodeClusterKey(cx, cz);

        ClusterEntry entry = clusterMap.get(key);
        if (entry == null) return;

        entry.sectionCount = Math.max(0, entry.sectionCount - 1);

        if (entry.sectionCount == 0) {
            // Cluster 已空，銷毀 BLAS 並移除追蹤
            clusterMap.remove(key);
            int repX = cx * CLUSTER_SIZE;
            int repZ = cz * CLUSTER_SIZE;
            BRVulkanBVH.destroyBLAS(repX, repZ);
            LOGGER.debug("[ClusterBVH] Cluster ({},{}) removed (all sections unloaded)", cx, cz);
        } else {
            // 仍有其他 section，標記 dirty 以重建（不再包含已移除 section）
            if (!entry.dirty) {
                entry.dirty = true;
                dirtyClusterCount.incrementAndGet();
            }
        }
    }

    /**
     * 每幀開始時呼叫，更新幀計數並觸發 dirty cluster 的 BLAS 重建。
     *
     * <p>非 Blackwell GPU 上此方法立即返回。
     * 每幀最多重建 {@link BRVulkanBVH#MAX_BLAS_REBUILDS_PER_FRAME} 個 Cluster，
     * 避免 GPU stall。
     *
     * @param frame 目前幀計數
     */
    public void onFrameStart(long frame) {
        if (!BRAdaRTConfig.isBlackwellOrNewer()) return;
        currentFrame = frame;

        if (dirtyClusterCount.get() == 0) return;

        int rebuilt = 0;
        // 遍歷所有 dirty cluster，按需重建（限制每幀重建量）
        for (Map.Entry<Long, ClusterEntry> mapEntry : clusterMap.entrySet()) {
            if (rebuilt >= BRVulkanBVH.MAX_BLAS_REBUILDS_PER_FRAME) break;

            ClusterEntry entry = mapEntry.getValue();
            if (!entry.dirty) continue;

            rebuildClusterBLAS(entry);
            entry.dirty = false;
            entry.lastBuildFrame = frame;
            dirtyClusterCount.decrementAndGet();
            rebuilt++;
            totalRebuildCount.incrementAndGet();
        }

        if (rebuilt > 0) {
            LOGGER.debug("[ClusterBVH] Frame {}: rebuilt {} clusters, {} dirty remain, total={}",
                frame, rebuilt, dirtyClusterCount.get(), clusterMap.size());
        }
    }

    /**
     * 強制重建所有 dirty Cluster（用於世界載入完成或大量變化後的一次性同步）。
     *
     * <p>非 Blackwell GPU 上此方法立即返回。
     * 與 {@link #onFrameStart(long)} 不同，此方法無每幀上限，會重建所有 dirty cluster。
     */
    public void rebuildAllDirty() {
        if (!BRAdaRTConfig.isBlackwellOrNewer()) return;
        if (dirtyClusterCount.get() == 0) return;

        int count = 0;
        for (ClusterEntry entry : clusterMap.values()) {
            if (!entry.dirty) continue;
            rebuildClusterBLAS(entry);
            entry.dirty = false;
            entry.lastBuildFrame = currentFrame;
            totalRebuildCount.incrementAndGet();
            count++;
        }
        dirtyClusterCount.set(0);

        if (count > 0) {
            LOGGER.info("[ClusterBVH] Forced rebuild of {} clusters (total={})", count, clusterMap.size());
        }
    }

    /**
     * 清空所有 Cluster 追蹤資料（世界卸載時呼叫）。
     * 注意：不主動銷毀 BLAS，由 {@link BRVulkanBVH#cleanup()} 統一清理。
     */
    public void clear() {
        clusterMap.clear();
        dirtyClusterCount.set(0);
        LOGGER.info("[ClusterBVH] All cluster tracking data cleared");
    }

    // ════════════════════════════════════════════════════════════════════════
    //  統計 / 查詢
    // ════════════════════════════════════════════════════════════════════════

    /** @return 目前追蹤的 Cluster 總數 */
    public int getClusterCount() {
        return clusterMap.size();
    }

    /** @return 目前 dirty（需重建）的 Cluster 數量 */
    public int getDirtyClusterCount() {
        return dirtyClusterCount.get();
    }

    /** @return 累計 Cluster BLAS 重建次數（效能監控） */
    public long getTotalRebuildCount() {
        return totalRebuildCount.get();
    }

    /**
     * 取得 Cluster 條目供外部查詢（唯讀），例如 Debug HUD。
     *
     * @param clusterX Cluster X 索引
     * @param clusterZ Cluster Z 索引
     * @return ClusterEntry，若不存在則返回 null
     */
    public ClusterEntry getClusterEntry(int clusterX, int clusterZ) {
        return clusterMap.get(encodeClusterKey(clusterX, clusterZ));
    }

    /**
     * 取得所有 Cluster 的快照（唯讀），用於 Debug 渲染。
     *
     * @return 所有 ClusterEntry 的列表（複製）
     */
    public List<ClusterEntry> getAllClusters() {
        return new ArrayList<>(clusterMap.values());
    }

    /**
     * 計算 TLAS instance 縮減效果：
     * {@code 1.0f} = 無縮減（退化為 section-level），{@code 1/16} = 最大縮減（16 sections/cluster）。
     *
     * @return 平均每個 Cluster 的 section 數量（> 1.0 表示有效縮減）
     */
    public float averageSectionsPerCluster() {
        int total = 0;
        int count = clusterMap.size();
        if (count == 0) return 0.0f;
        for (ClusterEntry e : clusterMap.values()) {
            total += e.sectionCount;
        }
        return (float) total / count;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  內部實作
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 重建單一 Cluster 的 BLAS。
     *
     * <p>使用 Cluster 覆蓋範圍的合併 AABB 作為 geometry，
     * 以 Cluster 左下角 section 的座標作為 {@link BRVulkanBVH} 的 key。
     *
     * <p>若 Cluster 含透明 section，使用標準 BLAS（any-hit shader 生效）；
     * 否則若 OMM 可用，使用 {@link BRVulkanBVH#buildBLASOpaque(int, int, float[], int)}
     * 跳過 any-hit（節省 15-30% ray intersection 時間）。
     *
     * @param entry Cluster 狀態（含合併 AABB）
     */
    private void rebuildClusterBLAS(ClusterEntry entry) {
        // Cluster 代表座標（左下角 section 的 section 座標）
        int repX = entry.clusterX * CLUSTER_SIZE;
        int repZ = entry.clusterZ * CLUSTER_SIZE;

        float[] aabbData = entry.toAabbData();
        int primitiveCount = aabbData.length / 6; // = 1

        try {
            boolean useOpaqueFlag = BRAdaRTConfig.hasOMM() && !entry.hasTransparent;

            if (useOpaqueFlag) {
                BRVulkanBVH.buildBLASOpaque(repX, repZ, aabbData, primitiveCount);
                LOGGER.debug("[ClusterBVH] Cluster ({},{}) BLAS rebuilt (opaque, any-hit skipped), " +
                        "sections={}, AABB=({:.1f},{:.1f},{:.1f})-({:.1f},{:.1f},{:.1f})",
                    entry.clusterX, entry.clusterZ, entry.sectionCount,
                    aabbData[0], aabbData[1], aabbData[2],
                    aabbData[3], aabbData[4], aabbData[5]);
            } else {
                BRVulkanBVH.buildBLAS(repX, repZ, aabbData, primitiveCount);
                LOGGER.debug("[ClusterBVH] Cluster ({},{}) BLAS rebuilt, sections={}, transparent={}",
                    entry.clusterX, entry.clusterZ, entry.sectionCount, entry.hasTransparent);
            }

        } catch (Exception e) {
            LOGGER.error("[ClusterBVH] Failed to rebuild BLAS for cluster ({},{}): {}",
                entry.clusterX, entry.clusterZ, e.getMessage());
        }
    }
}
