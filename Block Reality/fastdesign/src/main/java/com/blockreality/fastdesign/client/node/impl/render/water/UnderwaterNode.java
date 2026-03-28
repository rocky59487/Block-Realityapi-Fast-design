package com.blockreality.fastdesign.client.node.impl.render.water;

import com.blockreality.fastdesign.client.node.*;

/** A7-4: 水下效果 */
public class UnderwaterNode extends BRNode {
    public UnderwaterNode() {
        super("Underwater", "水下效果", "render", NodeColor.RENDER);
        addInput("fogDensity", "霧密度", PortType.FLOAT, 0.1f).range(0f, 0.5f);
        addInput("tintColor", "色調", PortType.COLOR, 0xFF224466);
        addInput("causticIntensity", "焦散強度", PortType.FLOAT, 0.5f).range(0f, 1f);
        addOutput("underwaterSpec", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        // underwaterSpec populated by downstream binder
    }

    @Override public String getTooltip() { return "水下霧密度、色調與焦散強度"; }
    @Override public String typeId() { return "render.water.Underwater"; }
}
