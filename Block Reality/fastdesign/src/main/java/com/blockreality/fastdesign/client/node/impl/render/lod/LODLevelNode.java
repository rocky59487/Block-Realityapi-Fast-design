package com.blockreality.fastdesign.client.node.impl.render.lod;

import com.blockreality.fastdesign.client.node.*;

/** A5-2: LOD 等級 */
public class LODLevelNode extends BRNode {
    public LODLevelNode() {
        super("LODLevel", "LOD 等級", "render", NodeColor.RENDER);
        addInput("levelIndex", "等級索引", PortType.INT, 0).range(0, 4);
        addInput("maxDistance", "最大距離", PortType.FLOAT, 64f).range(16f, 1024f);
        addInput("geometryRetention", "幾何保留率", PortType.FLOAT, 1.0f).range(0.01f, 1f);
        addInput("voxelScale", "體素倍率", PortType.INT, 1).range(1, 16);
        addInput("useSVDAG", "使用 SVDAG", PortType.BOOL, false);
        addOutput("levelConfig", PortType.STRUCT);
        addOutput("compressionRatio", PortType.FLOAT);
    }

    @Override
    public void evaluate() {
        float retention = getInput("geometryRetention").getFloat();
        int voxelScale = getInput("voxelScale").getInt();
        float ratio = retention / voxelScale;
        getOutput("compressionRatio").setValue(ratio);
    }

    @Override public String getTooltip() { return "單一 LOD 等級的距離、保留率與體素倍率"; }
    @Override public String typeId() { return "render.lod.LODLevel"; }
}
