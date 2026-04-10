package com.blockreality.fastdesign.client.node.impl.output.export;

import com.blockreality.fastdesign.client.node.*;

/** E1-2: 節點圖匯出 */
public class NodeGraphExportNode extends BRNode {
    public NodeGraphExportNode() {
        super("Node Graph Export", "節點圖匯出", "output", NodeColor.OUTPUT);
        addOutput("jsonGraph", PortType.ENUM);
    }

    @Override
    public void evaluate() {
        getOutput("jsonGraph").setValue("{}");
    }

    @Override public String getTooltip() { return "將整個節點圖匯出為 JSON"; }
    @Override public String typeId() { return "output.export.NodeGraphExport"; }
}
