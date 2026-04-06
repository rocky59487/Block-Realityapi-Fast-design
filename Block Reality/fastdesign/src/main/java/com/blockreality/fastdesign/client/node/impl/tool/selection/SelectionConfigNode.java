package com.blockreality.fastdesign.client.node.impl.tool.selection;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraft.nbt.CompoundTag;

/** D1-1: 選取設定 */
@OnlyIn(Dist.CLIENT)
public class SelectionConfigNode extends BRNode {
    public SelectionConfigNode() {
        super("Selection Config", "選取設定", "tool", NodeColor.TOOL);
        addInput("tool", "工具", PortType.ENUM, "Box");
        addInput("booleanMode", "布林模式", PortType.ENUM, "Replace");
        addInput("maxBlocks", "最大方塊數", PortType.INT, 1000000);
        addInput("undoDepth", "復原深度", PortType.INT, 32).range(1, 100);
        addOutput("selectionSpec", PortType.STRUCT);
        addOutput("currentCount", PortType.INT);
    }

    @Override
    public void evaluate() {
        CompoundTag spec = new CompoundTag();
        String tool = getInput("tool").getValue();
        spec.putString("tool", tool != null ? tool : "Box");
        String mode = getInput("booleanMode").getValue();
        spec.putString("booleanMode", mode != null ? mode : "Replace");
        spec.putInt("maxBlocks", getInput("maxBlocks").getInt());
        spec.putInt("undoDepth", getInput("undoDepth").getInt());
        getOutput("selectionSpec").setValue(spec);
        getOutput("currentCount").setValue(0);
    }

    @Override public String getTooltip() { return "選取工具類型與布林運算模式"; }
    @Override public String typeId() { return "tool.selection.SelectionConfig"; }
}
