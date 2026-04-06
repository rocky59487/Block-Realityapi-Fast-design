package com.blockreality.fastdesign.client.node.impl.render.water;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;

/** A7-3: 泡沫 */
@OnlyIn(Dist.CLIENT)
public class WaterFoamNode extends BRNode {
    public WaterFoamNode() {
        super("WaterFoam", "泡沫", "render", NodeColor.RENDER);
        addInput("threshold", "閾值", PortType.FLOAT, 0.8f).range(0f, 1f);
        addInput("fadeSpeed", "消退速度", PortType.FLOAT, 0.5f).range(0f, 2f);
        addInput("color", "顏色", PortType.COLOR, 0xFFEEEEFF);
        addOutput("waterFoamThreshold", PortType.FLOAT);
        addOutput("foamSpec", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        getOutput("waterFoamThreshold").setValue(getInput("threshold").getFloat());
    }

    @Override public String getTooltip() { return "水面泡沫閾值、消退速度與顏色"; }
    @Override public String typeId() { return "render.water.WaterFoam"; }
}
