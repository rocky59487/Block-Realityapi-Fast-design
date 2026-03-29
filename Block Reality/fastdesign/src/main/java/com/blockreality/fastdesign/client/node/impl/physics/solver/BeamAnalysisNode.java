package com.blockreality.fastdesign.client.node.impl.physics.solver;

import com.blockreality.fastdesign.client.node.*;
import net.minecraft.nbt.CompoundTag;

/** C1-2: 梁分析 */
public class BeamAnalysisNode extends BRNode {
    public BeamAnalysisNode() {
        super("Beam Analysis", "梁分析", "physics", NodeColor.PHYSICS);
        addInput("enabled", "啟用", PortType.BOOL, true);
        addInput("maxBlocks", "最大方塊數", PortType.INT, 500).range(64, 5000);
        addInput("gravity", "重力加速度", PortType.FLOAT, 9.81f);
        addInput("asyncTimeout", "非同步超時", PortType.INT, 5000).range(1000, 30000);
        addOutput("analysisSpec", PortType.STRUCT);
        addOutput("beamsAnalyzed", PortType.INT);
        addOutput("failedBeams", PortType.INT);
        addOutput("maxUtilization", PortType.FLOAT);
    }

    @Override
    public void evaluate() {
        CompoundTag spec = new CompoundTag();
        spec.putBoolean("enabled", getInput("enabled").getBool());
        spec.putInt("maxBlocks", getInput("maxBlocks").getInt());
        spec.putFloat("gravity", getInput("gravity").getFloat());
        spec.putInt("asyncTimeout", getInput("asyncTimeout").getInt());
        getOutput("analysisSpec").setValue(spec);
        getOutput("beamsAnalyzed").setValue(0);
        getOutput("failedBeams").setValue(0);
        getOutput("maxUtilization").setValue(0.0f);
    }

    @Override public String getTooltip() { return "Euler 挫屈梁分析引擎配置"; }
    @Override public String typeId() { return "physics.solver.BeamAnalysis"; }
}
