package com.blockreality.fastdesign.client.node.impl.physics.solver;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraft.nbt.CompoundTag;

/** C1-2: 梁分析 */
@OnlyIn(Dist.CLIENT)
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
        // ─── Inspector 屬性 ───
        registerProperty("enabled",      "啟用梁挫屈分析（Euler 梁理論）");
        registerProperty("maxBlocks",    "一次分析最多掃描的方塊數，影響 BFS 覆蓋範圍");
        registerProperty("gravity",      "重力加速度 g（m/s²），標準 9.81，月球 1.62");
        registerProperty("asyncTimeout", "非同步分析逾時閾值（ms），超時視為分析失敗");
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
