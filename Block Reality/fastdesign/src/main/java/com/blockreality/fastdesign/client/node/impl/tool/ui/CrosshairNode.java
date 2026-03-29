package com.blockreality.fastdesign.client.node.impl.tool.ui;

import com.blockreality.fastdesign.client.node.*;
import net.minecraft.nbt.CompoundTag;

/** D3-6: 準心 */
public class CrosshairNode extends BRNode {
    public CrosshairNode() {
        super("Crosshair", "準心", "tool", NodeColor.TOOL);
        addInput("style", "樣式", PortType.ENUM, "Cross");
        addInput("size", "大小", PortType.INT, 15).range(5, 50);
        addInput("color", "顏色", PortType.COLOR, 0xFFFFFFFF);
        addInput("gap", "間隙", PortType.INT, 3).range(0, 10);
        addOutput("crosshairConfig", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        CompoundTag cfg = new CompoundTag();
        String style = getInput("style").getValue();
        cfg.putString("style", style != null ? style : "Cross");
        cfg.putInt("size", getInput("size").getInt());
        cfg.putInt("color", getInput("color").getColor());
        cfg.putInt("gap", getInput("gap").getInt());
        getOutput("crosshairConfig").setValue(cfg);
    }

    @Override public String getTooltip() { return "準心樣式、大小與顏色"; }
    @Override public String typeId() { return "tool.ui.Crosshair"; }
}
