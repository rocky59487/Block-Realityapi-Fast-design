package com.blockreality.fastdesign.client.node.impl.physics.load;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraft.nbt.CompoundTag;

/** C2-7: 熱膨脹荷載 */
@OnlyIn(Dist.CLIENT)
public class ThermalLoadNode extends BRNode {
    public ThermalLoadNode() {
        super("Thermal Load", "熱膨脹荷載", "physics", NodeColor.PHYSICS);
        addInput("tempDelta", "溫差", PortType.FLOAT, 30f).range(-100f, 200f);
        addInput("thermalExpCoeff", "熱膨脹係數", PortType.FLOAT, 0.000012f);
        addOutput("strain", PortType.FLOAT);
        addOutput("thermalSpec", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        float dt = getInput("tempDelta").getFloat();
        float alpha = getInput("thermalExpCoeff").getFloat();
        float strain = alpha * dt;
        getOutput("strain").setValue(strain);
        CompoundTag spec = new CompoundTag();
        spec.putFloat("tempDelta", dt);
        spec.putFloat("thermalExpCoeff", alpha);
        spec.putFloat("strain", strain);
        getOutput("thermalSpec").setValue(spec);
    }

    @Override public String getTooltip() { return "熱膨脹應變計算：α × ΔT"; }
    @Override public String typeId() { return "physics.load.ThermalLoad"; }
}
