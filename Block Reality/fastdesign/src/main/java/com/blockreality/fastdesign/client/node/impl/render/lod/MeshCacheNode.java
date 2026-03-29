package com.blockreality.fastdesign.client.node.impl.render.lod;

import com.blockreality.fastdesign.client.node.*;

/** A5-7: VBO 快取 */
public class MeshCacheNode extends BRNode {
    public MeshCacheNode() {
        super("MeshCache", "VBO 快取", "render", NodeColor.RENDER);
        addInput("maxSections", "最大區段數", PortType.INT, 512).range(64, 2048);
        addInput("evictionPolicy", "淘汰策略", PortType.ENUM, "LRU");
        addOutput("cacheSpec", PortType.STRUCT);
        addOutput("meshCacheMaxSections", PortType.INT);
    }

    @Override
    public void evaluate() {
        getOutput("meshCacheMaxSections").setValue(getInput("maxSections").getInt());
    }

    @Override public String getTooltip() { return "VBO 快取區段上限與淘汰策略"; }
    @Override public String typeId() { return "render.lod.MeshCache"; }
}
