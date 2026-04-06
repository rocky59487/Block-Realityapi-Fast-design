package com.blockreality.fastdesign.client.node.impl.render.lighting;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;

/** A4-4: 點光源 */
@OnlyIn(Dist.CLIENT)
public class PointLightNode extends BRNode {
    public PointLightNode() {
        super("PointLight", "點光源", "render", NodeColor.RENDER);
        addInput("color", "顏色", PortType.COLOR, 0xFFFFAA44);
        addInput("intensity", "強度", PortType.FLOAT, 1.0f).range(0f, 10f);
        addInput("radius", "半徑", PortType.FLOAT, 8f).range(1f, 32f);
        addOutput("lightSpec", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        // lightSpec populated by downstream binder
    }

    @Override public String getTooltip() { return "點光源顏色、強度與衰減半徑"; }
    @Override public String typeId() { return "render.lighting.PointLight"; }
}
