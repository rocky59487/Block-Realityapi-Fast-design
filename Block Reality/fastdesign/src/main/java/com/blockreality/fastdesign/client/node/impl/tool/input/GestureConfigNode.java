package com.blockreality.fastdesign.client.node.impl.tool.input;

import com.blockreality.fastdesign.client.node.*;
import net.minecraft.nbt.CompoundTag;

/** D4-4: 手勢設定 */
public class GestureConfigNode extends BRNode {
    public GestureConfigNode() {
        super("Gesture Config", "手勢設定", "tool", NodeColor.TOOL);
        addInput("enabled", "啟用", PortType.BOOL, false);
        addOutput("gestureConfig", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        CompoundTag cfg = new CompoundTag();
        cfg.putBoolean("enabled", getInput("enabled").getBool());
        getOutput("gestureConfig").setValue(cfg);
    }

    @Override public String getTooltip() { return "觸控手勢啟用設定"; }
    @Override public String typeId() { return "tool.input.GestureConfig"; }
}
