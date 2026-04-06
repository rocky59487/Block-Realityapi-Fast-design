package com.blockreality.api.physics.sparse;

import com.blockreality.api.physics.PhysicsConstants;
import com.blockreality.api.physics.RBlockState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

/**
 * 粗粒度有限元素引擎 — 階層式物理 Layer 2。
 *
 * 核心概念：
 *   每個 VoxelSection (16³) 視為 FEM 的一個元素節點。
 *   材質屬性 = Section 內所有非空方塊的加權平均。
 *   用 Gauss-Seidel 迭代求解 Section 級的應力場。
 *
 * 壓縮比：4096:1（每 Section 4096 blocks → 1 node）
 * 目標：10K active sections → 100-500ms 完成
 *
 * 結果用途：
 *   1. StressHeatmapRenderer 的 LOD 顯示（遠距 Section 用粗估值）
 *   2. 識別高應力 Section → 觸發 Layer 1 精確分析
 *   3. 全域應力分佈概覽
 *
 * @since v3.0 Phase 2
 */
public class CoarseFEMEngine {

    private static final Logger LOGGER = LogManager.getLogger("BR/CoarseFEM");

    /** 重力加速度 */
    private static final double GRAVITY = PhysicsConstants.GRAVITY;

    /** 最大迭代次數 */
    private static final int MAX_ITERATIONS = 50;

    /** 收斂閾值（相對殘差） */
    private static final double CONVERGENCE_THRESHOLD = 0.005;

    /** SOR 鬆弛因子 */
    private static final double OMEGA = 1.4;

    /** 6 方向偏移 */
    private static final int[][] NEIGHBORS = {
        {1, 0, 0}, {-1, 0, 0},
        {0, 1, 0}, {0, -1, 0},
        {0, 0, 1}, {0, 0, -1}
    };

    // ═══ 分析入口 ═══

    /**
     * 執行 Section 級粗粒度應力分析。
     *
     * 演算法：
     *   1. 為每個非空 Section 計算加權平均材質屬性
     *   2. 計算每個 Section 的自重（密度 × nonAirCount × g）
     *   3. 識別錨定 Section（固定邊界條件）
     *   4. Gauss-Seidel SOR 迭代：從上到下傳遞荷載
     *   5. 計算每個 Section 的應力利用率
     *
     * @param svo 稀疏體素八叉樹
     * @return 粗粒度應力結果
     */
    public static CoarseFEMResult analyze(SparseVoxelOctree svo) {
        long t0 = System.nanoTime();

        // Phase 1: 收集 Section 屬性
        Map<Long, SectionProperties> sectionProps = new LinkedHashMap<>();
        Set<Long> anchorSections = new HashSet<>();

        int minSX = svo.getMinX() >> 4, maxSX = svo.getMaxX() >> 4;
        int minSY = svo.getMinY() >> 4, maxSY = svo.getMaxY() >> 4;
        int minSZ = svo.getMinZ() >> 4, maxSZ = svo.getMaxZ() >> 4;

        for (int sx = minSX; sx <= maxSX; sx++) {
            for (int sy = minSY; sy <= maxSY; sy++) {
                for (int sz = minSZ; sz <= maxSZ; sz++) {
                    VoxelSection section = svo.getSection(sx, sy, sz);
                    if (section == null || section.isEmpty()) continue;

                    long key = SparseVoxelOctree.sectionKey(sx, sy, sz);
                    SectionProperties props = computeSectionProperties(section);
                    sectionProps.put(key, props);

                    // 錨定判定：包含錨定方塊或在底層
                    if (props.hasAnchor || sy <= (svo.getMinY() >> 4) + 1) {
                        anchorSections.add(key);
                    }
                }
            }
        }

        int totalNodes = sectionProps.size();
        if (totalNodes == 0) {
            return CoarseFEMResult.empty();
        }

        // Phase 2: Gauss-Seidel SOR 荷載傳遞
        Map<Long, Double> cumulativeLoad = new HashMap<>(); // Section → 累積荷載 (N)
        Map<Long, Double> stressRatio = new HashMap<>();    // Section → 應力利用率

        // 初始化自重
        for (var entry : sectionProps.entrySet()) {
            cumulativeLoad.put(entry.getKey(), entry.getValue().totalWeight);
        }

        // 按高度排序（從高到低）
        List<Long> sortedByHeight = new ArrayList<>(sectionProps.keySet());
        sortedByHeight.sort((a, b) -> {
            int ya = SparseVoxelOctree.sectionKeyYStatic(a);
            int yb = SparseVoxelOctree.sectionKeyYStatic(b);
            return Integer.compare(yb, ya); // 降序
        });

        int iterations = 0;
        double maxResidual = Double.MAX_VALUE;

        for (int iter = 0; iter < MAX_ITERATIONS && maxResidual > CONVERGENCE_THRESHOLD; iter++) {
            maxResidual = 0;

            for (long key : sortedByHeight) {
                if (anchorSections.contains(key)) continue; // 錨定點荷載固定

                SectionProperties props = sectionProps.get(key);
                double oldLoad = cumulativeLoad.getOrDefault(key, 0.0);

                // 收集上方傳來的荷載
                double incomingLoad = props.totalWeight; // 自重

                int sx = SparseVoxelOctree.sectionKeyXStatic(key);
                int sy = SparseVoxelOctree.sectionKeyYStatic(key);
                int sz = SparseVoxelOctree.sectionKeyZStatic(key);

                // 上方 Section 傳遞的荷載（主要傳遞路徑：重力方向）
                long aboveKey = SparseVoxelOctree.sectionKey(sx, sy + 1, sz);
                if (sectionProps.containsKey(aboveKey)) {
                    incomingLoad += cumulativeLoad.getOrDefault(aboveKey, 0.0);
                }

                // ★ #16 fix: 水平方向側向力傳遞（懸臂/斜撐的荷載分擔）
                // 每個水平鄰居貢獻其累積荷載的一小部分（側向分擔比例）
                // 僅當該鄰居上方無直接支撐時才傳遞（模擬懸臂效應）
                double lateralFraction = 0.15; // 側向荷載傳遞係數
                for (int[] offset : NEIGHBORS) {
                    if (offset[1] != 0) continue; // 跳過垂直方向（已處理）
                    long neighborKey = SparseVoxelOctree.sectionKey(
                        sx + offset[0], sy + offset[1], sz + offset[2]);
                    if (!sectionProps.containsKey(neighborKey)) continue;
                    // 檢查鄰居上方是否有支撐 — 無支撐時才傳遞側向荷載
                    long neighborAbove = SparseVoxelOctree.sectionKey(
                        sx + offset[0], sy + 1, sz + offset[2]);
                    if (!sectionProps.containsKey(neighborAbove)) {
                        incomingLoad += cumulativeLoad.getOrDefault(neighborKey, 0.0) * lateralFraction;
                    }
                }

                // SOR 更新
                double newLoad = oldLoad + OMEGA * (incomingLoad - oldLoad);
                cumulativeLoad.put(key, newLoad);

                // 殘差
                double residual = Math.abs(newLoad - oldLoad) / (Math.abs(oldLoad) + 1.0);
                maxResidual = Math.max(maxResidual, residual);
            }

            iterations++;
        }

        // Phase 3: 計算應力利用率
        int highStressSections = 0;
        int criticalSections = 0;

        for (var entry : sectionProps.entrySet()) {
            long key = entry.getKey();
            SectionProperties props = entry.getValue();
            double load = cumulativeLoad.getOrDefault(key, 0.0);

            // 應力 = 荷載 / (有效截面積 × 壓縮強度)
            double effectiveArea = props.nonAirCount * PhysicsConstants.BLOCK_AREA;
            // capacity should be in N.
            // effectiveArea is in m². avgCompStrength is in MPa.
            // 1 MPa = 1e6 Pa = 1e6 N/m²
            double capacity = effectiveArea * (props.avgCompStrength * 1e6);

            double ratio = (capacity > 0) ? load / capacity : 1.0;
            ratio = Math.min(ratio, 2.0); // 上限 200%

            stressRatio.put(key, ratio);

            if (ratio > 0.7) highStressSections++;
            if (ratio > 1.0) criticalSections++;
        }

        long elapsed = System.nanoTime() - t0;

        CoarseFEMResult result = new CoarseFEMResult(
            totalNodes,
            anchorSections.size(),
            iterations,
            maxResidual,
            stressRatio,
            highStressSections,
            criticalSections,
            elapsed
        );

        LOGGER.debug("[CoarseFEM] {} nodes, {} anchors, {} iters (residual={:.4f}), {} high-stress, {}ms",
            totalNodes, anchorSections.size(), iterations, maxResidual,
            highStressSections, String.format("%.1f", elapsed / 1e6));

        return result;
    }

    // ═══ Section 屬性計算 ═══

    /**
     * 計算 Section 的加權平均材質屬性。
     */
    private static SectionProperties computeSectionProperties(VoxelSection section) {
        double totalMass = 0;
        double totalCompStrength = 0;
        double totalTensStrength = 0;
        int nonAirCount = 0;
        boolean hasAnchor = false;

        for (int i = 0; i < VoxelSection.VOLUME; i++) {
            RBlockState state = section.getBlockByIndex(i);
            if (state == null || state == RBlockState.AIR) continue;

            totalMass += state.mass();
            totalCompStrength += state.compressiveStrength();
            totalTensStrength += state.tensileStrength();
            nonAirCount++;

            if (state.isAnchor()) hasAnchor = true;
        }

        if (nonAirCount == 0) {
            return new SectionProperties(0, 0, 0, 0, 0, false);
        }

        return new SectionProperties(
            nonAirCount,
            totalMass * GRAVITY,                    // 總重量 (N)
            totalCompStrength / nonAirCount,        // 平均壓縮強度
            totalTensStrength / nonAirCount,        // 平均拉伸強度
            totalMass / nonAirCount,                // 平均密度
            hasAnchor
        );
    }

    /**
     * Section 加權平均屬性。
     */
    private record SectionProperties(
        int nonAirCount,
        double totalWeight,         // N
        double avgCompStrength,     // MPa
        double avgTensStrength,     // MPa
        double avgDensity,          // kg/m³
        boolean hasAnchor
    ) {}

    // ═══ 結果 ═══

    /**
     * 粗粒度 FEM 分析結果。
     */
    public record CoarseFEMResult(
        int totalNodes,
        int anchorNodes,
        int iterations,
        double finalResidual,
        Map<Long, Double> sectionStressRatios,
        int highStressSections,
        int criticalSections,
        long computeTimeNs
    ) {
        public double computeTimeMs() { return computeTimeNs / 1e6; }

        public static CoarseFEMResult empty() {
            return new CoarseFEMResult(0, 0, 0, 0, Map.of(), 0, 0, 0);
        }

        /**
         * 取得高應力 Section 的 key 列表（用於觸發 Layer 1 精確分析）。
         *
         * @param threshold 應力閾值（0.0-1.0）
         */
        public List<Long> getHighStressSections(double threshold) {
            List<Long> result = new ArrayList<>();
            for (var entry : sectionStressRatios.entrySet()) {
                if (entry.getValue() > threshold) {
                    result.add(entry.getKey());
                }
            }
            return result;
        }

        @Override
        public String toString() {
            return String.format(
                "CoarseFEM[nodes=%d, anchors=%d, iters=%d, residual=%.4f, highStress=%d, critical=%d, %.1fms]",
                totalNodes, anchorNodes, iterations, finalResidual,
                highStressSections, criticalSections, computeTimeMs()
            );
        }
    }
}
