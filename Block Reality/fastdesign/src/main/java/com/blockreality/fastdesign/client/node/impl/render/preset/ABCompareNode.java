package com.blockreality.fastdesign.client.node.impl.render.preset;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;

/** A1-5: A/B 品質比較 — 設計報告 §5 A1-5 */
@OnlyIn(Dist.CLIENT)
public class ABCompareNode extends BRNode {
    public ABCompareNode() {
        super("ABCompare", "A/B 比較", "render", NodeColor.RENDER);
        addInput("configA", "設定 A", PortType.STRUCT, null);
        addInput("configB", "設定 B", PortType.STRUCT, null);
        addInput("splitPosition", "分割位置", PortType.FLOAT, 0.5f).range(0, 1);
        addOutput("splitView", PortType.BOOL);
    }

    @Override
    public void evaluate() {
        boolean hasA = getInput("configA").isConnected();
        boolean hasB = getInput("configB").isConnected();
        getOutput("splitView").setValue(hasA && hasB);
    }

    @Override public String getTooltip() { return "A/B 分割預覽比較兩組品質設定"; }
    @Override public String typeId() { return "render.preset.ABCompare"; }
}
