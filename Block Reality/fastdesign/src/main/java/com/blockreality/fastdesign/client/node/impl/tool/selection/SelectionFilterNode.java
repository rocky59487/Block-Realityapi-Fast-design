package com.blockreality.fastdesign.client.node.impl.tool.selection;

import com.blockreality.fastdesign.client.node.*;
import net.minecraft.nbt.CompoundTag;

/** D1-3: 選取過濾 */
public class SelectionFilterNode extends BRNode {
    public SelectionFilterNode() {
        super("Selection Filter", "選取過濾", "tool", NodeColor.TOOL);
        addInput("maskConfig", "遮罩配置", PortType.STRUCT, null);
        addInput("predicateChain", "條件鏈", PortType.STRUCT, null);
        addOutput("filteredSelection", PortType.STRUCT);
        addOutput("filteredCount", PortType.INT);
    }

    @Override
    public void evaluate() {
        getOutput("filteredSelection").setValue(new CompoundTag());
        getOutput("filteredCount").setValue(0);
    }

    @Override public String getTooltip() { return "依遮罩與條件鏈過濾選取"; }
    @Override public String typeId() { return "tool.selection.SelectionFilter"; }
}
