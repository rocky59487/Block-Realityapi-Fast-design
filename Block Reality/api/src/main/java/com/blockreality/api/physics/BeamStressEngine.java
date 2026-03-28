package com.blockreality.api.physics;

import com.blockreality.api.block.RBlockEntity;
import com.blockreality.api.material.DefaultMaterial;
import com.blockreality.api.material.RMaterial;
import com.blockreality.api.material.VanillaMaterialMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Euler-Bernoulli 梁應力引擎 — 高精度結構分析。
 *
 * 靈感來源：NASA/Cornell Voxelyze 體素物理引擎。
 *
 * 與 SupportPathAnalyzer 的差異：
 *   SPA = BFS + 簡化力矩公式（即時近似，50ms 內完成）
 *   BSE = 梁元素模型 + 內力計算（精確但較慢，適合小型結構）
 *
 * 演算法流程：
 *   1. 掃描指定區域，為每對相鄰 RBlock 建立 BeamElement
 *   2. 從錨定點出發，沿梁元素傳遞荷載（自重 + 累積荷載）
 *   3. 每根梁計算軸力、彎矩、剪力
 *   4. 對比容許值，計算利用率（utilization ratio）
 *   5. 利用率 > 1.0 的梁 = 結構失效
 *
 * 使用時機：
 *   - /br_stress --precise 指令
 *   - 結構方塊數 < 500 時自動啟用
 *   - /fd export 前的最終驗證
 *
 * 效能特性：
 *   - O(N×K) 其中 N=方塊數, K=平均鄰居數(≤6)
 *   - 不求解全局剛度矩陣（O(N³) 太慢），使用局部梁力學
 *   - 異步執行，透過 CompletableFuture 回傳結果
 */
public class BeamStressEngine {

    private static final Logger LOGGER = LogManager.getLogger("BR-BeamStress");

    /** 推薦的最大方塊數（超過時退回到 SupportPathAnalyzer） */
    public static final int RECOMMENDED_MAX_BLOCKS = 500;

    /** 重力加速度 (m/s²) */
    private static final double GRAVITY = 9.81;

    /**
     * 分析結果
     */
    public record BeamAnalysisResult(
        /** 所有梁元素 */
        List<BeamElement> beams,
        /** 每個方塊的最大利用率 */
        Map<BlockPos, Float> utilizationMap,
        /** 失效的梁（利用率 > 1.0） */
        List<BeamElement> failedBeams,
        /** 失效方塊及原因 */
        Map<BlockPos, String> failures,
        /** 結構總方塊數 */
        int totalBlocks,
        /** 分析耗時 (ms) */
        double elapsedMs
    ) {
        public boolean isStable() { return failedBeams.isEmpty(); }
        public int failureCount() { return failures.size(); }
    }

    /**
     * 異步執行梁應力分析 — 含 5 秒超時保護。
     *
     * @param level  伺服器世界
     * @param center 分析中心
     * @param radius 分析半徑
     * @return CompletableFuture 包含分析結果（可能因超時而拋出異常）
     */
    public static CompletableFuture<BeamAnalysisResult> analyzeAsync(
            ServerLevel level, BlockPos center, int radius) {

        // Phase 1: 在主線程收集方塊數據（需要 level 存取）
        long t0 = System.nanoTime();
        StructureSnapshot snapshot = captureStructure(level, center, radius);

        if (snapshot.blocks.size() > RECOMMENDED_MAX_BLOCKS) {
            LOGGER.warn("[BeamStress] Structure has {} blocks (recommended max {}), analysis may be slow",
                snapshot.blocks.size(), RECOMMENDED_MAX_BLOCKS);
        }

        // Phase 2: 異步計算梁應力 + 超時 + 異常處理
        return CompletableFuture.supplyAsync(() -> {
            return computeBeamStress(snapshot, t0);
        })
        .orTimeout(5, TimeUnit.SECONDS)
        .exceptionally(ex -> {
            LOGGER.error("[BeamStress] Beam stress analysis timed out or failed: {}", ex.getMessage(), ex);
            // 返回空結果作為降級方案
            return new BeamAnalysisResult(
                List.of(),
                Map.of(),
                List.of(),
                Map.of(),
                snapshot.blocks.size(),
                -1.0 // 標記為失敗（-1.0 ms）
            );
        });
    }

    /**
     * 同步版本（給測試指令使用）。
     */
    public static BeamAnalysisResult analyze(ServerLevel level, BlockPos center, int radius) {
        long t0 = System.nanoTime();
        StructureSnapshot snapshot = captureStructure(level, center, radius);
        return computeBeamStress(snapshot, t0);
    }

    // ═══════════════════════════════════════════════════════
    //  內部資料結構
    // ═══════════════════════════════════════════════════════

    /**
     * 結構快照 — 主線程收集，工作線程只讀。
     */
    private record StructureSnapshot(
        Map<BlockPos, RMaterial> blocks,
        Set<BlockPos> anchors,
        Map<BlockPos, Double> weights // 每個方塊的自重 (N)
    ) {}

    // ═══════════════════════════════════════════════════════
    //  Phase 1: 收集結構數據（主線程）
    // ═══════════════════════════════════════════════════════

    private static StructureSnapshot captureStructure(ServerLevel level, BlockPos center, int radius) {
        Map<BlockPos, RMaterial> blocks = new LinkedHashMap<>();
        Set<BlockPos> anchors = new HashSet<>();
        Map<BlockPos, Double> weights = new HashMap<>();

        BlockPos min = center.offset(-radius, -radius, -radius);
        BlockPos max = center.offset(radius, radius, radius);

        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) continue;

                    BlockEntity be = level.getBlockEntity(pos);
                    RMaterial mat;

                    if (be instanceof RBlockEntity rbe) {
                        mat = rbe.getMaterial();
                        if (mat == null) mat = DefaultMaterial.STONE; // 防禦性 null check
                        if (rbe.isAnchored()) anchors.add(pos);
                    } else {
                        // 原版方塊 → VanillaMaterialMap
                        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                        mat = VanillaMaterialMap.getInstance().getMaterial(blockId);
                        // 原版實心方塊 = 隱式錨定
                        anchors.add(pos);
                    }

                    // 天然錨定
                    if (state.is(net.minecraft.world.level.block.Blocks.BEDROCK) ||
                        state.is(net.minecraft.world.level.block.Blocks.BARRIER)) {
                        anchors.add(pos);
                    }
                    // 底部方塊
                    if (pos.getY() <= level.getMinBuildHeight() + 1) {
                        anchors.add(pos);
                    }

                    blocks.put(pos, mat);
                    weights.put(pos, mat.getDensity() * GRAVITY); // kg/m³ × m³ × g = N
                }
            }
        }

        return new StructureSnapshot(blocks, anchors, weights);
    }

    // ═══════════════════════════════════════════════════════
    //  Phase 2: 梁應力計算（可異步）
    // ═══════════════════════════════════════════════════════

    private static BeamAnalysisResult computeBeamStress(StructureSnapshot snapshot, long t0) {
        Map<BlockPos, RMaterial> blocks = snapshot.blocks;
        Set<BlockPos> anchors = snapshot.anchors;

        // ─── Step 1: 建立梁元素 ───
        List<BeamElement> beams = new ArrayList<>();
        Set<Long> beamSet = new HashSet<>(); // 防止重複建梁

        for (BlockPos pos : blocks.keySet()) {
            RMaterial matA = blocks.get(pos);
            for (Direction dir : Direction.values()) {
                BlockPos neighbor = pos.relative(dir);
                if (!blocks.containsKey(neighbor)) continue;

                // 使用排序後的 key 防止重複
                long key = beamKey(pos, neighbor);
                if (beamSet.contains(key)) continue;
                beamSet.add(key);

                RMaterial matB = blocks.get(neighbor);
                beams.add(BeamElement.create(pos, neighbor, matA, matB));
            }
        }

        // ─── Step 2: 從錨定點 BFS 傳遞荷載 ───
        // 每個方塊的累積荷載（自重 + 上方傳來的荷載）
        Map<BlockPos, Double> cumulativeLoad = new HashMap<>();
        // 每個方塊的最大利用率
        Map<BlockPos, Float> utilizationMap = new HashMap<>();
        List<BeamElement> failedBeams = new ArrayList<>();
        Map<BlockPos, String> failures = new HashMap<>();

        // 初始化：每個方塊承載自重
        for (var entry : snapshot.weights.entrySet()) {
            cumulativeLoad.put(entry.getKey(), entry.getValue());
        }

        // 從最高的非錨定方塊向下累積荷載
        // （重力方向：上方方塊的重量傳遞到下方）
        List<BlockPos> sortedByHeight = new ArrayList<>(blocks.keySet());
        sortedByHeight.sort((a, b) -> Integer.compare(b.getY(), a.getY())); // 從高到低

        Set<BlockPos> processed = new HashSet<>();

        for (BlockPos pos : sortedByHeight) {
            if (processed.contains(pos)) continue;
            processed.add(pos);

            double myLoad = cumulativeLoad.getOrDefault(pos, 0.0);

            // ★ A-4 fix: 荷載傳遞 — 優先向下，若無下方支撐則分攤至水平鄰居
            BlockPos below = pos.below();
            if (blocks.containsKey(below)) {
                // 有下方支撐：全部荷載向下傳遞（重力路徑）
                cumulativeLoad.merge(below, myLoad, Double::sum);
            } else {
                // 無下方支撐：水平分攤至有支撐能力的水平鄰居
                // 這使水平梁段的 getLoadAbove() 能正確反映傳遞的荷載
                List<BlockPos> horizontalSupports = new ArrayList<>();
                for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                    BlockPos neighbor = pos.relative(dir);
                    if (blocks.containsKey(neighbor)) {
                        horizontalSupports.add(neighbor);
                    }
                }
                if (!horizontalSupports.isEmpty()) {
                    double share = myLoad / horizontalSupports.size();
                    for (BlockPos neighbor : horizontalSupports) {
                        cumulativeLoad.merge(neighbor, share, Double::sum);
                    }
                }
            }
        }

        // ─── Step 3: 評估每根梁的應力 ───
        for (BeamElement beam : beams) {
            BlockPos a = beam.nodeA();
            BlockPos b = beam.nodeB();

            // 軸力：垂直方向梁承受累積荷載
            double axialForce = 0;
            if (a.getY() != b.getY()) {
                // 垂直梁：取上方節點的累積荷載
                BlockPos upper = a.getY() > b.getY() ? a : b;
                axialForce = cumulativeLoad.getOrDefault(upper, 0.0);
            }

            // ★ v4-fix: 修正彎矩與剪力計算
            double moment = 0;
            double shear = 0;
            if (a.getY() == b.getY()) {
                // ★ A-3 fix: 水平梁彎矩計算 — ��用標準結構力學公式
                // 兩端上方荷載視為兩端等效集中力
                double loadAboveA = getLoadAbove(a, blocks, cumulativeLoad);
                double loadAboveB = getLoadAbove(b, blocks, cumulativeLoad);
                double L = beam.length();

                // 簡支梁模型：兩端支撐，上方荷載視為均布 + 不平衡分量
                // 等效均布荷載 q = (loadAboveA + loadAboveB) / L  (N/m)
                // M_max (均布) = q × L² / 8  (簡支梁中點最大彎矩)
                // 不平衡修正：兩端荷載差異產生額外彎矩
                // M_unbal = |Fa - Fb| × L / 6  (三角形分布近似)
                double totalLoad = loadAboveA + loadAboveB;
                double q = L > 0 ? totalLoad / L : 0;
                double distributedMoment = q * L * L / 8.0; // M = qL²/8
                double unbalancedMoment = Math.abs(loadAboveA - loadAboveB) * L / 6.0;
                moment = distributedMoment + unbalancedMoment;

                // 剪力 V_max = q × L / 2（均布簡支梁最大剪力）
                shear = q * L / 2.0;
            }

            // 計算利用率
            double utilization = beam.utilizationRatio(axialForce, moment, shear);

            // 記錄到每個節點
            utilizationMap.merge(a, (float) utilization, Math::max);
            utilizationMap.merge(b, (float) utilization, Math::max);

            // 判定失效
            if (utilization > 1.0) {
                failedBeams.add(beam);
                String reason = String.format(
                    "Beam %s→%s: utilization=%.2f (N=%.0fN, M=%.0fN·m, V=%.0fN)",
                    a.toShortString(), b.toShortString(),
                    utilization, axialForce, moment, shear
                );
                failures.put(a, reason);
                failures.put(b, reason);
            }
        }

        // ─── Step 4: 檢查無支撐方塊（不在錨定 BFS 範圍內） ───
        Set<BlockPos> reachable = bfsFromAnchors(blocks.keySet(), anchors);
        for (BlockPos pos : blocks.keySet()) {
            if (!reachable.contains(pos) && !anchors.contains(pos)) {
                failures.put(pos, "Isolated: not reachable from any anchor");
                utilizationMap.put(pos, 1.0f);
            }
        }

        double elapsed = (System.nanoTime() - t0) / 1e6;

        LOGGER.info("[BeamStress] Analyzed {} blocks, {} beams in {}ms: {} failures",
            blocks.size(), beams.size(), String.format("%.1f", elapsed), failures.size());

        return new BeamAnalysisResult(
            beams, utilizationMap, failedBeams, failures,
            blocks.size(), elapsed
        );
    }

    // ═══════════════════════════════════════════════════════
    //  輔助方法
    // ═══════════════════════════════════════════════════════

    /**
     * 取得方塊上方的荷載（用於水平梁的剪力估算）。
     */
    private static double getLoadAbove(BlockPos pos, Map<BlockPos, RMaterial> blocks,
                                        Map<BlockPos, Double> cumulativeLoad) {
        BlockPos above = pos.above();
        if (blocks.containsKey(above)) {
            return cumulativeLoad.getOrDefault(above, 0.0);
        }
        return 0;
    }

    /**
     * 從錨定點 BFS，找出所有可達方塊。
     */
    private static Set<BlockPos> bfsFromAnchors(Set<BlockPos> allBlocks, Set<BlockPos> anchors) {
        Set<BlockPos> reachable = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();

        for (BlockPos anchor : anchors) {
            if (allBlocks.contains(anchor)) {
                reachable.add(anchor);
                queue.add(anchor);
            }
        }

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            for (Direction dir : Direction.values()) {
                BlockPos neighbor = current.relative(dir);
                if (allBlocks.contains(neighbor) && !reachable.contains(neighbor)) {
                    reachable.add(neighbor);
                    queue.add(neighbor);
                }
            }
        }

        return reachable;
    }

    /**
     * 梁的唯一 key（兩個端點的有序組合）。
     */
    private static long beamKey(BlockPos a, BlockPos b) {
        long la = a.asLong();
        long lb = b.asLong();
        // 使用 XOR + 黃金比例常數，避免 la*31 溢位產生碰撞
        long lo = Math.min(la, lb);
        long hi = Math.max(la, lb);
        return lo ^ (hi * 0x9E3779B97F4A7C15L);
    }
}
