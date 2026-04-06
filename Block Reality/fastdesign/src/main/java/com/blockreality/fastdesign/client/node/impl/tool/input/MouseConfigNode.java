package com.blockreality.fastdesign.client.node.impl.tool.input;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraft.nbt.CompoundTag;

/** D4-2: 滑鼠設定 */
@OnlyIn(Dist.CLIENT)
public class MouseConfigNode extends BRNode {
    public MouseConfigNode() {
        super("Mouse Config", "滑鼠設定", "tool", NodeColor.TOOL);
        addInput("sensitivity", "靈敏度", PortType.FLOAT, 1.0f).range(0.1f, 5f);
        addInput("invertY", "反轉 Y 軸", PortType.BOOL, false);
        addInput("scrollSpeed", "滾輪速度", PortType.FLOAT, 1.0f).range(0.1f, 5f);
        addOutput("mouseConfig", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        CompoundTag cfg = new CompoundTag();
        cfg.putFloat("sensitivity", getInput("sensitivity").getFloat());
        cfg.putBoolean("invertY", getInput("invertY").getBool());
        cfg.putFloat("scrollSpeed", getInput("scrollSpeed").getFloat());
        getOutput("mouseConfig").setValue(cfg);
    }

    @Override public String getTooltip() { return "滑鼠靈敏度與滾輪設定"; }
    @Override public String typeId() { return "tool.input.MouseConfig"; }
}
