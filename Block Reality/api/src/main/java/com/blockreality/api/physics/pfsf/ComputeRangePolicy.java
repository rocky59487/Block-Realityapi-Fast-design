package com.blockreality.api.physics.pfsf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 動態計算範圍策略 — 根據 VRAM 壓力自動降級計算精度。
 *
 * <p>四級策略：
 * <ol>
 *   <li>充裕（pressure < 0.6）：全精度 L0 + W-Cycle + 相場</li>
 *   <li>緊張（pressure < 0.85）：L0 全精度但跳過相場分配</li>
 *   <li>吃緊（pressure < 0.95）：L1 粗網格（2x downsampled）+ 半步</li>
 *   <li>危急（pressure ≥ 0.95）：拒絕分配，island 進入 DORMANT</li>
 * </ol>
 */
public final class ComputeRangePolicy {

    private static final Logger LOGGER = LoggerFactory.getLogger("PFSF-ComputeRange");

    /** 每體素完整分配的估計大小 (bytes) */
    private static final long BYTES_PER_VOXEL_FULL = 62L;

    private ComputeRangePolicy() {}

    /** 網格解析度等級 */
    public enum GridLevel {
        /** 全精度（原始尺寸） */
        L0_FULL,
        /** 粗網格（每維度 2x 下採樣，VRAM 為 L0 的 1/8） */
        L1_COARSE,
        /** 極粗網格（每維度 4x 下採樣） */
        L2_VERY_COARSE
    }

    /** 計算配置 */
    public record ComputeConfig(
            GridLevel gridLevel,
            float stepsMultiplier,
            boolean allocatePhaseField,
            boolean allocateMultigrid
    ) {}

    /**
     * 根據 VRAM 壓力和 island 大小決定計算配置。
     *
     * @param budgetMgr    VRAM 預算管理器
     * @param islandVoxels island 體素數量 (Lx × Ly × Lz)
     * @return 計算配置，或 null 表示應拒絕（DORMANT）
     */
    public static ComputeConfig decide(VramBudgetManager budgetMgr, int islandVoxels) {
        float pressure = budgetMgr.getPressure();
        long freeBytes = budgetMgr.getFreeMemory();
        long needed = islandVoxels * BYTES_PER_VOXEL_FULL;

        if (pressure < 0.6f && freeBytes > needed * 3) {
            // 充裕：全精度 + W-Cycle + 相場
            return new ComputeConfig(GridLevel.L0_FULL, 1.0f, true, true);
        }

        if (pressure < 0.85f && freeBytes > needed) {
            // 緊張：全精度但跳過相場
            return new ComputeConfig(GridLevel.L0_FULL, 1.0f, false, true);
        }

        if (pressure < 0.95f && freeBytes > needed / 4) {
            // 吃緊：L1 粗網格 + 半步
            LOGGER.info("[ComputeRange] VRAM tight (pressure={}, free={}MB), " +
                            "downgrading island ({} voxels) to L1_COARSE",
                    pressure, freeBytes / (1024 * 1024), islandVoxels);
            return new ComputeConfig(GridLevel.L1_COARSE, 0.5f, false, false);
        }

        // 危急：拒絕
        LOGGER.warn("[ComputeRange] VRAM critical (pressure={}, free={}MB), " +
                        "rejecting island ({} voxels)",
                pressure, freeBytes / (1024 * 1024), islandVoxels);
        return null;
    }

    /**
     * 套用步數乘數到推薦步數。
     *
     * @param baseSteps  原始推薦步數
     * @param config     計算配置
     * @return 調整後的步數（最少 1）
     */
    public static int adjustSteps(int baseSteps, ComputeConfig config) {
        if (config == null) return 0;
        return Math.max(1, (int) (baseSteps * config.stepsMultiplier()));
    }
}
