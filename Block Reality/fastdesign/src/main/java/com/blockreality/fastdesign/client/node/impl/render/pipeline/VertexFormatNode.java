package com.blockreality.fastdesign.client.node.impl.render.pipeline;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;

/** A2-8: 頂點格式 */
@OnlyIn(Dist.CLIENT)
public class VertexFormatNode extends BRNode {
    public VertexFormatNode() {
        super("VertexFormat", "頂點格式", "render", NodeColor.RENDER);
        addInput("compactEnabled", "壓縮", PortType.BOOL, true);
        addOutput("formatSpec", PortType.STRUCT);
        addOutput("bandwidthSavingPercent", PortType.FLOAT);
    }

    @Override
    public void evaluate() {
        boolean compact = getInput("compactEnabled").getBool();
        getOutput("bandwidthSavingPercent").setValue(compact ? 42.8f : 0f);
    }

    @Override public String getTooltip() { return "頂點壓縮格式（16B vs 28B）"; }
    @Override public String typeId() { return "render.pipeline.VertexFormat"; }
}
