package com.blockreality.fastdesign.client.node.impl.physics.load;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;

/** C2-5: 風荷載 */
@OnlyIn(Dist.CLIENT)
public class WindLoadNode extends BRNode {
    private static final float AIR_DENSITY = 1.225f;

    public WindLoadNode() {
        super("Wind Load", "風荷載", "physics", NodeColor.PHYSICS);
        addInput("speed", "風速", PortType.FLOAT, 10f).range(0f, 100f);
        addInput("direction", "方向", PortType.VEC3, new float[]{1f, 0f, 0f});
        addInput("dragCoefficient", "阻力係數", PortType.FLOAT, 1.2f);
        addOutput("pressure", PortType.FLOAT);
        addOutput("force", PortType.FLOAT);
    }

    @Override
    public void evaluate() {
        float speed = getInput("speed").getFloat();
        float cd = getInput("dragCoefficient").getFloat();
        float pressure = 0.5f * AIR_DENSITY * speed * speed * cd;
        getOutput("pressure").setValue(pressure);
        getOutput("force").setValue(pressure);
    }

    @Override public String getTooltip() { return "風壓計算：0.5 × ρ × v² × Cd"; }
    @Override public String typeId() { return "physics.load.WindLoad"; }
}
