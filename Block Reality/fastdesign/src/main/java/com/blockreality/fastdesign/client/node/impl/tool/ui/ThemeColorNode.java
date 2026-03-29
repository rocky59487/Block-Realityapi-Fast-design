package com.blockreality.fastdesign.client.node.impl.tool.ui;

import com.blockreality.fastdesign.client.node.*;
import net.minecraft.nbt.CompoundTag;

/** D3-4: 色彩主題 */
public class ThemeColorNode extends BRNode {
    public ThemeColorNode() {
        super("Theme Color", "色彩主題", "tool", NodeColor.TOOL);
        addInput("primary", "主色", PortType.COLOR, 0xFF2196F3);
        addInput("secondary", "輔色", PortType.COLOR, 0xFF4CAF50);
        addInput("background", "背景色", PortType.COLOR, 0xFF1A1A2E);
        addInput("text", "文字色", PortType.COLOR, 0xFFDDDDDD);
        addOutput("themeConfig", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        CompoundTag cfg = new CompoundTag();
        cfg.putInt("primary", getInput("primary").getColor());
        cfg.putInt("secondary", getInput("secondary").getColor());
        cfg.putInt("background", getInput("background").getColor());
        cfg.putInt("text", getInput("text").getColor());
        getOutput("themeConfig").setValue(cfg);
    }

    @Override public String getTooltip() { return "UI 色彩主題配置"; }
    @Override public String typeId() { return "tool.ui.ThemeColor"; }
}
