package com.blockreality.fastdesign.client.node.impl.physics.load;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraft.nbt.CompoundTag;

/** C2-6: 地震荷載 */
@OnlyIn(Dist.CLIENT)
public class SeismicLoadNode extends BRNode {
    public SeismicLoadNode() {
        super("Seismic Load", "地震荷載", "physics", NodeColor.PHYSICS);
        addInput("magnitude", "震級", PortType.FLOAT, 5f).range(0f, 10f);
        addInput("pga", "峰值地面加速度", PortType.FLOAT, 0.2f).range(0f, 1f);
        addOutput("seismicSpec", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        CompoundTag spec = new CompoundTag();
        spec.putFloat("magnitude", getInput("magnitude").getFloat());
        spec.putFloat("pga", getInput("pga").getFloat());
        getOutput("seismicSpec").setValue(spec);
    }

    @Override public String getTooltip() { return "地震荷載參數定義"; }
    @Override public String typeId() { return "physics.load.SeismicLoad"; }
}
