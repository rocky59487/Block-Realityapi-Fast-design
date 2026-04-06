package com.blockreality.fastdesign.client.node.impl.physics.load;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraft.nbt.CompoundTag;

/** C2-3: 集中荷載 */
@OnlyIn(Dist.CLIENT)
public class ConcentratedLoadNode extends BRNode {
    public ConcentratedLoadNode() {
        super("Concentrated Load", "集中荷載", "physics", NodeColor.PHYSICS);
        addInput("force", "力", PortType.FLOAT, 1000f).range(0f, 1000000f);
        addInput("position", "位置", PortType.VEC3, new float[]{0f, 0f, 0f});
        addInput("direction", "方向", PortType.VEC3, new float[]{0f, -1f, 0f});
        addOutput("loadSpec", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        CompoundTag spec = new CompoundTag();
        spec.putFloat("force", getInput("force").getFloat());
        float[] pos = getInput("position").getVec3();
        spec.putFloat("posX", pos[0]);
        spec.putFloat("posY", pos[1]);
        spec.putFloat("posZ", pos[2]);
        float[] dir = getInput("direction").getVec3();
        spec.putFloat("dirX", dir[0]);
        spec.putFloat("dirY", dir[1]);
        spec.putFloat("dirZ", dir[2]);
        getOutput("loadSpec").setValue(spec);
    }

    @Override public String getTooltip() { return "單點集中荷載定義"; }
    @Override public String typeId() { return "physics.load.ConcentratedLoad"; }
}
