package com.blockreality.fastdesign.client.node.impl.render.postfx;

import com.blockreality.fastdesign.client.node.*;

/** A3-5: 色調映射 */
public class TonemapNode extends BRNode {
    public TonemapNode() {
        super("Tonemap", "色調映射", "render", NodeColor.RENDER);
        addInput("mode", "模式", PortType.ENUM, "ACES");
        addInput("exposure", "曝光", PortType.FLOAT, 1.0f).range(0.1f, 10f);
        addInput("autoExposure", "自動曝光", PortType.BOOL, true);
        addInput("adaptSpeed", "適應速度", PortType.FLOAT, 1.5f).range(0.1f, 5f);
        addInput("minEV", "最小 EV", PortType.FLOAT, -2f).range(-4f, 0f);
        addInput("maxEV", "最大 EV", PortType.FLOAT, 12f).range(4f, 16f);
        addInput("gamma", "Gamma", PortType.FLOAT, 2.2f).range(1.8f, 2.6f);
        addOutput("tonemappedTexture", PortType.TEXTURE);
        addOutput("tonemapMode", PortType.INT);
        addOutput("autoExposureEnabled", PortType.BOOL);
        addOutput("currentEV", PortType.FLOAT);
    }

    @Override
    public void evaluate() {
        Object modeVal = getInput("mode").getRawValue();
        String modeStr = modeVal != null ? modeVal.toString() : "ACES";
        int modeInt;
        switch (modeStr) {
            case "Reinhard":    modeInt = 0; break;
            case "ACES":        modeInt = 1; break;
            case "Uncharted2":  modeInt = 2; break;
            default:            modeInt = 1; break;
        }
        getOutput("tonemapMode").setValue(modeInt);
        getOutput("autoExposureEnabled").setValue(getInput("autoExposure").getBool());
        getOutput("currentEV").setValue(getInput("exposure").getFloat());
        getOutput("tonemappedTexture").setValue(0);
    }

    @Override public String getTooltip() { return "色調映射，將 HDR 色彩映射至 LDR 範圍"; }
    @Override public String typeId() { return "render.postfx.Tonemap"; }
}
