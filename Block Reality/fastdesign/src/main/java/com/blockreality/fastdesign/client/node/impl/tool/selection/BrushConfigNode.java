package com.blockreality.fastdesign.client.node.impl.tool.selection;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraft.nbt.CompoundTag;

/** D1-2: 筆刷設定 */
@OnlyIn(Dist.CLIENT)
public class BrushConfigNode extends BRNode {
    public BrushConfigNode() {
        super("Brush Config", "筆刷設定", "tool", NodeColor.TOOL);
        addInput("shape", "形狀", PortType.ENUM, "Sphere");
        addInput("radius", "半徑", PortType.INT, 3).range(1, 64);
        addInput("scrollWheelEnabled", "滾輪調整", PortType.BOOL, true);
        addOutput("brushSpec", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        CompoundTag spec = new CompoundTag();
        String shape = getInput("shape").getValue();
        spec.putString("shape", shape != null ? shape : "Sphere");
        spec.putInt("radius", getInput("radius").getInt());
        spec.putBoolean("scrollWheelEnabled", getInput("scrollWheelEnabled").getBool());
        getOutput("brushSpec").setValue(spec);
    }

    @Override public String getTooltip() { return "筆刷形狀與半徑設定"; }
    @Override public String typeId() { return "tool.selection.BrushConfig"; }
}
