package com.blockreality.fastdesign.client.node.impl.render.pipeline;

import com.blockreality.fastdesign.client.node.*;

/** A2-1: 渲染 Pass 排序 — 設計報告 §5 A2-1 */
public class PipelineOrderNode extends BRNode {
    public PipelineOrderNode() {
        super("PipelineOrder", "Pass 排序", "render", NodeColor.RENDER);
        addInput("passes", "Pass 列表", PortType.STRUCT, null);
        addOutput("orderedPasses", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        getOutput("orderedPasses").setValue(getInput("passes").getRawValue());
    }

    @Override public String getTooltip() { return "拖曳排列渲染 Pass 順序"; }
    @Override public String typeId() { return "render.pipeline.PipelineOrder"; }
}
