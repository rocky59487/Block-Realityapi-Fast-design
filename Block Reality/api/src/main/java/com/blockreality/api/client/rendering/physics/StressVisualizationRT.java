package com.blockreality.api.client.rendering.physics;

import com.blockreality.api.client.rendering.vulkan.VkContext;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RT 物理應力熱圖視覺化。
 *
 * 將 Block Reality 物理引擎的應力分析結果透過 RT 渲染為熱圖：
 *   - 低應力 → 藍色
 *   - 中應力 → 綠色/黃色
 *   - 高應力（接近降伏） → 紅色
 *   - 超過降伏 → 白色閃爍
 *
 * 應力資料來源：
 *   - {@link com.blockreality.api.physics.ForceEquilibriumSolver} 節點力
 *   - {@link com.blockreality.api.physics.BeamStressEngine} 梁應力
 *
 * 渲染方式：
 *   - Tier 0-2: GL shader overlay（已有實作）
 *   - Tier 3: RT closesthit shader 中直接著色（本類別管理）
 *
 * @see VkContext
 */
@OnlyIn(Dist.CLIENT)
public class StressVisualizationRT {

    private static final Logger LOG = LoggerFactory.getLogger("BR-StressVizRT");

    /** 應力色彩映射（uniform buffer 資料） */
    private static final float[][] STRESS_COLOR_MAP = {
            {0.0f, 0.0f, 1.0f},   // 0% — 藍
            {0.0f, 1.0f, 0.0f},   // 33% — 綠
            {1.0f, 1.0f, 0.0f},   // 66% — 黃
            {1.0f, 0.0f, 0.0f},   // 100% — 紅
    };

    private final VkContext context;

    /** 每方塊應力比率（0.0–1.0），上傳到 GPU storage buffer */
    private float[] stressRatios;
    private long stressBuffer = 0;   // VkBuffer handle
    private long stressAllocation = 0;

    private boolean enabled = false;

    public StressVisualizationRT(VkContext context) {
        this.context = context;
    }

    /**
     * 更新應力資料（每 tick 從物理引擎拉取）。
     *
     * @param blockPositions 方塊位置陣列 [x, y, z, ...]
     * @param stressValues   對應的應力比率（0.0 = 無應力, 1.0 = 降伏）
     */
    public void updateStressData(int[] blockPositions, float[] stressValues) {
        // TODO Phase 4: 上傳應力資料到 GPU storage buffer
        this.stressRatios = stressValues;
    }

    /**
     * 啟用/停用應力熱圖。
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        LOG.info("Stress visualization RT: {}", enabled ? "enabled" : "disabled");
    }

    public boolean isEnabled() { return enabled; }

    /**
     * 釋放 GPU 資源。
     */
    public void cleanup() {
        stressBuffer = 0;
        stressAllocation = 0;
        stressRatios = null;
        LOG.info("StressVisualizationRT cleanup complete");
    }
}
