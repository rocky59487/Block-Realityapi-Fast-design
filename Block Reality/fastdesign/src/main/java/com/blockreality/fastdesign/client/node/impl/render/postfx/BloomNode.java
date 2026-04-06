package com.blockreality.fastdesign.client.node.impl.render.postfx;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;

/** A3-4: 泛光 */
@OnlyIn(Dist.CLIENT)
public class BloomNode extends BRNode {
    public BloomNode() {
        super("Bloom", "泛光", "render", NodeColor.RENDER);
        addInput("enabled", "啟用", PortType.BOOL, true);
        addInput("threshold", "閾值", PortType.FLOAT, 1.0f).range(0f, 5f);
        addInput("intensity", "強度", PortType.FLOAT, 1.0f).range(0f, 3f);
        addInput("passes", "迭代次數", PortType.INT, 5).range(1, 8);
        addInput("radius", "半徑", PortType.FLOAT, 1.0f).range(0.5f, 5f);
        addInput("lensDirt", "鏡頭髒污", PortType.BOOL, false);
        addInput("dirtIntensity", "髒污強度", PortType.FLOAT, 0.5f).range(0f, 1f);
        addOutput("bloomTexture", PortType.TEXTURE);
        addOutput("bloomThreshold", PortType.FLOAT);
        addOutput("bloomIntensity", PortType.FLOAT);
        addOutput("bloomEnabled", PortType.BOOL);
    }

    @Override
    public void evaluate() {
        getOutput("bloomEnabled").setValue(getInput("enabled").getBool());
        getOutput("bloomThreshold").setValue(getInput("threshold").getFloat());
        getOutput("bloomIntensity").setValue(getInput("intensity").getFloat());
        getOutput("bloomTexture").setValue(0);
    }

    @Override public String getTooltip() { return "泛光效果，提取高亮度區域並模糊擴散"; }
    @Override public String typeId() { return "render.postfx.Bloom"; }
}
