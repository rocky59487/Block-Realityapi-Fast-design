package com.blockreality.fastdesign.client.node.impl.physics.result;

import com.blockreality.api.config.BRConfig;
import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraft.nbt.CompoundTag;

/**
 * C3-4: 利用率報告
 *
 * <p>當 {@code analysisResult} 輸入埠<b>未連線</b>時，
 * 自動切換到「引擎資源利用率」模式，從 {@link BRConfig} 讀取目前設定，
 * 計算各子系統的啟用比率作為利用率指標：
 *
 * <ul>
 *   <li>{@code maxUtilization} — VRAM 預算佔比（{@link BRConfig#getVramUsagePercent()} / 100）</li>
 *   <li>{@code avgUtilization} — 已啟用子系統數 / 全部可選子系統數（PFSF/PCG/流體/熱/風/EM/傾覆）</li>
 * </ul>
 *
 * <p>這提供「實際可調整遊戲參數」的即時回饋閉環，
 * 使玩家調整節點設定後能立即看到資源利用率變化。
 */
@OnlyIn(Dist.CLIENT)
public class UtilizationReportNode extends BRNode {

    public UtilizationReportNode() {
        super("Utilization Report", "利用率報告", "physics", NodeColor.PHYSICS);
        addInput("analysisResult", "分析結果", PortType.STRUCT, null);
        addOutput("reportData",       PortType.STRUCT);
        addOutput("maxUtilization",   PortType.FLOAT);
        addOutput("avgUtilization",   PortType.FLOAT);
    }

    @Override
    public void evaluate() {
        Object analysisIn = getInput("analysisResult").getRawValue();

        if (analysisIn instanceof CompoundTag tag && !tag.isEmpty()
                && tag.contains("maxUtilization")) {
            // ── 已連線：轉發上游分析結果 ──
            getOutput("reportData").setValue(tag);
            getOutput("maxUtilization").setValue(tag.getFloat("maxUtilization"));
            getOutput("avgUtilization").setValue(tag.getFloat("avgUtilization"));
        } else {
            // ── 未連線：計算引擎資源利用率 ──
            float maxUtil = computeMaxUtilization();
            float avgUtil = computeAvgUtilization();
            CompoundTag report = buildReportTag(maxUtil, avgUtil);
            getOutput("reportData").setValue(report);
            getOutput("maxUtilization").setValue(maxUtil);
            getOutput("avgUtilization").setValue(avgUtil);
        }
    }

    /**
     * 最大利用率 = VRAM 預算佔比（玩家配置的 VRAM 使用百分比）。
     * 值域 0.3–0.8，代表分配給物理緩衝區的 GPU 記憶體比例。
     */
    private float computeMaxUtilization() {
        return BRConfig.getVramUsagePercent() / 100.0f;
    }

    /**
     * 平均利用率 = 已啟用子系統數 / 可選子系統總數。
     * 7 個可選子系統：PFSF, PCG, 流體, 熱, 風, EM, 傾覆。
     */
    private float computeAvgUtilization() {
        int enabled = 0;
        final int total = 7;
        if (BRConfig.isPFSFEnabled())        enabled++;
        if (BRConfig.isPFSFPCGEnabled())     enabled++;
        if (BRConfig.isFluidEnabled())       enabled++;
        if (BRConfig.isThermalEnabled())     enabled++;
        if (BRConfig.isWindEnabled())        enabled++;
        if (BRConfig.isEmEnabled())          enabled++;
        if (BRConfig.isOverturningEnabled()) enabled++;
        return (float) enabled / total;
    }

    /** 建立完整報告 Tag，包含所有子系統狀態與效能預算。 */
    private CompoundTag buildReportTag(float maxUtil, float avgUtil) {
        CompoundTag tag = new CompoundTag();
        tag.putString("mode", "engine_utilization");
        tag.putFloat("maxUtilization", maxUtil);
        tag.putFloat("avgUtilization", avgUtil);

        // 子系統啟用狀態
        tag.putBoolean("pfsfEnabled",    BRConfig.isPFSFEnabled());
        tag.putBoolean("pcgEnabled",     BRConfig.isPFSFPCGEnabled());
        tag.putBoolean("fluidEnabled",   BRConfig.isFluidEnabled());
        tag.putBoolean("thermalEnabled", BRConfig.isThermalEnabled());
        tag.putBoolean("windEnabled",    BRConfig.isWindEnabled());
        tag.putBoolean("emEnabled",      BRConfig.isEmEnabled());

        // 效能預算
        tag.putInt("vramPercent",         BRConfig.getVramUsagePercent());
        tag.putInt("pfsfTickBudgetMs",    BRConfig.getPFSFTickBudgetMs());
        tag.putInt("fluidTickBudgetMs",   BRConfig.getFluidTickBudgetMs());
        tag.putInt("maxCollapsePerTick",  BRConfig.getMaxCollapsePerTick());

        // 求解器狀態
        tag.putInt("maxIterations",       BRConfig.getPFSFMaxIterations());
        tag.putFloat("omega",             (float) BRConfig.getPFSFOmega());

        return tag;
    }

    @Override public String getTooltip() { return "構件利用率統計報告。未連線時顯示物理引擎資源利用率。"; }
    @Override public String typeId()     { return "physics.result.UtilizationReport"; }
}
