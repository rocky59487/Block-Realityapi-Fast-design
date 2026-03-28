package com.blockreality.fastdesign.client.node.impl.tool.placement;

import com.blockreality.fastdesign.client.node.*;
import net.minecraft.nbt.CompoundTag;

/** D2-3: 快速放置 */
public class QuickPlacerNode extends BRNode {
    public QuickPlacerNode() {
        super("Quick Placer", "快速放置", "tool", NodeColor.TOOL);
        addInput("brushRadius", "筆刷半徑", PortType.INT, 1).range(1, 16);
        addInput("faceExtendEnabled", "面延伸", PortType.BOOL, true);
        addInput("scrollWheel", "滾輪調整", PortType.BOOL, true);
        addOutput("placerSpec", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        CompoundTag spec = new CompoundTag();
        spec.putInt("brushRadius", getInput("brushRadius").getInt());
        spec.putBoolean("faceExtendEnabled", getInput("faceExtendEnabled").getBool());
        spec.putBoolean("scrollWheel", getInput("scrollWheel").getBool());
        getOutput("placerSpec").setValue(spec);
    }

    @Override public String getTooltip() { return "快速放置筆刷配置"; }
    @Override public String typeId() { return "tool.placement.QuickPlacer"; }
}
