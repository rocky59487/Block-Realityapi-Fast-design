package com.blockreality.api.client.render.rt;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BRClusterBVHTest — 驗證 Cluster BVH 管理器的核心邏輯。
 *
 * <p>所有測試均在 JUnit 5 環境中執行，不依賴 Vulkan / Forge runtime。
 * 測試對象為 BRClusterBVH 的純 Java 邏輯：
 * <ul>
 *   <li>Cluster key 計算正確性</li>
 *   <li>Section → Cluster 座標映射</li>
 *   <li>4×4 section 打包邏輯</li>
 *   <li>AABB 合併展開</li>
 *   <li>邊界/負座標處理（地下 section）</li>
 * </ul>
 *
 * <p>注意：{@link BRClusterBVH#onSectionUpdated} 等方法需要
 * {@link com.blockreality.api.client.rendering.vulkan.BRAdaRTConfig#isBlackwellOrNewer()}
 * 回傳 true 才能執行。在 JUnit 環境中 BRAdaRTConfig 未初始化（默認 false），
 * 因此直接測試靜態方法（key 計算、座標轉換等），不測試 Forge 相依路徑。
 */
@DisplayName("BRClusterBVH — Cluster key 計算與 section 映射")
class BRClusterBVHTest {

    // ═══════════════════════════════════════════════════════════════════════
    //  常數確認
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("CLUSTER_SIZE 應為 4（與 VkAccelStructBuilder 保持一致）")
    void clusterSizeConstant() {
        assertEquals(4, BRClusterBVH.CLUSTER_SIZE,
            "BRClusterBVH.CLUSTER_SIZE 必須與 VkAccelStructBuilder.CLUSTER_SIZE=4 一致");
    }

    @Test
    @DisplayName("MAX_SECTIONS_PER_CLUSTER 應為 CLUSTER_SIZE² = 16")
    void maxSectionsPerClusterConstant() {
        assertEquals(
            BRClusterBVH.CLUSTER_SIZE * BRClusterBVH.CLUSTER_SIZE,
            BRClusterBVH.MAX_SECTIONS_PER_CLUSTER
        );
        assertEquals(16, BRClusterBVH.MAX_SECTIONS_PER_CLUSTER);
    }

    @Test
    @DisplayName("MAX_CLUSTERS = MAX_SECTIONS / MAX_SECTIONS_PER_CLUSTER")
    void maxClustersConstant() {
        assertEquals(
            BRVulkanBVH.MAX_SECTIONS / BRClusterBVH.MAX_SECTIONS_PER_CLUSTER,
            BRClusterBVH.MAX_CLUSTERS
        );
        assertEquals(256, BRClusterBVH.MAX_CLUSTERS,
            "BRVulkanBVH.MAX_SECTIONS=4096, MAX_SECTIONS_PER_CLUSTER=16 → MAX_CLUSTERS=256");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Section → Cluster 座標映射
    // ═══════════════════════════════════════════════════════════════════════

    @ParameterizedTest
    @DisplayName("sectionToClusterX：正數座標映射")
    @CsvSource({
        "0,   0",  // section 0-3 → cluster 0
        "1,   0",
        "2,   0",
        "3,   0",
        "4,   1",  // section 4-7 → cluster 1
        "7,   1",
        "8,   2",  // section 8-11 → cluster 2
        "15,  3",  // section 15 → cluster 3
        "16,  4",  // section 16 → cluster 4
        "100, 25", // section 100 → cluster 25
    })
    void sectionToClusterXPositive(int sectionX, int expectedClusterX) {
        assertEquals(expectedClusterX, BRClusterBVH.sectionToClusterX(sectionX),
            "sectionX=" + sectionX + " should map to clusterX=" + expectedClusterX);
    }

    @ParameterizedTest
    @DisplayName("sectionToClusterZ：正數座標映射（與 X 對稱）")
    @CsvSource({
        "0,   0",
        "3,   0",
        "4,   1",
        "8,   2",
        "63,  15",
        "64,  16",
    })
    void sectionToClusterZPositive(int sectionZ, int expectedClusterZ) {
        assertEquals(expectedClusterZ, BRClusterBVH.sectionToClusterZ(sectionZ));
    }

    @Test
    @DisplayName("sectionToClusterX/Z：負座標（地下 section）使用 Math.floorDiv")
    void sectionToClusterNegativeCoords() {
        // section -1 應屬 cluster -1（floorDiv(-1, 4) = -1）
        assertEquals(-1, BRClusterBVH.sectionToClusterX(-1),
            "floorDiv(-1, 4) = -1 (不是 0，因為 -1/4 向下取整)");
        assertEquals(-1, BRClusterBVH.sectionToClusterX(-4),
            "floorDiv(-4, 4) = -1");
        assertEquals(-2, BRClusterBVH.sectionToClusterX(-5),
            "floorDiv(-5, 4) = -2");
        assertEquals(-1, BRClusterBVH.sectionToClusterX(-3));
        assertEquals(-1, BRClusterBVH.sectionToClusterX(-2));

        // 對稱驗證 Z
        assertEquals(-1, BRClusterBVH.sectionToClusterZ(-1));
        assertEquals(-3, BRClusterBVH.sectionToClusterZ(-9));
    }

    @Test
    @DisplayName("4×4 cluster 邊界：section (0,0), (3,0), (0,3), (3,3) 均屬 cluster (0,0)")
    void fourByfourSectionBoundary() {
        assertEquals(0, BRClusterBVH.sectionToClusterX(0));
        assertEquals(0, BRClusterBVH.sectionToClusterX(3));
        assertEquals(0, BRClusterBVH.sectionToClusterZ(0));
        assertEquals(0, BRClusterBVH.sectionToClusterZ(3));
    }

    @Test
    @DisplayName("4×4 cluster 邊界：section (4,4) 屬 cluster (1,1)")
    void clusterBoundaryTransition() {
        assertEquals(1, BRClusterBVH.sectionToClusterX(4));
        assertEquals(1, BRClusterBVH.sectionToClusterZ(4));
        // cluster (1,1) 覆蓋 section (4,4)-(7,7)
        assertEquals(1, BRClusterBVH.sectionToClusterX(7));
        assertEquals(1, BRClusterBVH.sectionToClusterZ(7));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Cluster Key 編碼 / 解碼
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("encodeClusterKey / decode：正數座標往返")
    void clusterKeyRoundTripPositive() {
        int cx = 5, cz = 12;
        long key = BRClusterBVH.encodeClusterKey(cx, cz);
        assertEquals(cx, BRClusterBVH.decodeClusterX(key),
            "decodeClusterX 應與原始 cx 相同");
        assertEquals(cz, BRClusterBVH.decodeClusterZ(key),
            "decodeClusterZ 應與原始 cz 相同");
    }

    @Test
    @DisplayName("encodeClusterKey / decode：負數座標往返")
    void clusterKeyRoundTripNegative() {
        int cx = -3, cz = -7;
        long key = BRClusterBVH.encodeClusterKey(cx, cz);
        assertEquals(cx, BRClusterBVH.decodeClusterX(key));
        assertEquals(cz, BRClusterBVH.decodeClusterZ(key));
    }

    @Test
    @DisplayName("encodeClusterKey / decode：極端值（最大正整數）")
    void clusterKeyRoundTripExtreme() {
        int cx = Integer.MAX_VALUE;
        int cz = Integer.MIN_VALUE;
        long key = BRClusterBVH.encodeClusterKey(cx, cz);
        assertEquals(cx, BRClusterBVH.decodeClusterX(key));
        assertEquals(cz, BRClusterBVH.decodeClusterZ(key));
    }

    @Test
    @DisplayName("不同 cluster 的 key 應唯一（樣本抽查）")
    void clusterKeyUniqueness() {
        long k00 = BRClusterBVH.encodeClusterKey(0, 0);
        long k10 = BRClusterBVH.encodeClusterKey(1, 0);
        long k01 = BRClusterBVH.encodeClusterKey(0, 1);
        long k11 = BRClusterBVH.encodeClusterKey(1, 1);
        long k_1_0 = BRClusterBVH.encodeClusterKey(-1, 0);
        long k0_1 = BRClusterBVH.encodeClusterKey(0, -1);

        assertNotEquals(k00, k10, "Cluster (0,0) vs (1,0) 的 key 應不同");
        assertNotEquals(k00, k01, "Cluster (0,0) vs (0,1) 的 key 應不同");
        assertNotEquals(k10, k01, "Cluster (1,0) vs (0,1) 的 key 應不同");
        assertNotEquals(k00, k11, "Cluster (0,0) vs (1,1) 的 key 應不同");
        assertNotEquals(k00, k_1_0, "Cluster (0,0) vs (-1,0) 的 key 應不同");
        assertNotEquals(k00, k0_1, "Cluster (0,0) vs (0,-1) 的 key 應不同");
        assertNotEquals(k_1_0, k0_1, "Cluster (-1,0) vs (0,-1) 的 key 應不同");
    }

    @Test
    @DisplayName("同一 cluster 的 key 應相同（多次呼叫）")
    void clusterKeyIdempotent() {
        long k1 = BRClusterBVH.encodeClusterKey(3, 7);
        long k2 = BRClusterBVH.encodeClusterKey(3, 7);
        assertEquals(k1, k2, "相同輸入的 encodeClusterKey 應有相同輸出");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ClusterEntry — AABB 計算
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ClusterEntry 初始 AABB：XZ 由 clusterX/Z 決定，Y 未初始化")
    void clusterEntryInitialAABB() {
        // 手動建構 ClusterEntry（package-private，透過 BRClusterBVH 測試）
        // 使用反射替代（考慮到 ClusterEntry 是 package-private static class）
        // 改為測試 toAabbData 的 Y 預設值
        BRClusterBVH.ClusterEntry entry = createEntry(2, 3);

        // XZ 應由 cluster index 決定
        float expectedMinX = 2 * BRClusterBVH.CLUSTER_SIZE * 16.0f; // = 128
        float expectedMinZ = 3 * BRClusterBVH.CLUSTER_SIZE * 16.0f; // = 192
        float expectedMaxX = expectedMinX + BRClusterBVH.CLUSTER_SIZE * 16.0f; // = 192
        float expectedMaxZ = expectedMinZ + BRClusterBVH.CLUSTER_SIZE * 16.0f; // = 256

        assertEquals(expectedMinX, entry.minX, 0.001f, "初始 minX = clusterX × 4 × 16");
        assertEquals(expectedMinZ, entry.minZ, 0.001f, "初始 minZ = clusterZ × 4 × 16");
        assertEquals(expectedMaxX, entry.maxX, 0.001f, "初始 maxX = minX + 4 × 16");
        assertEquals(expectedMaxZ, entry.maxZ, 0.001f, "初始 maxZ = minZ + 4 × 16");
    }

    @Test
    @DisplayName("ClusterEntry.expandY：單次 expandY 更新 minY/maxY")
    void clusterEntryExpandYSingle() {
        BRClusterBVH.ClusterEntry entry = createEntry(0, 0);
        entry.expandY(-16.0f, 256.0f);

        assertEquals(-16.0f, entry.minY, 0.001f);
        assertEquals(256.0f, entry.maxY, 0.001f);
    }

    @Test
    @DisplayName("ClusterEntry.expandY：多次展開取最大包圍")
    void clusterEntryExpandYMultiple() {
        BRClusterBVH.ClusterEntry entry = createEntry(0, 0);
        entry.expandY(0.0f, 128.0f);
        entry.expandY(-32.0f, 64.0f);   // 擴展 minY
        entry.expandY(16.0f, 320.0f);   // 擴展 maxY

        assertEquals(-32.0f, entry.minY, 0.001f, "minY 應取三次中的最小值");
        assertEquals(320.0f, entry.maxY, 0.001f, "maxY 應取三次中的最大值");
    }

    @Test
    @DisplayName("ClusterEntry.toAabbData：Y 未初始化時使用預設值 (0, 256)")
    void clusterEntryToAabbDataDefaultY() {
        BRClusterBVH.ClusterEntry entry = createEntry(0, 0);
        float[] aabb = entry.toAabbData();

        assertNotNull(aabb);
        assertEquals(6, aabb.length, "AABB 陣列長度應為 6（minX,minY,minZ,maxX,maxY,maxZ）");
        assertEquals(0.0f, aabb[1], 0.001f, "Y 未初始化時 minY 預設為 0");
        assertEquals(256.0f, aabb[4], 0.001f, "Y 未初始化時 maxY 預設為 256");
    }

    @Test
    @DisplayName("ClusterEntry.toAabbData：Y 已初始化時正確返回")
    void clusterEntryToAabbDataWithY() {
        BRClusterBVH.ClusterEntry entry = createEntry(1, 2);
        entry.expandY(-8.0f, 128.0f);
        float[] aabb = entry.toAabbData();

        assertNotNull(aabb);
        assertEquals(6, aabb.length);

        float expectedMinX = 1 * 4 * 16.0f;
        float expectedMinZ = 2 * 4 * 16.0f;
        float expectedMaxX = expectedMinX + 4 * 16.0f;
        float expectedMaxZ = expectedMinZ + 4 * 16.0f;

        assertEquals(expectedMinX, aabb[0], 0.001f);
        assertEquals(-8.0f,        aabb[1], 0.001f);
        assertEquals(expectedMinZ, aabb[2], 0.001f);
        assertEquals(expectedMaxX, aabb[3], 0.001f);
        assertEquals(128.0f,       aabb[4], 0.001f);
        assertEquals(expectedMaxZ, aabb[5], 0.001f);
    }

    @Test
    @DisplayName("ClusterEntry.toAabbData：minX < maxX, minZ < maxZ（AABB 非退化）")
    void clusterEntryAabbNonDegenerate() {
        BRClusterBVH.ClusterEntry entry = createEntry(0, 0);
        entry.expandY(0.0f, 256.0f);
        float[] aabb = entry.toAabbData();

        assertTrue(aabb[0] < aabb[3], "minX < maxX");
        assertTrue(aabb[1] < aabb[4], "minY < maxY");
        assertTrue(aabb[2] < aabb[5], "minZ < maxZ");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Section 到 Cluster 打包整合測試
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("同一 4×4 cluster 的所有 16 個 section 產生相同 clusterKey")
    void allSectionsInClusterShareKey() {
        int baseSectionX = 8; // cluster (2, 1)
        int baseSectionZ = 4;
        int cx = BRClusterBVH.sectionToClusterX(baseSectionX);
        int cz = BRClusterBVH.sectionToClusterZ(baseSectionZ);
        long expectedKey = BRClusterBVH.encodeClusterKey(cx, cz);

        // 遍歷 4×4 = 16 個 section
        for (int dx = 0; dx < 4; dx++) {
            for (int dz = 0; dz < 4; dz++) {
                int sx = baseSectionX + dx;
                int sz = baseSectionZ + dz;
                int thisCx = BRClusterBVH.sectionToClusterX(sx);
                int thisCz = BRClusterBVH.sectionToClusterZ(sz);
                long thisKey = BRClusterBVH.encodeClusterKey(thisCx, thisCz);

                assertEquals(expectedKey, thisKey,
                    String.format("Section (%d,%d) 應屬同一 cluster key=%d", sx, sz, expectedKey));
            }
        }
    }

    @Test
    @DisplayName("超出 4×4 邊界的 section 產生不同 clusterKey")
    void sectionsOutsideClusterHaveDifferentKey() {
        // cluster (0,0) 覆蓋 section (0,0) ~ (3,3)
        long k00 = BRClusterBVH.encodeClusterKey(0, 0);
        // section (4,0) 屬 cluster (1,0)
        int cx4 = BRClusterBVH.sectionToClusterX(4);
        int cz0 = BRClusterBVH.sectionToClusterZ(0);
        long k10 = BRClusterBVH.encodeClusterKey(cx4, cz0);
        assertNotEquals(k00, k10, "Section (4,0) 不屬 cluster (0,0)");
    }

    @Test
    @DisplayName("TLAS 縮減因子：N sections → ceil(N / 16) clusters")
    void tlasReductionFactor() {
        // 模擬 32 個 section（跨 2 個 cluster，假設排列在 8×4 的 XZ）
        // cluster (0,0): section (0,0)-(3,3) = 16 sections
        // cluster (1,0): section (4,0)-(7,3) = 16 sections
        java.util.Set<Long> clusterKeys = new java.util.HashSet<>();
        for (int sx = 0; sx < 8; sx++) {
            for (int sz = 0; sz < 4; sz++) {
                int cx = BRClusterBVH.sectionToClusterX(sx);
                int cz = BRClusterBVH.sectionToClusterZ(sz);
                clusterKeys.add(BRClusterBVH.encodeClusterKey(cx, cz));
            }
        }
        // 32 sections → 2 clusters（縮減 16×）
        assertEquals(2, clusterKeys.size(),
            "8×4=32 sections 應對應 2 個 cluster（縮減 16×）");
    }

    @Test
    @DisplayName("ClusterEntry.toString：包含 clusterX, clusterZ, sectionCount")
    void clusterEntryToString() {
        BRClusterBVH.ClusterEntry entry = createEntry(5, 7);
        entry.sectionCount = 8;
        String s = entry.toString();
        assertTrue(s.contains("5"), "toString 應包含 clusterX=5");
        assertTrue(s.contains("7"), "toString 應包含 clusterZ=7");
        assertTrue(s.contains("8"), "toString 應包含 sectionCount=8");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Helper
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 建立測試用 ClusterEntry。
     * 測試類別與 BRClusterBVH 在同一套件（com.blockreality.api.client.render.rt），
     * 可直接存取 package-private 建構子，無需反射。
     */
    private BRClusterBVH.ClusterEntry createEntry(int cx, int cz) {
        return new BRClusterBVH.ClusterEntry(cx, cz);
    }
}
