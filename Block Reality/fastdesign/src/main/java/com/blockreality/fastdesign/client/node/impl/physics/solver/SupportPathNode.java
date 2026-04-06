package com.blockreality.fastdesign.client.node.impl.physics.solver;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraft.nbt.CompoundTag;

/** C1-3: 支撐路徑 */
@OnlyIn(Dist.CLIENT)
public class SupportPathNode extends BRNode {
    public SupportPathNode() {
        super("Support Path", "支撐路徑", "physics", NodeColor.PHYSICS);
        addInput("bfsMaxBlocks", "BFS 最大方塊", PortType.INT, 500000).range(64, 72000000);
        addInput("bfsMaxMs", "BFS 最大毫秒", PortType.INT, 200).range(5, 2000);
        addInput("gravity", "重力加速度", PortType.FLOAT, 9.81f);
        addInput("blockSectionModulus", "截面模數", PortType.FLOAT, 0.1667f);
        addOutput("analysisSpec", PortType.STRUCT);
        addOutput("stableCount", PortType.INT);
        addOutput("failedCount", PortType.INT);
        addOutput("maxMoment", PortType.FLOAT);
    }

    @Override
    public void evaluate() {
        CompoundTag spec = new CompoundTag();
        spec.putInt("bfsMaxBlocks", getInput("bfsMaxBlocks").getInt());
        spec.putInt("bfsMaxMs", getInput("bfsMaxMs").getInt());
        spec.putFloat("gravity", getInput("gravity").getFloat());
        spec.putFloat("blockSectionModulus", getInput("blockSectionModulus").getFloat());
        getOutput("analysisSpec").setValue(spec);
        getOutput("stableCount").setValue(0);
        getOutput("failedCount").setValue(0);
        getOutput("maxMoment").setValue(0.0f);
    }

    @Override public String getTooltip() { return "BFS 支撐路徑搜尋與力矩分析"; }
    @Override public String typeId() { return "physics.solver.SupportPath"; }
}
