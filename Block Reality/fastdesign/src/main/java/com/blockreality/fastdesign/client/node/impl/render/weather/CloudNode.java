package com.blockreality.fastdesign.client.node.impl.render.weather;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;

/** A6-2: 體積雲 */
@OnlyIn(Dist.CLIENT)
public class CloudNode extends BRNode {
    public CloudNode() {
        super("Cloud", "體積雲", "render", NodeColor.RENDER);
        addInput("enabled", "啟用", PortType.BOOL, true);
        addInput("bottomHeight", "底部高度", PortType.FLOAT, 192f).range(64f, 320f);
        addInput("thickness", "厚度", PortType.FLOAT, 96f).range(32f, 256f);
        addInput("coverage", "覆蓋率", PortType.FLOAT, 0.45f).range(0f, 1f);
        addInput("density", "密度", PortType.FLOAT, 0.5f).range(0f, 1f);
        addOutput("cloudEnabled", PortType.BOOL);
        addOutput("cloudBottomHeight", PortType.FLOAT);
        addOutput("cloudThickness", PortType.FLOAT);
        addOutput("cloudDefaultCoverage", PortType.FLOAT);
    }

    @Override
    public void evaluate() {
        getOutput("cloudEnabled").setValue(getInput("enabled").getBool());
        getOutput("cloudBottomHeight").setValue(getInput("bottomHeight").getFloat());
        getOutput("cloudThickness").setValue(getInput("thickness").getFloat());
        getOutput("cloudDefaultCoverage").setValue(getInput("coverage").getFloat());
    }

    @Override public String getTooltip() { return "體積雲高度、厚度、覆蓋率與密度"; }
    @Override public String typeId() { return "render.weather.Cloud"; }
}
