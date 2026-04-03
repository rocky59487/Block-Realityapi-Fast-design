package com.blockreality.api.spi;

import com.blockreality.api.material.BlockType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nonnull;

/**
 * RC 融合偵測器介面 (RC Fusion Detector SPI)
 *
 * RC Fusion Detector (RCFusionDetector) — Detects reinforced concrete (RC) fusion.
 *
 * 核心責任 (Core Responsibilities):
 *   - 偵測相鄰的鋼筋 (REBAR) 和混凝土 (CONCRETE) 方塊，自動升級為 RC 融合節點 (RC_NODE)
 *   - 當支撐材料被破壞時，檢查 RC_NODE 是否需要降級回原始類型
 *   - 計算 RC 融合材料的等效強度參數（根據工程公式）
 *   - 模擬施工品質不確定性（蜂窩效應）
 *
 * 設計哲學 (Design Philosophy):
 *   現實工程中，鋼筋混凝土 (Reinforced Concrete) 的強度不是
 *   鋼筋+混凝土的簡單加總，而是遵循經驗公式：
 *     R_RC_tens  = R_concrete_tens + R_rebar_tens × φ_tens (φ=0.8)
 *     R_RC_shear = R_concrete_shear + R_rebar_shear × φ_shear (φ=0.6)
 *     R_RC_comp  = R_concrete_comp × compBoost (1.1)
 *
 * 觸發時機 (Trigger Conditions):
 *   1. 當 REBAR 或 CONCRETE 類型的 RBlock 被放置時
 *   2. 掃描 6 個鄰居（限定距離內），看有沒有互補的材料
 *   3. 如果有 → 將雙方升級為 RC_NODE 並設定融合材料
 *
 * 蜂窩效應 (Honeycomb Effect):
 *   現實澆灌混凝土時，振搗不確實會產生蜂窩空洞，降低強度。
 *   rcFusionHoneycombProb 設定蜂窩機率，模擬施工品質不確定性。
 *
 * 線程安全 (Thread Safety):
 *   Method calls are thread-safe for concurrent access from game and physics threads.
 *   All operations are localized to the affected blocks only.
 *
 * @since 1.0.0
 */
@SPIVersion(major = 1, minor = 0)
public interface IFusionDetector {

    /**
     * 檢查剛放置的方塊是否觸發 RC 融合。
     *
     * 當 REBAR 或 CONCRETE 被放置時，掃描鄰近區域尋找互補的材料。
     * 如果找到，自動升級雙方為 RC_NODE 並計算融合材料參數。
     *
     * 流程 (Flow):
     *   1. 檢查放置的方塊是否為 REBAR 或 CONCRETE（其他類型無法觸發融合）
     *   2. 掃描 6 個方向，限定最大距離 (rcFusionRebarSpacingMax)
     *   3. 尋找互補的材料（REBAR ↔ CONCRETE）
     *   4. 若找到：
     *      a. 計算蜂窩機率（品質控制）
     *      b. 計算融合材料（加權公式）
     *      c. 升級雙方為 RC_NODE
     *   5. 遇到 RC_NODE 或距離超限時停止搜索（避免重複融合）
     *
     * @param level 伺服器世界 / Server world
     * @param pos 剛放置的方塊位置 / Position of the block just placed
     * @return 發生融合的數量 / Number of fusion reactions triggered (0 if no fusion occurred)
     */
    int checkAndFuse(@Nonnull ServerLevel level, @Nonnull BlockPos pos);

    /**
     * 當 REBAR、CONCRETE 或 RC_NODE 被破壞時，檢查相鄰的 RC_NODE 是否需要降級。
     *
     * RC_NODE 必須同時與 REBAR 和 CONCRETE 相鄰才能維持 RC 狀態。
     * 當其中一種互補材料失去時，RC_NODE 會根據剩餘的材料類型降級：
     *
     * 降級邏輯 (Downgrade Logic):
     *   - 如果周圍只剩 REBAR → 此 RC_NODE 降級為 CONCRETE（它原本是混凝土端）
     *   - 如果周圍只剩 CONCRETE → 此 RC_NODE 降級為 REBAR（它原本是鋼筋端）
     *   - 如果兩種都還有 → 重新計算融合材料（蜂窩機率新增）
     *   - 如果兩種都沒了 → 降級為 CONCRETE（安全預設）
     *
     * 降級恢復 (Recovery):
     *   ★ B-2 Fix: 降級時優先使用 preFusionMaterial（融合前保存的原始材料）以精確恢復。
     *           如果 preFusionMaterial 為 null，使用 DefaultMaterial 作為預設。
     *
     * @param level 伺服器世界 / Server world
     * @param brokenPos 被破壞的方塊位置 / Position of block being broken
     * @param brokenType 被破壞的方塊原始類型 / Original BlockType of the broken block
     * @return 降級的 RC_NODE 數量 / Number of RC_NODEs that were downgraded
     */
    int checkAndDowngrade(@Nonnull ServerLevel level, @Nonnull BlockPos brokenPos, @Nonnull BlockType brokenType);
}
