package com.blockreality.fastdesign.client.node.impl.physics.solver;

import com.blockreality.fastdesign.client.node.*;
import net.minecraft.nbt.CompoundTag;

/** C1-1: 力平衡求解器 */
public class ForceEquilibriumNode extends BRNode {
    public ForceEquilibriumNode() {
        super("Force Equilibrium", "力平衡求解器", "physics", NodeColor.PHYSICS);
        addInput("enabled", "啟用", PortType.BOOL, true);
        addInput("maxIterations", "最大迭代", PortType.INT, 100).range(10, 500);
        addInput("convergenceThreshold", "收斂閾值", PortType.FLOAT, 0.001f).range(0.0001f, 0.1f);
        addInput("omega", "鬆弛因子", PortType.FLOAT, 1.25f).range(1.0f, 1.95f);
        addInput("autoOmega", "自動鬆弛", PortType.BOOL, true);
        addInput("warmStartEntries", "暖啟動項", PortType.INT, 64).range(0, 256);
        addOutput("solverSpec", PortType.STRUCT);
        addOutput("convergenceRate", PortType.FLOAT);
        addOutput("iterationsUsed", PortType.INT);
        addOutput("residual", PortType.FLOAT);
    }

    @Override
    public void evaluate() {
        CompoundTag spec = new CompoundTag();
        spec.putBoolean("enabled", getInput("enabled").getBool());
        spec.putInt("maxIterations", getInput("maxIterations").getInt());
        spec.putFloat("convergenceThreshold", getInput("convergenceThreshold").getFloat());
        spec.putFloat("omega", getInput("omega").getFloat());
        spec.putBoolean("autoOmega", getInput("autoOmega").getBool());
        spec.putInt("warmStartEntries", getInput("warmStartEntries").getInt());
        getOutput("solverSpec").setValue(spec);
        getOutput("convergenceRate").setValue(0.0f);
        getOutput("iterationsUsed").setValue(0);
        getOutput("residual").setValue(0.0f);
    }

    @Override public String getTooltip() { return "SOR 力平衡迭代求解器配置"; }
    @Override public String typeId() { return "physics.solver.ForceEquilibrium"; }
}
