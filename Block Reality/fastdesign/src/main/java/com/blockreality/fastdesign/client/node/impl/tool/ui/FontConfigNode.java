package com.blockreality.fastdesign.client.node.impl.tool.ui;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraft.nbt.CompoundTag;

/** D3-5: 字型設定 */
@OnlyIn(Dist.CLIENT)
public class FontConfigNode extends BRNode {
    public FontConfigNode() {
        super("Font Config", "字型設定", "tool", NodeColor.TOOL);
        addInput("fontSize", "字型大小", PortType.FLOAT, 1.0f).range(0.5f, 2f);
        addInput("lineSpacing", "行距", PortType.FLOAT, 1.2f).range(1f, 2f);
        addOutput("fontConfig", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        CompoundTag cfg = new CompoundTag();
        cfg.putFloat("fontSize", getInput("fontSize").getFloat());
        cfg.putFloat("lineSpacing", getInput("lineSpacing").getFloat());
        getOutput("fontConfig").setValue(cfg);
    }

    @Override public String getTooltip() { return "字型大小與行距設定"; }
    @Override public String typeId() { return "tool.ui.FontConfig"; }
}
