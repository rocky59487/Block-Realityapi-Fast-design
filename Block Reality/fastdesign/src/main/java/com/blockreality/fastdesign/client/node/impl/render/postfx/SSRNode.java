package com.blockreality.fastdesign.client.node.impl.render.postfx;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;

/** A3-2: 螢幕空間反射 */
@OnlyIn(Dist.CLIENT)
public class SSRNode extends BRNode {
    public SSRNode() {
        super("SSR", "螢幕空間反射", "render", NodeColor.RENDER);
        addInput("enabled", "啟用", PortType.BOOL, true);
        addInput("maxDistance", "最大距離", PortType.FLOAT, 50f).range(10f, 200f);
        addInput("maxSteps", "最大步數", PortType.INT, 64).range(16, 256);
        addInput("binarySteps", "二分步數", PortType.INT, 8).range(0, 16);
        addInput("thickness", "厚度", PortType.FLOAT, 0.1f).range(0.01f, 1f);
        addInput("fadeEdge", "邊緣淡出", PortType.FLOAT, 0.1f).range(0f, 0.5f);
        addOutput("ssrTexture", PortType.TEXTURE);
        addOutput("ssrEnabled", PortType.BOOL);
        addOutput("ssrMaxDistance", PortType.FLOAT);
        addOutput("ssrMaxSteps", PortType.INT);
        addOutput("hitRate", PortType.FLOAT);
    }

    @Override
    public void evaluate() {
        getOutput("ssrEnabled").setValue(getInput("enabled").getBool());
        getOutput("ssrMaxDistance").setValue(getInput("maxDistance").getFloat());
        getOutput("ssrMaxSteps").setValue(getInput("maxSteps").getInt());
        getOutput("ssrTexture").setValue(0);
        getOutput("hitRate").setValue(0.0f);
    }

    @Override public String getTooltip() { return "螢幕空間反射，光線步進追蹤"; }
    @Override public String typeId() { return "render.postfx.SSR"; }
}
