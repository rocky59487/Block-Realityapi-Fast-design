package com.blockreality.fastdesign.client.node.impl.physics.load;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraft.nbt.CompoundTag;

/** C2-4: 力矩計算 */
@OnlyIn(Dist.CLIENT)
public class MomentCalculatorNode extends BRNode {
    public MomentCalculatorNode() {
        super("Moment Calculator", "力矩計算", "physics", NodeColor.PHYSICS);
        addInput("loads", "荷載", PortType.STRUCT, null);
        addInput("pivotPoint", "支點", PortType.VEC3, new float[]{0f, 0f, 0f});
        addOutput("moment", PortType.FLOAT);
        addOutput("momentVector", PortType.VEC3);
    }

    @Override
    public void evaluate() {
        CompoundTag loads = getInput("loads").getValue();
        float[] pivot = getInput("pivotPoint").getVec3();
        float momentMag = 0f;
        float[] momentVec = new float[]{0f, 0f, 0f};
        if (loads != null) {
            float fx = loads.getFloat("dirX") * loads.getFloat("force");
            float fy = loads.getFloat("dirY") * loads.getFloat("force");
            float fz = loads.getFloat("dirZ") * loads.getFloat("force");
            float rx = loads.getFloat("posX") - pivot[0];
            float ry = loads.getFloat("posY") - pivot[1];
            float rz = loads.getFloat("posZ") - pivot[2];
            momentVec[0] = ry * fz - rz * fy;
            momentVec[1] = rz * fx - rx * fz;
            momentVec[2] = rx * fy - ry * fx;
            momentMag = (float) Math.sqrt(momentVec[0] * momentVec[0] + momentVec[1] * momentVec[1] + momentVec[2] * momentVec[2]);
        }
        getOutput("moment").setValue(momentMag);
        getOutput("momentVector").setValue(momentVec);
    }

    @Override public String getTooltip() { return "計算荷載對支點的力矩 (r × F)"; }
    @Override public String typeId() { return "physics.load.MomentCalculator"; }
}
