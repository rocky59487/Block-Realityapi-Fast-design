package com.blockreality.fastdesign.client.node.impl.tool.ui;

import com.blockreality.fastdesign.client.node.*;
import net.minecraft.nbt.CompoundTag;

/** D3-1: 徑向選單 */
public class RadialMenuNode extends BRNode {
    public RadialMenuNode() {
        super("Radial Menu", "徑向選單", "tool", NodeColor.TOOL);
        addInput("sectorCount", "扇區數", PortType.INT, 8).range(3, 12);
        addInput("activationKey", "啟動鍵", PortType.INT, 0);
        addInput("openDurationMs", "開啟時間", PortType.INT, 150).range(50, 500);
        addInput("deadZoneRatio", "死區比例", PortType.FLOAT, 0.2f).range(0.1f, 0.5f);
        addInput("easing", "緩動函數", PortType.ENUM, "CubicOut");
        addInput("highlightColor", "高亮色", PortType.COLOR, 0xFFFFCC00);
        addInput("backgroundColor", "背景色", PortType.COLOR, 0xAA000000);
        addOutput("menuConfig", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        CompoundTag cfg = new CompoundTag();
        cfg.putInt("sectorCount", getInput("sectorCount").getInt());
        cfg.putInt("activationKey", getInput("activationKey").getInt());
        cfg.putInt("openDurationMs", getInput("openDurationMs").getInt());
        cfg.putFloat("deadZoneRatio", getInput("deadZoneRatio").getFloat());
        String easing = getInput("easing").getValue();
        cfg.putString("easing", easing != null ? easing : "CubicOut");
        cfg.putInt("highlightColor", getInput("highlightColor").getColor());
        cfg.putInt("backgroundColor", getInput("backgroundColor").getColor());
        getOutput("menuConfig").setValue(cfg);
    }

    @Override public String getTooltip() { return "徑向選單配置：扇區、緩動、色彩"; }
    @Override public String typeId() { return "tool.ui.RadialMenu"; }
}
