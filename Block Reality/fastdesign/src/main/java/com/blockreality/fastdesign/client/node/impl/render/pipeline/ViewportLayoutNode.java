package com.blockreality.fastdesign.client.node.impl.render.pipeline;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;

/** A2-5: 視窗佈局 */
@OnlyIn(Dist.CLIENT)
public class ViewportLayoutNode extends BRNode {
    public ViewportLayoutNode() {
        super("ViewportLayout", "視窗佈局", "render", NodeColor.RENDER);
        addInput("mode", "模式", PortType.ENUM, "Single");
        addInput("mainViewFOV", "FOV", PortType.FLOAT, 70f).range(30, 120);
        addInput("orthoZoom", "正交縮放", PortType.FLOAT, 1.0f).range(0.1f, 10f);
        addOutput("viewportConfig", PortType.STRUCT);
    }

    @Override
    public void evaluate() { /* pass-through to binder */ }

    @Override public String getTooltip() { return "視窗佈局：Single/DualH/DualV/Quad"; }
    @Override public String typeId() { return "render.pipeline.ViewportLayout"; }
}
