package com.blockreality.fastdesign.client.node.impl.physics.solver;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraft.nbt.CompoundTag;

/** C1-4: 粗粒度 FEM */
@OnlyIn(Dist.CLIENT)
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
        // ─── Inspector 屬性 ───
        registerProperty("maxIterations",       "SOR 最大迭代次數（10–200）");
        registerProperty("convergenceThreshold","殘差收斂閾值（越小越精確，越慢）");
        registerProperty("omega",               "SOR 鬆弛因子 ω（1.0–1.9）");
        registerProperty("lateralFraction",     "側向荷載占重力比例（風/地震等）");
        registerProperty("interval",            "粗略 FEM 更新間隔（ticks，20=每秒）");
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
