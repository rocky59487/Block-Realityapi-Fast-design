package com.blockreality.fastdesign.client.node.impl.tool.selection;

import com.blockreality.fastdesign.client.node.*;
import net.minecraft.nbt.CompoundTag;

/** D1-4: 工具遮罩 */
public class ToolMaskNode extends BRNode {
    public ToolMaskNode() {
        super("Tool Mask", "工具遮罩", "tool", NodeColor.TOOL);
        addInput("blockId", "方塊 ID", PortType.ENUM, "");
        addInput("yRangeMin", "Y 最小", PortType.INT, 0);
        addInput("yRangeMax", "Y 最大", PortType.INT, 320);
        addInput("solidOnly", "僅固體", PortType.BOOL, false);
        addInput("surfaceOnly", "僅表面", PortType.BOOL, false);
        addOutput("maskConfig", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        CompoundTag mask = new CompoundTag();
        String blockId = getInput("blockId").getValue();
        mask.putString("blockId", blockId != null ? blockId : "");
        mask.putInt("yRangeMin", getInput("yRangeMin").getInt());
        mask.putInt("yRangeMax", getInput("yRangeMax").getInt());
        mask.putBoolean("solidOnly", getInput("solidOnly").getBool());
        mask.putBoolean("surfaceOnly", getInput("surfaceOnly").getBool());
        getOutput("maskConfig").setValue(mask);
    }

    @Override public String getTooltip() { return "工具操作遮罩：方塊類型、Y 範圍、表面過濾"; }
    @Override public String typeId() { return "tool.selection.ToolMask"; }
}
