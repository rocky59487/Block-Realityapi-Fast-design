package com.blockreality.fastdesign.client.node.impl.physics.collapse;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraft.nbt.CompoundTag;

/** C4-3: 纜索約束 */
@OnlyIn(Dist.CLIENT)
public class CableConstraintNode extends BRNode {
    public CableConstraintNode() {
        super("Cable Constraint", "纜索約束", "physics", NodeColor.PHYSICS);
        addInput("xpbdIterations", "XPBD 迭代", PortType.INT, 10).range(1, 50);
        addInput("compliance", "柔度", PortType.FLOAT, 0.001f).range(0f, 0.1f);
        addInput("damping", "阻尼", PortType.FLOAT, 0.1f).range(0f, 1f);
        addOutput("cableSpec", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        CompoundTag spec = new CompoundTag();
        spec.putInt("xpbdIterations", getInput("xpbdIterations").getInt());
        spec.putFloat("compliance", getInput("compliance").getFloat());
        spec.putFloat("damping", getInput("damping").getFloat());
        getOutput("cableSpec").setValue(spec);
    }

    @Override public String getTooltip() { return "XPBD 纜索約束求解器參數"; }
    @Override public String typeId() { return "physics.collapse.CableConstraint"; }
}
