package com.blockreality.fastdesign.client.node.impl.tool.ui;

import com.blockreality.fastdesign.client.node.*;
import net.minecraft.nbt.CompoundTag;

/** D3-2: 全息圖風格 */
public class HologramStyleNode extends BRNode {
    public HologramStyleNode() {
        super("Hologram Style", "全息圖風格", "tool", NodeColor.TOOL);
        addInput("alpha", "透明度", PortType.FLOAT, 0.5f).range(0f, 1f);
        addInput("cullDist", "裁切距離", PortType.FLOAT, 128f).range(16f, 512f);
        addInput("cornerMarks", "角標", PortType.BOOL, true);
        addOutput("hologramSpec", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        CompoundTag spec = new CompoundTag();
        spec.putFloat("alpha", getInput("alpha").getFloat());
        spec.putFloat("cullDist", getInput("cullDist").getFloat());
        spec.putBoolean("cornerMarks", getInput("cornerMarks").getBool());
        getOutput("hologramSpec").setValue(spec);
    }

    @Override public String getTooltip() { return "3D 全息圖預覽風格配置"; }
    @Override public String typeId() { return "tool.ui.HologramStyle"; }
}
