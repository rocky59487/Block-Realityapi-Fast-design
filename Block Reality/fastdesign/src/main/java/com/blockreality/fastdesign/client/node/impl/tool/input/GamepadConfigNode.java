package com.blockreality.fastdesign.client.node.impl.tool.input;

import com.blockreality.fastdesign.client.node.*;
import net.minecraft.nbt.CompoundTag;

/** D4-3: 手柄設定 */
public class GamepadConfigNode extends BRNode {
    public GamepadConfigNode() {
        super("Gamepad Config", "手柄設定", "tool", NodeColor.TOOL);
        addInput("deadzone", "死區", PortType.FLOAT, 0.2f).range(0.05f, 0.5f);
        addInput("stickCurve", "搖桿曲線", PortType.ENUM, "Linear");
        addOutput("gamepadConfig", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        CompoundTag cfg = new CompoundTag();
        cfg.putFloat("deadzone", getInput("deadzone").getFloat());
        String curve = getInput("stickCurve").getValue();
        cfg.putString("stickCurve", curve != null ? curve : "Linear");
        getOutput("gamepadConfig").setValue(cfg);
    }

    @Override public String getTooltip() { return "手柄死區與搖桿響應曲線"; }
    @Override public String typeId() { return "tool.input.GamepadConfig"; }
}
