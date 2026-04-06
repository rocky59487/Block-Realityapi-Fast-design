package com.blockreality.fastdesign.client.node.impl.render.postfx;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;

/** A3-11: 鏡頭光暈 */
@OnlyIn(Dist.CLIENT)
public class LensFlareNode extends BRNode {
    public LensFlareNode() {
        super("LensFlare", "鏡頭光暈", "render", NodeColor.RENDER);
        addInput("enabled", "啟用", PortType.BOOL, true);
        addInput("intensity", "強度", PortType.FLOAT, 0.8f).range(0f, 2f);
        addInput("ghostCount", "鬼影數", PortType.INT, 4).range(1, 8);
        addInput("dispersal", "分散度", PortType.FLOAT, 0.5f).range(0f, 1f);
        addOutput("lensFlareEnabled", PortType.BOOL);
        addOutput("lensFlareIntensity", PortType.FLOAT);
    }

    @Override
    public void evaluate() {
        getOutput("lensFlareEnabled").setValue(getInput("enabled").getBool());
        getOutput("lensFlareIntensity").setValue(getInput("intensity").getFloat());
    }

    @Override public String getTooltip() { return "鏡頭光暈，模擬光線通過鏡頭產生的鬼影與光斑"; }
    @Override public String typeId() { return "render.postfx.LensFlare"; }
}
