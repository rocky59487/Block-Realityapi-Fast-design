package com.blockreality.fastdesign.client.node.impl.tool.selection;

import com.blockreality.fastdesign.client.node.*;
import net.minecraft.nbt.CompoundTag;

/** D1-7: 選取匯出 */
public class SelectionExportNode extends BRNode {
    public SelectionExportNode() {
        super("Selection Export", "選取匯出", "tool", NodeColor.TOOL);
        addInput("selection", "選取", PortType.STRUCT, null);
        addInput("format", "格式", PortType.ENUM, "NBT");
        addOutput("exportData", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        CompoundTag data = new CompoundTag();
        String fmt = getInput("format").getValue();
        data.putString("format", fmt != null ? fmt : "NBT");
        CompoundTag sel = getInput("selection").getValue();
        if (sel != null) data.put("selection", sel);
        getOutput("exportData").setValue(data);
    }

    @Override public String getTooltip() { return "將選取區域匯出為 NBT/Schematic"; }
    @Override public String typeId() { return "tool.selection.SelectionExport"; }
}
