package com.blockreality.fastdesign.client.node.impl.physics.result;

import com.blockreality.fastdesign.client.node.*;
import net.minecraft.nbt.CompoundTag;

/** C3-4: 利用率報告 */
public class UtilizationReportNode extends BRNode {
    public UtilizationReportNode() {
        super("Utilization Report", "利用率報告", "physics", NodeColor.PHYSICS);
        addInput("analysisResult", "分析結果", PortType.STRUCT, null);
        addOutput("reportData", PortType.STRUCT);
        addOutput("maxUtilization", PortType.FLOAT);
        addOutput("avgUtilization", PortType.FLOAT);
    }

    @Override
    public void evaluate() {
        CompoundTag report = new CompoundTag();
        report.putFloat("maxUtilization", 0f);
        report.putFloat("avgUtilization", 0f);
        getOutput("reportData").setValue(report);
        getOutput("maxUtilization").setValue(0.0f);
        getOutput("avgUtilization").setValue(0.0f);
    }

    @Override public String getTooltip() { return "構件利用率統計報告"; }
    @Override public String typeId() { return "physics.result.UtilizationReport"; }
}
