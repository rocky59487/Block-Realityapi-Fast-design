package com.blockreality.fastdesign.client.node.impl.render.lod;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;

/** A5-4: 遮蔽剔除 */
@OnlyIn(Dist.CLIENT)
public class OcclusionCullerNode extends BRNode {
    public OcclusionCullerNode() {
        super("OcclusionCuller", "遮蔽剔除", "render", NodeColor.RENDER);
        addInput("enabled", "啟用", PortType.BOOL, true);
        addInput("queryCount", "查詢數", PortType.INT, 64).range(16, 256);
        addInput("timeout", "逾時", PortType.INT, 2).range(1, 10);
        addOutput("cullerSpec", PortType.STRUCT);
        addOutput("occlusionQueryEnabled", PortType.BOOL);
    }

    @Override
    public void evaluate() {
        getOutput("occlusionQueryEnabled").setValue(getInput("enabled").getBool());
    }

    @Override public String getTooltip() { return "遮蔽剔除查詢數量與逾時設定"; }
    @Override public String typeId() { return "render.lod.OcclusionCuller"; }
}
