package com.blockreality.fastdesign.client.node.impl.physics.load;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;

/** C2-1: 重力 */
@OnlyIn(Dist.CLIENT)
public class GravityNode extends BRNode {
    public GravityNode() {
        super("Gravity", "重力", "physics", NodeColor.PHYSICS);
        addInput("g", "重力加速度", PortType.FLOAT, 9.81f).range(0f, 20f);
        addInput("direction", "方向", PortType.VEC3, new float[]{0f, -1f, 0f});
        addOutput("gravityVec", PortType.VEC3);
        addOutput("gravityMagnitude", PortType.FLOAT);
    }

    @Override
    public void evaluate() {
        float g = getInput("g").getFloat();
        float[] dir = getInput("direction").getVec3();
        getOutput("gravityVec").setValue(new float[]{dir[0] * g, dir[1] * g, dir[2] * g});
        getOutput("gravityMagnitude").setValue(g);
    }

    @Override public String getTooltip() { return "重力向量計算 (g × direction)"; }
    @Override public String typeId() { return "physics.load.Gravity"; }
}
