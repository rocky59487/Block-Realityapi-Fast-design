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
        NO_SUPPORT,         // 完全無支撐（孤島）
        TENSION_BREAK       // Fix 3: 拉力斷裂（outward flux 超過 Rtens）
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

        // ─── Phase 1: 從 scopeBlocks 收集 RBlock + 識別錨定點 ───
        // 關鍵設計：只有 RBlock 參與結構分析。
        // 原版方塊（泥土、石頭等）視為「地形」，不加入分析集合。
        // 原版方塊若與 RBlock 相鄰，作為隱式錨定點提供支撐。
        Set<BlockPos> allBlocks = new HashSet<>();
        Deque<BfsNode> queue = new ArrayDeque<>();

        // ★ 島嶼感知修正：掃描 scopeBlocks 及其 1 格鄰域以找到隱式錨定
        // （原版方塊作為支撐源，但不在 allBlocks 中）
        Set<BlockPos> scanned = new HashSet<>();
        for (BlockPos islandPos : scopeBlocks) {
            // 掃描島嶼成員本身
            if (scanned.add(islandPos)) {
                BlockState state = level.getBlockState(islandPos);
                if (state.isAir()) continue;

                BlockEntity be = level.getBlockEntity(islandPos);
                if (!(be instanceof RBlockEntity)) continue;

                allBlocks.add(islandPos);
                if (isAnchor(level, islandPos, state)) {
                    queue.add(new BfsNode(islandPos, 0, 0f, 0.0, islandPos));
                    stable.add(islandPos);
                    stressMap.put(islandPos, 0f);
                }
            }

            // 掃描 6 鄰居 — 找原版方塊作為隱式錨定
            for (Direction dir : ALL_DIRS) {
                BlockPos neighbor = islandPos.relative(dir);
                if (!scanned.add(neighbor)) continue;
                if (allBlocks.contains(neighbor)) continue;

                BlockState nState = level.getBlockState(neighbor);
                if (nState.isAir()) continue;

                BlockEntity nBe = level.getBlockEntity(neighbor);
                if (nBe instanceof RBlockEntity) {
                    // 鄰居是 RBlock 但不在島嶼成員中 → 不加入分析（防止誤觸）
                    continue;
                }

                // 原版實心方塊 = 隱式錨定（地形永遠穩定）
                queue.add(new BfsNode(neighbor, 0, 0f, 0.0, neighbor));
                stable.add(neighbor);
                stressMap.put(neighbor, 0f);
            }
        }

        // ─── Phase 2: Weighted Stress BFS ───
        Set<BlockPos> visited = new HashSet<>(stable);
        int processed = 0;

        while (!queue.isEmpty() && processed < maxBlocks) {
            if (System.nanoTime() > deadline) {
                LOGGER.warn("[SupportPath] Analysis timeout after {} blocks", processed);
                break;
            }

            BfsNode current = queue.poll();
            processed++;

            for (Direction dir : ALL_DIRS) {
                BlockPos neighborPos = current.pos.relative(dir);
                if (visited.contains(neighborPos)) continue;
                if (!allBlocks.contains(neighborPos)) continue;

                visited.add(neighborPos);

                BlockState neighborState = level.getBlockState(neighborPos);
                RMaterial neighborMat = getMaterial(level, neighborPos, neighborState);
                ChiselState neighborChisel = getChiselState(level, neighborPos);
                float neighborWeight = (float) (neighborMat.getDensity() * neighborChisel.fillRatio()); // 按填充率縮放自重

                // ─── 力臂計算 ───
                // 水平方向移動 = 力臂 +1
                // 垂直方向移動 = 力臂重置（重力方向不產生力矩）
                int newArmLength;
                BlockPos newAnchor;

                if (dir == Direction.DOWN) {
                    // 往下 = 重力方向 = 力臂重置，自己變成新的支撐參考點
                    newArmLength = 0;
                    newAnchor = neighborPos;
                } else if (dir == Direction.UP) {
                    // 往上 = 被支撐 = 力臂 +1（懸吊結構）
                    newArmLength = current.armLength + 1;
                    newAnchor = current.lastAnchorPos;
                } else {
                    // 水平方向 = 懸臂延伸 = 力臂 +1
                    newArmLength = current.armLength + 1;
                    newAnchor = current.lastAnchorPos;
                }

                // ─── 累積載重 + 力矩 ───
                float newLoad = current.accumulatedLoad + neighborWeight;

                // ★ A-1/A-2 核心修正：逐塊累加力矩 M = Σ(w_i × g × d_i)
                // 水平步：此方塊在 newArmLength 處貢獻 w×g×d
                // 垂直步（DOWN）：力矩重置（重力路徑不產生懸臂力矩）
                double newMoment;
                if (newArmLength == 0) {
                    newMoment = 0.0; // 重力方向 or 錨定 → 力矩歸零
                } else {
                    newMoment = current.accumulatedMoment
                        + neighborWeight * GRAVITY * newArmLength;
                }

                // ─── 判定 0: 跨距快速拒絕 (Block Physics Overhaul 梁強度模型) ───
                // 如果水平延伸距離超過材料的 maxSpan，直接判定斷裂，
                // 不需要進入力矩計算。
                if (newArmLength > 0 && newArmLength > neighborMat.getMaxSpan()) {
                    failures.put(neighborPos, new FailureReason(
                        FailureType.CANTILEVER_BREAK,
                        String.format("Span=%d > MaxSpan=%d (%s)",
                            newArmLength, neighborMat.getMaxSpan(), neighborMat.getMaterialId())
                    ));
                    stressMap.put(neighborPos, 1.0f);
                    continue;
                }

                // ─── 判定 1: 懸臂力矩檢查 ───
                // ★ A-1/A-2 fix: 逐塊累加力矩 M = Σ(w_i × g × d_i)
                // 每塊方塊在其實際力臂距離處貢獻力矩，正確處理非均勻重量
                // 例：混凝土(2400kg)在arm=1 + 木材(600kg)在arm=2
                //   M = 2400×9.81×1 + 600×9.81×2 = 35,316 N⋅m（精確值）
                if (newArmLength > 0) {
                    // ★ A-1/A-2 fix: 使用逐塊累加力矩（非均勻重量正確）
                    // M = Σ(w_i × g × d_i) — 每塊在其實際力臂距離計算
                    double moment = newMoment;

                    // 連接點的抗彎能力 = Rtens(Pa) × W(m³)
                    // W = 截面模數 = I/y = (bh³/12)/(h/2) = bh²/6
                    // 對 1m × 1m 方塊：W = 1×1²/6 = 1/6 ≈ 0.1667 m³
                    RMaterial connectionMat = getConnectionMaterial(level, neighborPos, current.pos);
                    // 使用鄰居方塊的實際截面模數（雕刻形狀影響抗彎能力）
                    double sectionModulus = neighborChisel.sectionModulusX(); // m³
                    double momentCapacity = connectionMat.getRtens() * 1e6 * sectionModulus; // N⋅m

                    if (moment > momentCapacity) {
                        // 懸臂斷裂！
                        failures.put(neighborPos, new FailureReason(
                            FailureType.CANTILEVER_BREAK,
                            String.format("Moment=%.0f > Capacity=%.0f (arm=%d, Rtens=%.1f)",
                                moment, momentCapacity, newArmLength, connectionMat.getRtens())
                        ));
                        stressMap.put(neighborPos, 1.0f);
                        continue; // 不繼續 BFS — 斷裂點之後的方塊也會失效
                    }

                    // 記錄應力比
                    float stressRatio = momentCapacity > 0
                        ? (float) (moment / momentCapacity)
                        : 1.0f;
                    stressMap.put(neighborPos, Math.max(
                        stressMap.getOrDefault(neighborPos, 0f), stressRatio));
                }

                // ─── 判定 2: 壓碎檢查（在垂直路徑上） ───
                if (dir == Direction.DOWN || dir == Direction.UP) {
                    // ★ v4-fix: 正確的力/應力比較
                    // 載重力 F = mass(kg) × g(m/s²) = N
                    // 壓碎容量 = Rcomp(MPa) × 1e6(→Pa) × A(m²) = N
                    double loadForce = newLoad * GRAVITY; // N
                    // 使用鄰居方塊的實際截面積（雕刻形狀影響抗壓容量）
                    double compCapacity = neighborMat.getRcomp() * 1e6 * neighborChisel.crossSectionArea(); // N
                    if (loadForce > compCapacity) {
                        failures.put(neighborPos, new FailureReason(
                            FailureType.CRUSHING,
                            String.format("Force=%.0fN > Capacity=%.0fN (Rcomp=%.1fMPa)",
                                loadForce, compCapacity, neighborMat.getRcomp())
                        ));
                        stressMap.put(neighborPos, 1.0f);
                        continue;
                    }

                    // 壓力應力比（利用率）
                    float compStress = compCapacity > 0
                        ? (float) (loadForce / compCapacity)
                        : 0f;
                    stressMap.merge(neighborPos, compStress, Math::max);
                }

                // ─── 安全 → 繼續 BFS ───
                stable.add(neighborPos);
                if (!stressMap.containsKey(neighborPos)) {
                    stressMap.put(neighborPos, 0f);
                }
                queue.add(new BfsNode(neighborPos, newArmLength, newLoad, newMoment, newAnchor));
            }
        }

        // ★ W-8 fix: 如果 BFS 因預算用盡而提前結束，未訪問的方塊不一定是 NO_SUPPORT
        boolean bfsExhausted = (processed >= maxBlocks) || (System.nanoTime() > deadline);
        if (bfsExhausted) {
            LOGGER.warn("[SupportPath] BFS budget exhausted: processed={}/{}, unvisited RBlocks may be falsely marked NO_SUPPORT. " +
                "Consider increasing bfs_max_blocks or bfs_max_ms in config.", processed, maxBlocks);
        }

        // ─── Phase 3: 未被 BFS 觸及的方塊 = 無支撐 ───
        // 區分兩種原因：
        //   A. 上游有方塊斷裂 (CANTILEVER_BREAK/CRUSHING)，只能經由它到達 → cascade failure
        //   B. 完全與錨定點不連通 → 孤島
        //   C. ★ W-8: BFS 預算不足導致未訪問（標記但不崩塌）
        Set<BlockPos> failedBarrier = failures.keySet();
        for (BlockPos pos : allBlocks) {
            if (!visited.contains(pos)) {
                // ★ W-8: 如果 BFS 因預算用盡而停止，未訪問的方塊可能只是沒輪到
                // 只有在 BFS 正常結束（佇列清空）時才確信是真正的 NO_SUPPORT
                if (bfsExhausted) {
                    // ★ #19 fix: BFS 預算耗盡 → 標記為「未知」而非「穩定」
                    // 不加入 stable 也不加入 failures，以 0.5f 應力提示使用者需關注
                    // 不觸發崩塌（保守策略），但也不虛報為安全
                    stressMap.put(pos, 0.5f);
                    // ★ 移除: stable.add(pos) — 未驗證的方塊不應被標記為穩定
                    continue;
                }

                // BFS 正常結束 → 真正的無支撐
                boolean isCascade = false;
                for (Direction dir : ALL_DIRS) {
                    if (failedBarrier.contains(pos.relative(dir))) {
                        isCascade = true;
                        break;
                    }
                }
                String detail = isCascade
                    ? "Cascade: upstream support failed"
                    : "Not reachable from any anchor (isolated)";
                failures.put(pos, new FailureReason(FailureType.NO_SUPPORT, detail));
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
