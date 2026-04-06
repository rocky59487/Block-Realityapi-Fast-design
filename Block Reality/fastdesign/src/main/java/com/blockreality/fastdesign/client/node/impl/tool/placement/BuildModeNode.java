package com.blockreality.fastdesign.client.node.impl.tool.placement;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraft.nbt.CompoundTag;

/** D2-1: 建造模式 */
@OnlyIn(Dist.CLIENT)
public class BuildModeNode extends BRNode {
    public BuildModeNode() {
        super("Build Mode", "建造模式", "tool", NodeColor.TOOL);
        addInput("mode", "模式", PortType.ENUM, "SINGLE");
        addInput("material", "材料", PortType.MATERIAL, null);
        addOutput("buildSpec", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        CompoundTag spec = new CompoundTag();
        String mode = getInput("mode").getValue();
        spec.putString("mode", mode != null ? mode : "SINGLE");
        getOutput("buildSpec").setValue(spec);
    }

    @Override public String getTooltip() { return "建造模式選擇：單方塊/線/面/體"; }
    @Override public String typeId() { return "tool.placement.BuildMode"; }
}
