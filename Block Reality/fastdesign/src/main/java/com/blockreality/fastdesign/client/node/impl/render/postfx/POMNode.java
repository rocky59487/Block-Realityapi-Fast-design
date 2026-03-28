package com.blockreality.fastdesign.client.node.impl.render.postfx;

import com.blockreality.fastdesign.client.node.*;

/** A3-13: 視差遮蔽 */
public class POMNode extends BRNode {
    public POMNode() {
        super("POM", "視差遮蔽", "render", NodeColor.RENDER);
        addInput("enabled", "啟用", PortType.BOOL, true);
        addInput("scale", "縮放", PortType.FLOAT, 0.04f).range(0.01f, 0.1f);
        addInput("steps", "步數", PortType.INT, 16).range(4, 64);
        addInput("refinementSteps", "精煉步數", PortType.INT, 4).range(0, 8);
        addOutput("pomEnabled", PortType.BOOL);
        addOutput("pomScale", PortType.FLOAT);
        addOutput("pomSteps", PortType.INT);
    }

    @Override
    public void evaluate() {
        getOutput("pomEnabled").setValue(getInput("enabled").getBool());
        getOutput("pomScale").setValue(getInput("scale").getFloat());
        getOutput("pomSteps").setValue(getInput("steps").getInt());
    }

    @Override public String getTooltip() { return "視差遮蔽映射，透過高度圖模擬表面凹凸深度"; }
    @Override public String typeId() { return "render.postfx.POM"; }
}
