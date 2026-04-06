package com.blockreality.fastdesign.client.node.impl.tool.placement;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraft.nbt.CompoundTag;

/** D2-4: 藍圖放置 */
@OnlyIn(Dist.CLIENT)
public class BlueprintPlaceNode extends BRNode {
    public BlueprintPlaceNode() {
        super("Blueprint Place", "藍圖放置", "tool", NodeColor.TOOL);
        addInput("snapMode", "對齊模式", PortType.ENUM, "Grid");
        addInput("rotation", "旋轉", PortType.INT, 0).range(0, 3);
        addInput("mirror", "鏡像", PortType.BOOL, false);
        addInput("ghostAlpha", "預覽透明度", PortType.FLOAT, 0.5f).range(0f, 1f);
        addOutput("previewSpec", PortType.STRUCT);
        addOutput("ghostBlockAlpha", PortType.FLOAT);
    }

    @Override
    public void evaluate() {
        CompoundTag spec = new CompoundTag();
        String snap = getInput("snapMode").getValue();
        spec.putString("snapMode", snap != null ? snap : "Grid");
        spec.putInt("rotation", getInput("rotation").getInt());
        spec.putBoolean("mirror", getInput("mirror").getBool());
        float alpha = getInput("ghostAlpha").getFloat();
        spec.putFloat("ghostAlpha", alpha);
        getOutput("previewSpec").setValue(spec);
        getOutput("ghostBlockAlpha").setValue(alpha);
    }

    @Override public String getTooltip() { return "藍圖預覽放置：對齊、旋轉、鏡像"; }
    @Override public String typeId() { return "tool.placement.BlueprintPlace"; }
}
