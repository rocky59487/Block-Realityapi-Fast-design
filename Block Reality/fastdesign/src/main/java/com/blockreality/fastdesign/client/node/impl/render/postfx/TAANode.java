package com.blockreality.fastdesign.client.node.impl.render.postfx;

import com.blockreality.fastdesign.client.node.*;

/** A3-3: 時序抗鋸齒 */
public class TAANode extends BRNode {
    public TAANode() {
        super("TAA", "時序抗鋸齒", "render", NodeColor.RENDER);
        addInput("enabled", "啟用", PortType.BOOL, true);
        addInput("blendFactor", "混合因子", PortType.FLOAT, 0.9f).range(0.5f, 0.99f);
        addInput("jitterSamples", "抖動取樣數", PortType.INT, 16).range(4, 32);
        addInput("velocityWeight", "速度權重", PortType.FLOAT, 0.5f).range(0f, 1f);
        addInput("clampingMode", "夾取模式", PortType.ENUM, "Variance");
        addOutput("taaTexture", PortType.TEXTURE);
        addOutput("taaEnabled", PortType.BOOL);
        addOutput("taaBlendFactor", PortType.FLOAT);
        addOutput("taaJitterSamples", PortType.INT);
    }

    @Override
    public void evaluate() {
        getOutput("taaEnabled").setValue(getInput("enabled").getBool());
        getOutput("taaBlendFactor").setValue(getInput("blendFactor").getFloat());
        getOutput("taaJitterSamples").setValue(getInput("jitterSamples").getInt());
        getOutput("taaTexture").setValue(0);
    }

    @Override public String getTooltip() { return "時序抗鋸齒，透過多幀累積消除鋸齒"; }
    @Override public String typeId() { return "render.postfx.TAA"; }
}
