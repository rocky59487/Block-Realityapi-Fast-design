package com.blockreality.fastdesign.client.node.impl.render.lod;

import com.blockreality.fastdesign.client.node.*;

/** A5-6: 貪心合併 */
public class GreedyMeshNode extends BRNode {
    public GreedyMeshNode() {
        super("GreedyMesh", "貪心合併", "render", NodeColor.RENDER);
        addInput("maxArea", "最大面積", PortType.INT, 256).range(16, 1024);
        addInput("cacheEnabled", "啟用快取", PortType.BOOL, true);
        addOutput("meshSpec", PortType.STRUCT);
        addOutput("greedyMeshMaxArea", PortType.INT);
    }

    @Override
    public void evaluate() {
        getOutput("greedyMeshMaxArea").setValue(getInput("maxArea").getInt());
    }

    @Override public String getTooltip() { return "貪心網格合併最大面積與快取設定"; }
    @Override public String typeId() { return "render.lod.GreedyMesh"; }
}
