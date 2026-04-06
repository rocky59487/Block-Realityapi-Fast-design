package com.blockreality.fastdesign.client.node.impl.render.weather;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;

/** A6-6: 極光 */
@OnlyIn(Dist.CLIENT)
public class AuroraNode extends BRNode {
    public AuroraNode() {
        super("Aurora", "極光", "render", NodeColor.RENDER);
        addInput("enabled", "啟用", PortType.BOOL, false);
        addInput("intensity", "強度", PortType.FLOAT, 0.8f).range(0f, 2f);
        addInput("height", "高度", PortType.FLOAT, 200f).range(100f, 400f);
        addInput("waveSpeed", "波動速度", PortType.FLOAT, 0.5f).range(0f, 2f);
        addInput("color1", "顏色 1", PortType.COLOR, 0xFF00FF88);
        addInput("color2", "顏色 2", PortType.COLOR, 0xFF8800FF);
        addOutput("auroraHeight", PortType.FLOAT);
        addOutput("auroraSpec", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        getOutput("auroraHeight").setValue(getInput("height").getFloat());
    }

    @Override public String getTooltip() { return "極光強度、高度、波動速度與雙色設定"; }
    @Override public String typeId() { return "render.weather.Aurora"; }
}
