package com.blockreality.api.physics;

import com.blockreality.api.block.RBlockEntity;
import com.blockreality.api.config.BRConfig;
import com.blockreality.api.material.BlockType;
import com.blockreality.api.material.DefaultMaterial;
import com.blockreality.api.material.DynamicMaterial;
import com.blockreality.api.material.RMaterial;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * RC 融合偵測器 — 偵測相鄰鋼筋+混凝土自動產生 RC 融合節點。
 *
 * 設計哲學（來自 v3fix 手冊 + 想法.docx）：
 *   現實工程中，鋼筋混凝土 (Reinforced Concrete) 的強度不是
 *   鋼筋+混凝土的簡單加總，而是有經驗公式：
 *
 *     R_RC_tens  = R_concrete_tens + R_rebar_tens × φ_tens (φ=0.8)
 *     R_RC_shear = R_concrete_shear + R_rebar_shear × φ_shear (φ=0.6)
 *     R_RC_comp  = R_concrete_comp × compBoost (1.1)
 *
 * 觸發時機：
 *   1. 當 REBAR 或 CONCRETE 類型的 RBlock 被放置時
 *   2. 掃描 6 個鄰居，看有沒有互補的材料
 *   3. 如果有 → 將兩個方塊升級為 RC_NODE + 設定融合材料
 *
 * 蜂窩效應（品質控制）：
 *   現實澆灌混凝土時，振搗不確實會產生蜂窩空洞，降低強度。
 *   rcFusionHoneycombProb 設定蜂窩機率（預設 15%），模擬施工品質不確定性。
 */
public class RCFusionDetector {

    private static final Logger LOGGER = LogManager.getLogger("BR-RCFusion");
    private static final Direction[] ALL_DIRS = Direction.values();

    /**
     * 檢查剛放置的方塊是否觸發 RC 融合。
     *
     * @param level 世界
     * @param pos   剛放置的方塊位置
     * @return 發生融合的數量
     */
    public static int checkAndFuse(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof RBlockEntity rbe)) return 0;

        BlockType myType = rbe.getBlockType();
        // 只有 REBAR 或 CONCRETE 能觸發融合
        if (myType != BlockType.REBAR && myType != BlockType.CONCRETE) return 0;

        int fusionCount = 0;
        int maxSpacing = BRConfig.INSTANCE.rcFusionRebarSpacingMax.get();

        for (Direction dir : ALL_DIRS) {
            // 掃描方向上 maxSpacing 格以內的鄰居
            for (int dist = 1; dist <= maxSpacing; dist++) {
                BlockPos neighborPos = pos.relative(dir, dist);
                BlockEntity neighborBe = level.getBlockEntity(neighborPos);

                if (!(neighborBe instanceof RBlockEntity neighborRbe)) break; // 遇到非 RBlock 就停

                BlockType neighborType = neighborRbe.getBlockType();

                // 互補檢查：REBAR + CONCRETE
                boolean canFuse = (myType == BlockType.REBAR && neighborType == BlockType.CONCRETE)
                               || (myType == BlockType.CONCRETE && neighborType == BlockType.REBAR);

                if (canFuse) {
                    // ★ Round 5 fix: 蜂窩機率改用確定性 hash，避免 Math.random() 不可重現
                    // hash 基於兩個方塊的位置，確保相同位置總是產生相同結果
                    double honeycombProb = BRConfig.INSTANCE.rcFusionHoneycombProb.get();
                    boolean hasHoneycomb = deterministicHoneycomb(pos, neighborPos, honeycombProb);

                    // ★ R6-2 fix: 傳入 BlockType 明確區分鋼筋/混凝土角色，
                    // 避免自訂材料 Rtens 高於鋼筋時角色互換
                    RMaterial fusedMat = calculateFusionMaterial(
                        rbe.getMaterial(), myType,
                        neighborRbe.getMaterial(), neighborType, hasHoneycomb);

                    // 升級雙方為 RC_NODE
                    upgradeToRC(rbe, fusedMat);
                    upgradeToRC(neighborRbe, fusedMat);

                    fusionCount++;
                    LOGGER.debug("[RCFusion] Fused {} + {} at {} (honeycomb={})",
                        myType, neighborType, pos, hasHoneycomb);
                }

                // 如果鄰居已經是 RC_NODE，也停止搜索（不重複融合）
                if (neighborType == BlockType.RC_NODE) break;
            }
        }

        return fusionCount;
    }

    /**
     * 計算 RC 融合材料的等效參數。
     *
     * ★ R6-2 fix: 以 BlockType 明確區分鋼筋和混凝土角色。
     * 舊版以 Rtens 大小判定角色，在自訂高強度混凝土（Rtens > 普通鋼筋）
     * 場景下會導致角色互換，compBoost 套用到鋼筋而非混凝土。
     *
     * @param matA         材料 A
     * @param typeA        材料 A 的方塊類型
     * @param matB         材料 B
     * @param typeB        材料 B 的方塊類型
     * @param hasHoneycomb 是否有蜂窩空洞（降低強度）
     */
    private static RMaterial calculateFusionMaterial(
            RMaterial matA, BlockType typeA,
            RMaterial matB, BlockType typeB,
            boolean hasHoneycomb) {
        double phiTens = BRConfig.INSTANCE.rcFusionPhiTens.get();
        double phiShear = BRConfig.INSTANCE.rcFusionPhiShear.get();
        double compBoost = BRConfig.INSTANCE.rcFusionCompBoost.get();

        // ★ R6-2: 以 BlockType 判定角色（唯一正確來源），而非 Rtens 大小
        RMaterial rebar, concrete;
        if (typeA == BlockType.REBAR) {
            rebar = matA;
            concrete = matB;
        } else if (typeB == BlockType.REBAR) {
            rebar = matB;
            concrete = matA;
        } else {
            // 回退：兩者都非 REBAR（罕見情況），仍以 Rtens 判定
            if (matA.getRtens() > matB.getRtens()) {
                rebar = matA;
                concrete = matB;
            } else {
                rebar = matB;
                concrete = matA;
            }
        }

        return DynamicMaterial.ofRCFusion(concrete, rebar, phiTens, phiShear, compBoost, hasHoneycomb);
    }

    /**
     * 將 RBlockEntity 升級為 RC_NODE。
     * ★ B-2 fix: 升級前保存原始材料，降級時可精確恢復。
     */
    private static void upgradeToRC(RBlockEntity rbe, RMaterial fusedMat) {
        if (rbe.getBlockType() == BlockType.RC_NODE) return; // 已經是 RC
        // ★ B-2: 保存融合前的原始材料（用於降級恢復）
        rbe.setPreFusionMaterial(rbe.getMaterial());
        rbe.setBlockType(BlockType.RC_NODE);
        rbe.setMaterial(fusedMat);
    }

    // ═══════════════════════════════════════════════════════
    //  W-5: RC 融合降級 — 破壞鋼筋/混凝土後，鄰居 RC_NODE 降級
    // ═══════════════════════════════════════════════════════

    /**
     * 當 REBAR 或 CONCRETE 被破壞時，檢查相鄰的 RC_NODE 是否需要降級。
     *
     * 降級邏輯：
     *   RC_NODE 必須同時與 REBAR 和 CONCRETE 相鄰才維持 RC 狀態。
     *   如果失去任一互補材料 → 降級回原始類型：
     *     - 如果周圍只剩 REBAR → 此 RC_NODE 降級為 CONCRETE（它原本是混凝土端）
     *     - 如果周圍只剩 CONCRETE → 此 RC_NODE 降級為 REBAR（它原本是鋼筋端）
     *     - 如果兩種都還有 → 重新計算融合材料
     *     - 如果兩種都沒了 → 降級為 CONCRETE（安全預設）
     *
     * @param level 世界
     * @param brokenPos 被破壞的方塊位置
     * @param brokenType 被破壞的方塊原始類型
     * @return 降級的 RC_NODE 數量
     */
    public static int checkAndDowngrade(ServerLevel level, BlockPos brokenPos, BlockType brokenType) {
        // 只有 REBAR / CONCRETE / RC_NODE 被破壞時才需要檢查降級
        if (brokenType != BlockType.REBAR && brokenType != BlockType.CONCRETE
            && brokenType != BlockType.RC_NODE) return 0;

        int downgradeCount = 0;
        int maxSpacing = BRConfig.INSTANCE.rcFusionRebarSpacingMax.get();

        for (Direction dir : ALL_DIRS) {
            for (int dist = 1; dist <= maxSpacing; dist++) {
                BlockPos neighborPos = brokenPos.relative(dir, dist);
                BlockEntity neighborBe = level.getBlockEntity(neighborPos);

                if (!(neighborBe instanceof RBlockEntity neighborRbe)) break;

                // 只處理 RC_NODE
                if (neighborRbe.getBlockType() != BlockType.RC_NODE) {
                    // 遇到非 RC_NODE 就停止（間距中斷）
                    break;
                }

                // 掃描這個 RC_NODE 周圍是否仍有互補材料
                boolean hasRebar = false;
                boolean hasConcrete = false;
                RMaterial rebarMat = null;
                RMaterial concreteMat = null;

                for (Direction scanDir : ALL_DIRS) {
                    for (int scanDist = 1; scanDist <= maxSpacing; scanDist++) {
                        BlockPos scanPos = neighborPos.relative(scanDir, scanDist);
                        if (scanPos.equals(brokenPos)) continue; // 跳過被破壞的方塊

                        BlockEntity scanBe = level.getBlockEntity(scanPos);
                        if (!(scanBe instanceof RBlockEntity scanRbe)) break;

                        BlockType scanType = scanRbe.getBlockType();
                        if (scanType == BlockType.REBAR) {
                            hasRebar = true;
                            rebarMat = scanRbe.getMaterial();
                        } else if (scanType == BlockType.CONCRETE) {
                            hasConcrete = true;
                            concreteMat = scanRbe.getMaterial();
                        } else if (scanType == BlockType.RC_NODE) {
                            // 相鄰 RC_NODE 提供兩種角色
                            hasRebar = true;
                            hasConcrete = true;
                            break;
                        } else {
                            break;
                        }
                    }
                    if (hasRebar && hasConcrete) break; // 早退
                }

                if (hasRebar && hasConcrete) {
                    // 兩種都還在 → 仍可維持 RC，但重算融合材料
                    if (rebarMat != null && concreteMat != null) {
                        double honeycombProb = BRConfig.INSTANCE.rcFusionHoneycombProb.get();
                        // ★ Round 5 fix: 確定性 hash（與 checkAndFuse 一致）
                        boolean hasHoneycomb = deterministicHoneycomb(brokenPos, neighborPos, honeycombProb);
                        // ★ R6-2 fix: 明確傳入 BlockType 區分角色
                        RMaterial newFused = calculateFusionMaterial(
                            rebarMat, BlockType.REBAR,
                            concreteMat, BlockType.CONCRETE, hasHoneycomb);
                        neighborRbe.setMaterial(newFused);
                    }
                    // 如果材料為 null（來自其他 RC_NODE），保持現狀
                } else {
                    // ★ B-2 fix: 優先使用 preFusionMaterial 恢復原始材料
                    RMaterial preFusion = neighborRbe.getPreFusionMaterial();

                    if (hasRebar) {
                        // 只剩鋼筋 → 此 RC_NODE 原本是混凝土端 → 降級為 CONCRETE
                        neighborRbe.setBlockType(BlockType.CONCRETE);
                        neighborRbe.setMaterial(preFusion != null ? preFusion : DefaultMaterial.CONCRETE);
                        downgradeCount++;
                        LOGGER.info("[RCFusion] Downgraded RC_NODE at {} to CONCRETE (lost concrete neighbor)", neighborPos);
                    } else if (hasConcrete) {
                        // 只剩混凝土 → 此 RC_NODE 原本是鋼筋端 → 降級為 REBAR
                        neighborRbe.setBlockType(BlockType.REBAR);
                        neighborRbe.setMaterial(preFusion != null ? preFusion : DefaultMaterial.REBAR);
                        downgradeCount++;
                        LOGGER.info("[RCFusion] Downgraded RC_NODE at {} to REBAR (lost rebar neighbor)", neighborPos);
                    } else {
                        // 兩種都沒了 → 恢復原始材料或安全預設 CONCRETE
                        neighborRbe.setBlockType(BlockType.CONCRETE);
                        neighborRbe.setMaterial(preFusion != null ? preFusion : DefaultMaterial.CONCRETE);
                        downgradeCount++;
                        LOGGER.info("[RCFusion] Downgraded RC_NODE at {} to CONCRETE (no complementary neighbors)", neighborPos);
                    }

                    // 清除 preFusionMaterial — 已恢復，不再需要
                    neighborRbe.setPreFusionMaterial(null);
                }
            }
        }

        return downgradeCount;
    }

    // ═══════════════════════════════════════════════════════
    //  ★ Round 5: 確定性蜂窩判定 — 取代 Math.random()
    // ═══════════════════════════════════════════════════════

    /**
     * 基於方塊位置的確定性蜂窩判定。
     *
     * 使用 FNV-1a hash 將兩個 BlockPos 打包成 [0,1) 偽隨機值，
     * 相同的兩個位置永遠產生相同結果（可重現、伺服器重啟一致）。
     * 原本使用 Math.random() 會導致：
     *   1. 多執行緒呼叫不確定性
     *   2. 結構重載後融合結果不同
     *   3. 自動化測試無法重現
     *
     * @param posA      第一個方塊位置
     * @param posB      第二個方塊位置
     * @param threshold 蜂窩機率閾值 (0~1)
     * @return true = 有蜂窩空洞
     */
    private static boolean deterministicHoneycomb(BlockPos posA, BlockPos posB, double threshold) {
        // FNV-1a 64-bit hash
        long hash = 0xcbf29ce484222325L;
        final long FNV_PRIME = 0x100000001b3L;

        // 混入 posA 座標
        hash ^= posA.getX(); hash *= FNV_PRIME;
        hash ^= posA.getY(); hash *= FNV_PRIME;
        hash ^= posA.getZ(); hash *= FNV_PRIME;
        // 混入 posB 座標
        hash ^= posB.getX(); hash *= FNV_PRIME;
        hash ^= posB.getY(); hash *= FNV_PRIME;
        hash ^= posB.getZ(); hash *= FNV_PRIME;
        // 混入 salt 防止與其他 hash 碰撞
        hash ^= 0xDEAD_BEEF_CAFE_BABEL; hash *= FNV_PRIME;

        // 轉換為 [0, 1) — 取低 52 bits 映射到 double mantissa
        double normalized = (double) (hash & 0x001F_FFFF_FFFF_FFFFL) / (double) 0x0020_0000_0000_0000L;
        return normalized < threshold;
    }
}
