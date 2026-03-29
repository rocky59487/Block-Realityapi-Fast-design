package com.blockreality.fastdesign.client.node.impl.render.pipeline;

import com.blockreality.fastdesign.client.node.*;

/** A2-6: VRAM 預算管理 */
public class VRAMBudgetNode extends BRNode {
    public VRAMBudgetNode() {
        super("VRAMBudget", "VRAM 預算", "render", NodeColor.RENDER);
        addInput("budgetMB", "預算 MB", PortType.INT, 512).range(128, 2048);
        addInput("evictionPolicy", "淘汰策略", PortType.ENUM, "LRU");
        addOutput("usedMB", PortType.FLOAT);
        addOutput("freeMB", PortType.FLOAT);
        addOutput("utilizationPercent", PortType.FLOAT);
        addOutput("warningLevel", PortType.INT);
    }

    @Override
    public void evaluate() {
        int budget = getInput("budgetMB").getInt();
        // 即時 VRAM 用量由 GPU profiler 提供，此處模擬
        float used = budget * 0.6f;
        getOutput("usedMB").setValue(used);
        getOutput("freeMB").setValue(budget - used);
        getOutput("utilizationPercent").setValue(used / budget * 100);
        getOutput("warningLevel").setValue(used / budget > 0.9f ? 2 : used / budget > 0.75f ? 1 : 0);
    }

    @Override public String getTooltip() { return "VRAM 預算與用量監控"; }
    @Override public String typeId() { return "render.pipeline.VRAMBudget"; }
}
