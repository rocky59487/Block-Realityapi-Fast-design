package com.blockreality.fastdesign.client.node.impl.render.lod;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;

/** A5-9: 批次渲染 */
@OnlyIn(Dist.CLIENT)
public class BatchRenderNode extends BRNode {
    public BatchRenderNode() {
        super("BatchRender", "批次渲染", "render", NodeColor.RENDER);
        addInput("enabled", "啟用", PortType.BOOL, true);
        addInput("maxVertices", "最大頂點數", PortType.INT, 262144).range(65536, 1048576);
        addInput("maxMerge", "最大合併數", PortType.INT, 64).range(8, 256);
        addOutput("batchSpec", PortType.STRUCT);
        addOutput("batchMaxVertices", PortType.INT);
    }

    @Override
    public void evaluate() {
        getOutput("batchMaxVertices").setValue(getInput("maxVertices").getInt());
    }

    @Override public String getTooltip() { return "批次渲染頂點上限與合併數設定"; }
    @Override public String typeId() { return "render.lod.BatchRender"; }
}
