package com.blockreality.fastdesign.client.node.impl.render.preset;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;

/** A1-2: 效能目標 — 設計報告 §5 A1-2 */
@OnlyIn(Dist.CLIENT)
public class PerformanceTargetNode extends BRNode {
    public PerformanceTargetNode() {
        super("PerformanceTarget", "效能目標", "render", NodeColor.RENDER);
        addInput("targetFPS", "目標 FPS", PortType.INT, 60).range(30, 240);
        addInput("gpuBudgetMs", "GPU 預算 ms", PortType.FLOAT, 16.67f).range(4f, 33.33f);
        addOutput("budgetPerPass", PortType.FLOAT);
        addOutput("warningLevel", PortType.INT);
    }

    @Override
    public void evaluate() {
        float budget = getInput("gpuBudgetMs").getFloat();
        getOutput("budgetPerPass").setValue(budget / 17.0f); // ~17 passes average
        getOutput("warningLevel").setValue(budget < 8f ? 2 : budget < 12f ? 1 : 0);
    }

    @Override public String getTooltip() { return "設定目標幀率與 GPU 時間預算"; }
    @Override public String typeId() { return "render.preset.PerformanceTarget"; }
}
