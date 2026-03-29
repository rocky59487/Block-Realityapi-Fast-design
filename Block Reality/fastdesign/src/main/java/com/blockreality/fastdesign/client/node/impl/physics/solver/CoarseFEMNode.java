package com.blockreality.fastdesign.client.node.impl.physics.solver;

import com.blockreality.fastdesign.client.node.*;
import net.minecraft.nbt.CompoundTag;

/** C1-4: 粗粒度 FEM */
public class CoarseFEMNode extends BRNode {
    public CoarseFEMNode() {
        super("Coarse FEM", "粗粒度 FEM", "physics", NodeColor.PHYSICS);
        addInput("maxIterations", "最大迭代", PortType.INT, 50).range(10, 200);
        addInput("convergenceThreshold", "收斂閾值", PortType.FLOAT, 0.005f).range(0.001f, 0.1f);
        addInput("omega", "鬆弛因子", PortType.FLOAT, 1.4f).range(1.0f, 1.9f);
        addInput("lateralFraction", "側向分量", PortType.FLOAT, 0.15f).range(0f, 0.5f);
        addInput("interval", "更新間隔", PortType.INT, 20).range(5, 200);
        addOutput("femSpec", PortType.STRUCT);
        addOutput("sectionsAnalyzed", PortType.INT);
        addOutput("avgStress", PortType.FLOAT);
    }

    @Override
    public void evaluate() {
        CompoundTag spec = new CompoundTag();
        spec.putInt("maxIterations", getInput("maxIterations").getInt());
        spec.putFloat("convergenceThreshold", getInput("convergenceThreshold").getFloat());
        spec.putFloat("omega", getInput("omega").getFloat());
        spec.putFloat("lateralFraction", getInput("lateralFraction").getFloat());
        spec.putInt("interval", getInput("interval").getInt());
        getOutput("femSpec").setValue(spec);
        getOutput("sectionsAnalyzed").setValue(0);
        getOutput("avgStress").setValue(0.0f);
    }

    @Override public String getTooltip() { return "粗粒度有限元素法求解器配置"; }
    @Override public String typeId() { return "physics.solver.CoarseFEM"; }
}
