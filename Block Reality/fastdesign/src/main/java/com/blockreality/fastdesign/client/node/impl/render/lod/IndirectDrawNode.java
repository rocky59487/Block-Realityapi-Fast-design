package com.blockreality.fastdesign.client.node.impl.render.lod;

import com.blockreality.fastdesign.client.node.*;

/** A5-8: GPU 間接繪製 */
public class IndirectDrawNode extends BRNode {
    public IndirectDrawNode() {
        super("IndirectDraw", "GPU 間接繪製", "render", NodeColor.RENDER);
        addInput("enabled", "啟用", PortType.BOOL, true);
        addInput("maxDrawCommands", "最大繪製指令數", PortType.INT, 4096).range(256, 16384);
        addOutput("drawSpec", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        // drawSpec populated by downstream binder
    }

    @Override public String getTooltip() { return "GPU 間接繪製指令上限設定"; }
    @Override public String typeId() { return "render.lod.IndirectDraw"; }
}
