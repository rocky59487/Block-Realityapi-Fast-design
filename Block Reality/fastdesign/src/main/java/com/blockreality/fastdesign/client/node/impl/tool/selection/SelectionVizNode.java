package com.blockreality.fastdesign.client.node.impl.tool.selection;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraft.nbt.CompoundTag;

/** D1-6: 選取視覺化 */
@OnlyIn(Dist.CLIENT)
public class SelectionVizNode extends BRNode {
    public SelectionVizNode() {
        super("Selection Viz", "選取視覺化", "tool", NodeColor.TOOL);
        addInput("glowPeriod", "脈衝週期", PortType.INT, 40).range(10, 120);
        addInput("alpha", "透明度", PortType.FLOAT, 0.5f).range(0f, 1f);
        addInput("pulseSpeed", "脈衝速度", PortType.FLOAT, 3f).range(0f, 10f);
        addOutput("selectionVizPulseSpeed", PortType.FLOAT);
        addOutput("selectionVizFillAlpha", PortType.FLOAT);
        addOutput("vizConfig", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        float alpha = getInput("alpha").getFloat();
        float pulse = getInput("pulseSpeed").getFloat();
        getOutput("selectionVizPulseSpeed").setValue(pulse);
        getOutput("selectionVizFillAlpha").setValue(alpha);
        CompoundTag cfg = new CompoundTag();
        cfg.putInt("glowPeriod", getInput("glowPeriod").getInt());
        cfg.putFloat("alpha", alpha);
        cfg.putFloat("pulseSpeed", pulse);
        getOutput("vizConfig").setValue(cfg);
    }

    @Override public String getTooltip() { return "選取區域脈衝發光視覺化設定"; }
    @Override public String typeId() { return "tool.selection.SelectionViz"; }
}
