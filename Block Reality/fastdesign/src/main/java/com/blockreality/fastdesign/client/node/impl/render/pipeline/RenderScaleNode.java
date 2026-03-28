package com.blockreality.fastdesign.client.node.impl.render.pipeline;

import com.blockreality.fastdesign.client.node.*;

/** A2-7: 渲染解析度縮放 */
public class RenderScaleNode extends BRNode {
    public RenderScaleNode() {
        super("RenderScale", "解析度縮放", "render", NodeColor.RENDER);
        addInput("scale", "縮放", PortType.FLOAT, 1.0f).range(0.25f, 2.0f);
        addInput("upscaleMethod", "上取樣", PortType.ENUM, "Bilinear");
        addOutput("internalWidth", PortType.INT);
        addOutput("internalHeight", PortType.INT);
    }

    @Override
    public void evaluate() {
        float scale = getInput("scale").getFloat();
        getOutput("internalWidth").setValue((int) (1920 * scale));
        getOutput("internalHeight").setValue((int) (1080 * scale));
    }

    @Override public String getTooltip() { return "內部渲染解析度與上取樣方法"; }
    @Override public String typeId() { return "render.pipeline.RenderScale"; }
}
