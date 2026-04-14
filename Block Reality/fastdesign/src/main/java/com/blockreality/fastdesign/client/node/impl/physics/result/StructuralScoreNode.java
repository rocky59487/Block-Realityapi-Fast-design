package com.blockreality.fastdesign.client.node.impl.physics.result;

import com.blockreality.api.config.BRConfig;
import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraft.nbt.CompoundTag;

/**
 * C3-5: 結構健康度評分
 *
 * <p>當 {@code analysisResult} 輸入埠<b>未連線</b>時，
 * 自動切換到「物理引擎配置健康度」模式，依目前 {@link BRConfig} 設定
 * 計算 0–100 分的配置完整度評分，並給出 A–F 等級。
 *
 * <p>計分細則（配置健康度模式）：
 * <ul>
 *   <li>PFSF GPU 引擎啟用：+25 分</li>
 *   <li>Hybrid RBGS+PCG 求解器啟用：+15 分</li>
 *   <li>傾覆穩定性物理啟用：+15 分</li>
 *   <li>連鎖崩塌啟用：+10 分</li>
 *   <li>每 tick 崩塌上限合理（≥200）：+20 分</li>
 *   <li>每 tick island 計算上限合理（≥8）：+15 分</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public class StructuralScoreNode extends BRNode {

    public StructuralScoreNode() {
        super("Structural Score", "結構健康度", "physics", NodeColor.PHYSICS);
        addInput("analysisResult", "分析結果", PortType.STRUCT, null);
        addOutput("score",   PortType.INT);
        addOutput("grade",   PortType.ENUM);
        addOutput("details", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        Object analysisIn = getInput("analysisResult").getRawValue();

        if (analysisIn instanceof CompoundTag tag && !tag.isEmpty()) {
            // ── 已連線：直接從輸入讀取分析結果 ──
            int  score = tag.contains("score") ? tag.getInt("score") : 100;
            String grade = tag.contains("grade") ? tag.getString("grade") : scoreToGrade(score);
            getOutput("score").setValue(score);
            getOutput("grade").setValue(grade);
            getOutput("details").setValue(tag);
        } else {
            // ── 未連線：計算配置健康度分數 ──
            int score = computeConfigHealthScore();
            String grade = scoreToGrade(score);
            CompoundTag details = buildConfigDetailsTag(score);
            getOutput("score").setValue(score);
            getOutput("grade").setValue(grade);
            getOutput("details").setValue(details);
        }
    }

    /** 依 BRConfig 目前設定計算 0–100 配置健康度分數。 */
    private int computeConfigHealthScore() {
        int score = 0;

        // PFSF GPU 引擎啟用（核心求解器）
        if (BRConfig.isPFSFEnabled())        score += 25;
        // Hybrid RBGS+PCG 求解器（收斂速度提升 ~50%）
        if (BRConfig.isPFSFPCGEnabled())     score += 15;
        // 傾覆穩定性物理（重心超出支撐多邊形 → 傾倒）
        if (BRConfig.isOverturningEnabled()) score += 15;
        // 連鎖崩塌（串聯破壞傳播）
        if (BRConfig.isCascadeEnabled())     score += 10;
        // 崩塌吞吐量合理（每 tick ≥ 200 個方塊）
        if (BRConfig.getMaxCollapsePerTick() >= 200) score += 20;
        // Island 計算吞吐量合理（每 tick ≥ 8 個 island）
        if (BRConfig.getMaxIslandsPerTick()  >= 8)   score += 15;

        return score;
    }

    /** 建立配置詳細資訊 CompoundTag（供下游節點或除錯使用）。 */
    private CompoundTag buildConfigDetailsTag(int score) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("score", score);
        tag.putString("grade", scoreToGrade(score));
        tag.putString("mode", "config_health");

        // 物理引擎狀態
        tag.putBoolean("pfsfEnabled",         BRConfig.isPFSFEnabled());
        tag.putBoolean("pcgEnabled",          BRConfig.isPFSFPCGEnabled());
        tag.putBoolean("overturningEnabled",  BRConfig.isOverturningEnabled());
        tag.putBoolean("cascadeEnabled",      BRConfig.isCascadeEnabled());

        // 求解器精細參數
        tag.putInt("maxIterations",           BRConfig.getPFSFMaxIterations());
        tag.putFloat("omega",                 (float) BRConfig.getPFSFOmega());
        tag.putFloat("convergenceThreshold",  (float) BRConfig.getPFSFConvergenceThreshold());

        // 效能預算
        tag.putInt("maxCollapsePerTick",      BRConfig.getMaxCollapsePerTick());
        tag.putInt("maxIslandsPerTick",       BRConfig.getMaxIslandsPerTick());
        tag.putInt("pfsfTickBudgetMs",        BRConfig.getPFSFTickBudgetMs());

        return tag;
    }

    private static String scoreToGrade(int score) {
        if (score >= 90) return "A";
        if (score >= 75) return "B";
        if (score >= 60) return "C";
        if (score >= 45) return "D";
        return "F";
    }

    @Override public String getTooltip() { return "綜合結構健康度評分 (A–F)。未連線時顯示物理引擎配置完整度。"; }
    @Override public String typeId()     { return "physics.result.StructuralScore"; }
}
