package com.blockreality.api.physics;

import com.blockreality.api.block.RBlockEntity;
import com.blockreality.api.chisel.ChiselState;
import com.blockreality.api.config.BRConfig;
import com.blockreality.api.material.BlockType;
import com.blockreality.api.material.DefaultMaterial;
import com.blockreality.api.material.DynamicMaterial;
import com.blockreality.api.material.RMaterial;
import com.blockreality.api.material.VanillaMaterialMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 支撐路徑分析器 — 帶權重的應力評估 BFS (Weighted Stress BFS)
 *
 * 這不是純拓撲學的「有沒有連在一起」，而是加入了三個偽真實力學判定：
 *
 *   1. 懸臂樑效應 (Cantilever Moment)
 *      力矩 M = W × D（重量 × 距離）
 *      當 M > Rtens × 斷面積 → 連接點斷裂
 *      → 純混凝土陽台伸出 3 格就會從根部斷掉
 *      → 加了鋼筋的 RC 可以伸到 10+ 格
 *
 *   2. 載重累積 (Load Accumulation)
 *      BFS 沿路徑加總方塊重量，傳遞到支撐點
 *      當 ΣW > Rcomp × 斷面積 → 支撐點壓碎
 *      → 木柱撐不住 10 萬噸鐵堡壘
 *
 *   3. RC 融合加成 (RC Fusion Bonus)
 *      相鄰鋼筋+混凝土的連接點 Rtens 大幅提升
 *      → 有鋼筋的結構更強韌
 *
 * 架構定位（CTO 雙軌戰略）：
 *   Java 端 = 即時近似值（50ms 內給玩家合乎物理直覺的結果）
 *   TypeScript 端 = /fd export 時跑精確 FEA 矩陣
 */
public class SupportPathAnalyzer {

    private static final Logger LOGGER = LogManager.getLogger("BR-SupportPath");

    /** 6 方向鄰居 */
    private static final Direction[] ALL_DIRS = Direction.values();

    /** 重力加速度 (m/s²) — ★ audit-fix Y2-2: 統一使用 PhysicsConstants */
    private static final double GRAVITY = PhysicsConstants.GRAVITY;

    /** 1m×1m 正方形截面的截面模數 W = I/y = bh²/6 = 1/6 m³ */
    private static final double BLOCK_SECTION_MODULUS = 1.0 / 6.0;

    /** 方塊截面積 1m × 1m = 1 m² */
    private static final double BLOCK_CROSS_SECTION_AREA = 1.0;

    /**
     * 分析結果 — 包含每個方塊的應力狀態與崩塌判定。
     */
    public record AnalysisResult(
        /** 結構安全的方塊 */
        Set<BlockPos> stable,
        /** 應力過載需要崩塌的方塊（含原因） */
        Map<BlockPos, FailureReason> failures,
        /** 每個方塊承受的應力比 (0.0 ~ 1.0+)，用於視覺化 */
        Map<BlockPos, Float> stressMap,
        /** 分析耗時 (ms) */
        double elapsedMs
    ) {
        public int failureCount() { return failures.size(); }
        public int totalAnalyzed() { return stable.size() + failures.size(); }
    }

    /**
     * 崩塌原因
     */
    public enum FailureType {
        CANTILEVER_BREAK,   // 懸臂力矩超過 Rtens → 從根部斷裂
        CRUSHING,           // 載重超過 Rcomp → 壓碎
        NO_SUPPORT          // 完全無支撐（孤島）
    }

    public record FailureReason(FailureType type, String detail) {}

    /**
     * BFS 節點 — 攜帶路徑資訊
     */
    private record BfsNode(
        BlockPos pos,
        /** 從最近的支撐點到這裡的水平距離（力臂） */
        int armLength,
        /** 沿路徑累積的總重量 (kg) — 用於壓碎檢查 */
        float accumulatedLoad,
        /** 沿路徑累積的力矩 (N·m) — M = Σ(w_i × g × d_i)，每塊在其實際距離計算 */
        double accumulatedMoment,
        /** 最近的支撐點位置 */
        BlockPos lastAnchorPos
    ) {}

    // ═══════════════════════════════════════════════════════
    //  主分析入口
    // ═══════════════════════════════════════════════════════

    /**
     * 對指定區域進行帶權重的應力 BFS 分析。
     *
     * 從所有錨定點（基岩、地面、錨定 RBlock）開始 BFS，
     * 沿途計算力矩與載重，判定每個方塊是否安全。
     *
     * @param level  伺服器世界
     * @param center 分析中心點
     * @param radius 分析半徑
     * @return 完整分析結果
     */
    public static AnalysisResult analyze(ServerLevel level, BlockPos center, int radius) {
        // 用 center+radius 掃描模式 — 收集範圍內所有 RBlock
        Set<BlockPos> scopeBlocks = new HashSet<>();
        BlockPos min = center.offset(-radius, -radius, -radius);
        BlockPos max = center.offset(radius, radius, radius);
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockEntity be = level.getBlockEntity(pos);
                    if (be instanceof RBlockEntity) {
                        scopeBlocks.add(pos);
                    }
                }
            }
        }
        return analyzeBlocks(level, scopeBlocks);
    }

    /**
     * ★ 島嶼感知版本 — 只分析指定的方塊集合，避免掃描無關方塊。
     *
     * 修復 BUG: 放置方塊時 AABB radius 掃描會涵蓋不屬於該島嶼的 RBlock，
     * 導致無關方塊被誤判為不穩定而坍塌。
     *
     * @param level        伺服器世界
     * @param islandBlocks 指定要分析的方塊位置集合（通常是某個島嶼的成員）
     * @return 完整分析結果
     */
    public static AnalysisResult analyze(ServerLevel level, Set<BlockPos> islandBlocks) {
        return analyzeBlocks(level, islandBlocks);
    }

    /**
     * 內部實作 — Phase 1 收集 + Phase 2 BFS + Phase 3 判定。
     *
     * @param level       伺服器世界
     * @param scopeBlocks 需要分析的 RBlock 位置集合
     */
    private static AnalysisResult analyzeBlocks(ServerLevel level, Set<BlockPos> scopeBlocks) {
        long startTime = System.nanoTime();

        Set<BlockPos> stable = new HashSet<>();
        Map<BlockPos, FailureReason> failures = new LinkedHashMap<>();
        Map<BlockPos, Float> stressMap = new HashMap<>();

        int maxBlocks = BRConfig.INSTANCE.structureBfsMaxBlocks.get();
        int maxMs = BRConfig.INSTANCE.structureBfsMaxMs.get();
        long deadline = System.nanoTime() + maxMs * 1_000_000L;

        Set<BlockPos> allBlocks = new HashSet<>();
        Set<BlockPos> scanned = new HashSet<>();
        List<BlockPos> anchors = new ArrayList<>();

        for (BlockPos islandPos : scopeBlocks) {
            if (scanned.add(islandPos)) {
                BlockState state = level.getBlockState(islandPos);
                if (state.isAir()) continue;

                BlockEntity be = level.getBlockEntity(islandPos);
                if (!(be instanceof RBlockEntity)) continue;

                allBlocks.add(islandPos);
                if (isAnchor(level, islandPos, state)) {
                    anchors.add(islandPos);
                }
            }

            for (Direction dir : ALL_DIRS) {
                BlockPos neighbor = islandPos.relative(dir);
                if (!scanned.add(neighbor)) continue;
                if (allBlocks.contains(neighbor)) continue;

                BlockState nState = level.getBlockState(neighbor);
                if (nState.isAir()) continue;

                BlockEntity nBe = level.getBlockEntity(neighbor);
                if (nBe instanceof RBlockEntity) continue;

                anchors.add(neighbor);
            }
        }

        // Pass 1: Build BFS tree (DAG of support)
        Map<BlockPos, BlockPos> parentMap = new HashMap<>();
        Map<BlockPos, Integer> armLengths = new HashMap<>();
        List<BlockPos> orderedNodes = new ArrayList<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();

        for (BlockPos anchor : anchors) {
            queue.add(anchor);
            visited.add(anchor);
            armLengths.put(anchor, 0);
            stable.add(anchor);
            stressMap.put(anchor, 0f);
        }

        int processed = 0;
        while (!queue.isEmpty() && processed < maxBlocks) {
            if (System.nanoTime() > deadline) break;

            BlockPos current = queue.poll();
            orderedNodes.add(current);
            processed++;

            for (Direction dir : ALL_DIRS) {
                BlockPos neighborPos = current.relative(dir);
                if (visited.contains(neighborPos)) continue;
                if (!allBlocks.contains(neighborPos)) continue;

                visited.add(neighborPos);
                parentMap.put(neighborPos, current);

                int newArmLength = armLengths.get(current);
                if (dir == Direction.DOWN) {
                    newArmLength = 0;
                } else {
                    newArmLength += 1;
                }
                armLengths.put(neighborPos, newArmLength);

                queue.add(neighborPos);
            }
        }

        boolean bfsExhausted = (processed >= maxBlocks) || (System.nanoTime() > deadline);

        // Pass 2: Accumulate loads and moments from leaves to roots
        Map<BlockPos, Float> accumulatedLoad = new HashMap<>();
        Map<BlockPos, Double> accumulatedMoment = new HashMap<>();

        for (BlockPos pos : allBlocks) {
            accumulatedLoad.put(pos, 0f);
            accumulatedMoment.put(pos, 0.0);
        }
        for (BlockPos anchor : anchors) {
            accumulatedLoad.put(anchor, 0f);
            accumulatedMoment.put(anchor, 0.0);
        }

        // Process in reverse BFS order (leaves first)
        for (int i = orderedNodes.size() - 1; i >= 0; i--) {
            BlockPos current = orderedNodes.get(i);

            // Anchors don't have weight or fail
            if (anchors.contains(current)) continue;

            BlockState currentState = level.getBlockState(current);
            RMaterial currentMat = getMaterial(level, current, currentState);
            ChiselState currentChisel = getChiselState(level, current);
            float currentWeight = (float) (currentMat.getDensity() * currentChisel.fillRatio());

            // Add own weight
            float currentLoad = accumulatedLoad.get(current) + currentWeight;
            accumulatedLoad.put(current, currentLoad);

            // Check cross-span limit
            int armLength = armLengths.get(current);
            if (armLength > 0 && armLength > currentMat.getMaxSpan()) {
                failures.put(current, new FailureReason(
                    FailureType.CANTILEVER_BREAK,
                    String.format("Span=%d > MaxSpan=%d (%s)",
                        armLength, currentMat.getMaxSpan(), currentMat.getMaterialId())
                ));
                stressMap.put(current, 1.0f);
            }

            BlockPos parent = parentMap.get(current);
            if (parent != null) {
                // Determine direction to parent
                boolean isVertical = (current.getX() == parent.getX() && current.getZ() == parent.getZ());

                // Add load to parent
                float parentLoad = accumulatedLoad.get(parent) + currentLoad;
                accumulatedLoad.put(parent, parentLoad);

                // Calculate moment
                double moment = accumulatedMoment.get(current);
                if (!isVertical) {
                    moment += currentLoad * GRAVITY * 1.0; // 1 unit arm per step
                } else {
                    moment = 0.0; // Reset moment if supported vertically from below (or above)
                }

                // Accumulate moment to parent
                accumulatedMoment.put(parent, accumulatedMoment.get(parent) + moment);

                // Evaluate connection capacity AT the parent looking at the child
                RMaterial connectionMat = getConnectionMaterial(level, current, parent);
                double sectionModulus = currentChisel.sectionModulusX();
                double momentCapacity = connectionMat.getRtens() * 1e6 * sectionModulus;

                if (moment > momentCapacity) {
                    failures.put(current, new FailureReason(
                        FailureType.CANTILEVER_BREAK,
                        String.format("Moment=%.0f > Capacity=%.0f (Rtens=%.1f)",
                            moment, momentCapacity, connectionMat.getRtens())
                    ));
                    stressMap.put(current, 1.0f);
                } else {
                    float stressRatio = momentCapacity > 0 ? (float) (moment / momentCapacity) : 1.0f;
                    stressMap.put(current, Math.max(stressMap.getOrDefault(current, 0f), stressRatio));
                }

                // Evaluate crushing capacity
                if (isVertical) {
                    double loadForce = currentLoad * GRAVITY;
                    double compCapacity = currentMat.getRcomp() * 1e6 * currentChisel.crossSectionArea();
                    if (loadForce > compCapacity) {
                        failures.put(current, new FailureReason(
                            FailureType.CRUSHING,
                            String.format("Force=%.0fN > Capacity=%.0fN (Rcomp=%.1fMPa)",
                                loadForce, compCapacity, currentMat.getRcomp())
                        ));
                        stressMap.put(current, 1.0f);
                    } else {
                        float compStress = compCapacity > 0 ? (float) (loadForce / compCapacity) : 0f;
                        stressMap.merge(current, compStress, Math::max);
                    }
                }
            }
        }

        // Cascade failures: if a block fails, its children fail
        for (BlockPos pos : orderedNodes) {
            if (anchors.contains(pos)) continue;

            BlockPos parent = parentMap.get(pos);
            if (parent != null && failures.containsKey(parent)) {
                failures.put(pos, new FailureReason(FailureType.NO_SUPPORT, "Cascade: upstream support failed"));
                stressMap.put(pos, 1.0f);
            } else if (!failures.containsKey(pos)) {
                stable.add(pos);
                if (!stressMap.containsKey(pos)) {
                    stressMap.put(pos, 0f);
                }
            }
        }

        // Handle unvisited nodes
        for (BlockPos pos : allBlocks) {
            if (!visited.contains(pos)) {
                if (bfsExhausted) {
                    stressMap.put(pos, 0.5f);
                    continue;
                }
                failures.put(pos, new FailureReason(FailureType.NO_SUPPORT, "Not reachable from any anchor (isolated)"));
                stressMap.put(pos, 1.0f);
            }
        }

        double elapsed = (System.nanoTime() - startTime) / 1e6;
        LOGGER.info("[SupportPath] Analyzed {} blocks in {}ms: {} stable, {} failures",
            allBlocks.size(), String.format("%.1f", elapsed), stable.size(), failures.size());

        return new AnalysisResult(stable, failures, stressMap, elapsed);
    }
    // ═══════════════════════════════════════════════════════
    //  輔助方法
    // ═══════════════════════════════════════════════════════

    /**
     * 判定是否為錨定點。
     * 統一委託 AnchorContinuityChecker.isNaturalAnchor()，
     * 再加上 RBlockEntity.isAnchored() 的手動標記。
     */
    private static boolean isAnchor(ServerLevel level, BlockPos pos, BlockState state) {
        // 統一錨定判斷（基岩/屏障/底層/ANCHOR_PILE）
        if (AnchorContinuityChecker.isNaturalAnchor(level, pos)) return true;

        // RBlock 手動標記為錨定（可能由 ResultApplicator 寫入）
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof RBlockEntity rbe && rbe.isAnchored()) return true;

        return false;
    }

    /**
     * 取得方塊的材料參數。
     * 優先從 RBlockEntity 讀取，否則 fallback 到原版映射。
     *
     * ★ M-3 fix: 未錨定的 RC_NODE，Rtens 加成歸零，
     * 僅保留素混凝土數值（想法.docx 規定）。
     */
    /**
     * 取得方塊的雕刻狀態。非 RBlock 回傳完整方塊預設值。
     */
    private static ChiselState getChiselState(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof RBlockEntity rbe) {
            return rbe.getChiselState();
        }
        return ChiselState.FULL;
    }

    private static RMaterial getMaterial(ServerLevel level, BlockPos pos, BlockState state) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof RBlockEntity rbe) {
            // ★ M-3: RC_NODE 未錨定時折減 Rtens
            if (rbe.getBlockType() == com.blockreality.api.material.BlockType.RC_NODE && !rbe.isAnchored()) {
                RMaterial original = rbe.getMaterial();
                // 降級為素混凝土的 Rtens（只保留混凝土端強度，移除鋼筋加成）
                return DynamicMaterial.ofCustom(
                    original.getMaterialId() + "_unanchored",
                    original.getRcomp(),
                    DefaultMaterial.CONCRETE.getRtens(),  // 歸零到素混凝土 Rtens
                    DefaultMaterial.CONCRETE.getRshear(), // 歸零到素混凝土 Rshear
                    original.getDensity()
                );
            }
            return rbe.getMaterial();
        }

        // 原版方塊 fallback — 委託 VanillaMaterialMap（JSON 數據驅動）
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return VanillaMaterialMap.getInstance().getMaterial(blockId);
    }

    /**
     * 取得兩個方塊之間連接點的等效材料。
     *
     * RC 融合邏輯：
     *   如果一邊是鋼筋、另一邊是混凝土（或其中一個是 RC_NODE）：
     *   → 連接 Rtens = R_concrete_tens + R_rebar_tens × φ_tens
     *   → 這就是 v3fix 手冊裡的 RC Fusion 公式
     *
     * 否則取兩者的最小值（木桶原理：連接強度取決於最弱的一方）。
     */
    private static RMaterial getConnectionMaterial(ServerLevel level, BlockPos a, BlockPos b) {
        RMaterial matA = getMaterial(level, a, level.getBlockState(a));
        RMaterial matB = getMaterial(level, b, level.getBlockState(b));

        // RC 融合檢查
        if (isRCPair(level, a, b, matA, matB)) {
            return createRCFusionMaterial(matA, matB);
        }

        // 木桶原理：取最弱的
        if (matA.getRtens() < matB.getRtens()) return matA;
        return matB;
    }

    /**
     * 檢查兩個方塊是否構成 RC 配對（鋼筋+混凝土）。
     */
    private static boolean isRCPair(ServerLevel level, BlockPos a, BlockPos b,
                                     RMaterial matA, RMaterial matB) {
        // 檢查 BlockType
        BlockEntity beA = level.getBlockEntity(a);
        BlockEntity beB = level.getBlockEntity(b);

        boolean aIsRebar = false, aIsConcrete = false;
        boolean bIsRebar = false, bIsConcrete = false;

        if (beA instanceof RBlockEntity rbeA) {
            switch (rbeA.getBlockType()) {
                case REBAR -> aIsRebar = true;
                case CONCRETE, PLAIN -> aIsConcrete = true;
                case RC_NODE -> { return true; } // 已經是 RC
            }
        } else {
            // 原版方塊 — 鐵塊當鋼筋，石頭當混凝土
            BlockState stateA = level.getBlockState(a);
            if (stateA.is(Blocks.IRON_BLOCK)) aIsRebar = true;
            else aIsConcrete = true; // 預設當混凝土
        }

        if (beB instanceof RBlockEntity rbeB) {
            switch (rbeB.getBlockType()) {
                case REBAR -> bIsRebar = true;
                case CONCRETE, PLAIN -> bIsConcrete = true;
                case RC_NODE -> { return true; }
            }
        } else {
            BlockState stateB = level.getBlockState(b);
            if (stateB.is(Blocks.IRON_BLOCK)) bIsRebar = true;
            else bIsConcrete = true;
        }

        return (aIsRebar && bIsConcrete) || (aIsConcrete && bIsRebar);
    }

    /**
     * 建立 RC 融合的等效材料。
     *
     * 公式（v3fix 手冊）：
     *   R_RC_tens  = R_concrete_tens + R_rebar_tens × φ_tens
     *   R_RC_shear = R_concrete_shear + R_rebar_shear × φ_shear
     *   R_RC_comp  = R_concrete_comp × compBoost
     */
    private static RMaterial createRCFusionMaterial(RMaterial matA, RMaterial matB) {
        double phiTens = BRConfig.INSTANCE.rcFusionPhiTens.get();
        double phiShear = BRConfig.INSTANCE.rcFusionPhiShear.get();
        double compBoost = BRConfig.INSTANCE.rcFusionCompBoost.get();

        // 找出哪個是混凝土、哪個是鋼筋
        RMaterial concrete, rebar;
        if (matA.getRtens() > matB.getRtens()) {
            rebar = matA; concrete = matB;
        } else {
            rebar = matB; concrete = matA;
        }

        // 使用 DynamicMaterial 回傳真實計算值（BUG-1 修復，統一與 RCFusionDetector 的邏輯）
        return DynamicMaterial.ofRCFusion(concrete, rebar, phiTens, phiShear, compBoost, false);
    }
}
