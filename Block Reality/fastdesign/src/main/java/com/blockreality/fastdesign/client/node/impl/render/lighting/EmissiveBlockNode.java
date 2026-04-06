package com.blockreality.fastdesign.client.node.impl.render.lighting;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;

/** A4-6: 自發光方塊 */
@OnlyIn(Dist.CLIENT)
public class EmissiveBlockNode extends BRNode {
    public EmissiveBlockNode() {
        super("EmissiveBlock", "自發光方塊", "render", NodeColor.RENDER);
        addInput("intensity", "強度", PortType.FLOAT, 1.0f).range(0f, 5f);
        addInput("color", "顏色", PortType.COLOR, 0xFFFFCC44);
        addInput("radius", "半徑", PortType.FLOAT, 8f).range(1f, 32f);
        addOutput("emissiveSpec", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        // emissiveSpec populated by downstream binder
    }

    @Override public String getTooltip() { return "自發光方塊的發光強度、顏色與影響半徑"; }
    @Override public String typeId() { return "render.lighting.EmissiveBlock"; }
}
