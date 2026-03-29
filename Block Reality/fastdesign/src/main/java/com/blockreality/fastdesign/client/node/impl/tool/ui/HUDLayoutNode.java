package com.blockreality.fastdesign.client.node.impl.tool.ui;

import com.blockreality.fastdesign.client.node.*;
import net.minecraft.nbt.CompoundTag;

/** D3-3: HUD 佈局 */
public class HUDLayoutNode extends BRNode {
    public HUDLayoutNode() {
        super("HUD Layout", "HUD 佈局", "tool", NodeColor.TOOL);
        addInput("scale", "縮放", PortType.FLOAT, 1.0f).range(0.5f, 3f);
        addInput("position", "位置", PortType.ENUM, "TopLeft");
        addInput("showFPS", "顯示 FPS", PortType.BOOL, true);
        addOutput("hudConfig", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        CompoundTag cfg = new CompoundTag();
        cfg.putFloat("scale", getInput("scale").getFloat());
        String pos = getInput("position").getValue();
        cfg.putString("position", pos != null ? pos : "TopLeft");
        cfg.putBoolean("showFPS", getInput("showFPS").getBool());
        getOutput("hudConfig").setValue(cfg);
    }

    @Override public String getTooltip() { return "HUD 佈局位置與縮放設定"; }
    @Override public String typeId() { return "tool.ui.HUDLayout"; }
}
