package com.blockreality.fastdesign.client.node.impl.render.postfx;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;

/** A3-16: 螢幕空間全域光照 */
@OnlyIn(Dist.CLIENT)
public class SSGINode extends BRNode {
    public SSGINode() {
        super("SSGI", "螢幕空間全域光照", "render", NodeColor.RENDER);
        addInput("enabled", "啟用", PortType.BOOL, true);
        addInput("intensity", "強度", PortType.FLOAT, 0.6f).range(0f, 2f);
        addInput("radius", "半徑", PortType.FLOAT, 2.0f).range(0.5f, 5f);
        addInput("samples", "取樣數", PortType.INT, 16).range(4, 64);
        addInput("temporal", "時序累積", PortType.BOOL, true);
        addOutput("ssgiEnabled", PortType.BOOL);
        addOutput("ssgiIntensity", PortType.FLOAT);
        addOutput("ssgiRadius", PortType.FLOAT);
        addOutput("ssgiSamples", PortType.INT);
    }

    @Override
    public void evaluate() {
        getOutput("ssgiEnabled").setValue(getInput("enabled").getBool());
        getOutput("ssgiIntensity").setValue(getInput("intensity").getFloat());
        getOutput("ssgiRadius").setValue(getInput("radius").getFloat());
        getOutput("ssgiSamples").setValue(getInput("samples").getInt());
    }

    @Override public String getTooltip() { return "螢幕空間全域光照，模擬間接光照的漫反射效果"; }
    @Override public String typeId() { return "render.postfx.SSGI"; }
}
