package com.blockreality.api.physics.pfsf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 動態計算範圍策略 — 根據 VRAM 壓力決定 island 處理策略。
 *
 * <h2>設計動機</h2>
 * VRAM 接近滿載時，需要優雅降級而非直接拒絕分配：
 * <ul>
 *   <li>壓力 &lt; 50%：全量處理（L0 full resolution）</li>
 *   <li>壓力 50-70%：減少迭代步數</li>
 *   <li>壓力 70-85%：僅分配粗網格（L1 coarse only）</li>
 *   <li>壓力 &gt; 85%：拒絕新 island</li>
 * </ul>
 *
 * <h2>使用位置</h2>
 * <ol>
 *   <li>{@code PFSFBufferManager.getOrCreateBuffer()} — 決定是否分配 + 分配精度</li>
 *   <li>{@code PFSFEngineInstance.onServerTick()} — 動態調整迭代步數</li>
 * </ol>
 */
public final class ComputeRangePolicy {

    private static final Logger LOGGER = LoggerFactory.getLogger("PFSF-Range");

    /** 計算配置結果 */
    public static final class Config {
        /** 網格層級：L0 = 全解析度，L1 = 粗網格 */
        public final GridLevel gridLevel;
        /** 建議迭代步數倍率 (0.0 ~ 1.0)，1.0 = 全量 */
        public final float stepMultiplier;
        /** 是否分配相場 buffer */
        public final boolean allocatePhaseField;
        /** 是否分配多重網格 buffer */
        public final boolean allocateMultigrid;

        Config(GridLevel gridLevel, float stepMultiplier,
               boolean allocatePhaseField, boolean allocateMultigrid) {
            this.gridLevel = gridLevel;
            this.stepMultiplier = stepMultiplier;
            this.allocatePhaseField = allocatePhaseField;
            this.allocateMultigrid = allocateMultigrid;
        }
    }

    public enum GridLevel {
        /** 全解析度 */
        L0_FULL,
        /** 僅粗網格（半維度） */
        L1_COARSE
    }

    // ─── 壓力閾值 ───
    private static final float PRESSURE_LOW       = 0.50f;
    private static final float PRESSURE_MEDIUM    = 0.70f;
    private static final float PRESSURE_HIGH      = 0.85f;

    private ComputeRangePolicy() {}

    /**
     * 根據當前 VRAM 壓力決定 island 計算策略。
     *
     * @param vramMgr VRAM 預算管理器
     * @param islandVoxelCount 預估 island 體素數
     * @return 計算配置，或 null 表示拒絕此 island
     */
    public static Config decide(VramBudgetManager vramMgr, int islandVoxelCount) {
        float pressure = vramMgr.getPressure();

        if (pressure < PRESSURE_LOW) {
            // 低壓力：全量處理
            return new Config(GridLevel.L0_FULL, 1.0f, true, true);
        }

        if (pressure < PRESSURE_MEDIUM) {
            // 中低壓力：全解析度但減少迭代
            float mult = 1.0f - (pressure - PRESSURE_LOW) / (PRESSURE_MEDIUM - PRESSURE_LOW) * 0.5f;
            return new Config(GridLevel.L0_FULL, mult, true, true);
        }

        if (pressure < PRESSURE_HIGH) {
            // 中高壓力：粗網格 + 無相場 + 減少迭代
            float mult = 0.5f - (pressure - PRESSURE_MEDIUM) / (PRESSURE_HIGH - PRESSURE_MEDIUM) * 0.3f;
            return new Config(GridLevel.L1_COARSE, mult, false, false);
        }

        // 超高壓力：拒絕
        LOGGER.warn("[PFSF] VRAM pressure {:.1f}% — rejecting island ({} voxels)",
                pressure * 100, islandVoxelCount);
        return null;
    }

    /**
     * 根據 VRAM 壓力調整建議步數。
     *
     * @param baseSteps 基礎步數（PFSFScheduler 建議的）
     * @param vramMgr   VRAM 預算管理器
     * @return 調整後的步數（至少 1）
     */
    public static int adjustSteps(int baseSteps, VramBudgetManager vramMgr) {
        float pressure = vramMgr.getPressure();

        if (pressure < PRESSURE_LOW) return baseSteps;
        if (pressure < PRESSURE_MEDIUM) {
            float mult = 1.0f - (pressure - PRESSURE_LOW) / (PRESSURE_MEDIUM - PRESSURE_LOW) * 0.5f;
            return Math.max(1, (int) (baseSteps * mult));
        }
        if (pressure < PRESSURE_HIGH) {
            return Math.max(1, baseSteps / 3);
        }
        return 1;
    }
}
