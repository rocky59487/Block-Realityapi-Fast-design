package com.blockreality.fastdesign.client.node.impl.render.lighting;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;

/** A4-5: 面光源 */
@OnlyIn(Dist.CLIENT)
public class AreaLightNode extends BRNode {
    public AreaLightNode() {
        super("AreaLight", "面光源", "render", NodeColor.RENDER);
        addInput("color", "顏色", PortType.COLOR, 0xFFFFFFCC);
        addInput("intensity", "強度", PortType.FLOAT, 1.0f).range(0f, 5f);
        addInput("width", "寬度", PortType.FLOAT, 1f).range(0.1f, 4f);
        addInput("height", "高度", PortType.FLOAT, 1f).range(0.1f, 4f);
        addOutput("lightSpec", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        // lightSpec populated by downstream binder
    }

    @Override public String getTooltip() { return "面光源顏色、強度與面積尺寸"; }
    @Override public String typeId() { return "render.lighting.AreaLight"; }
}
