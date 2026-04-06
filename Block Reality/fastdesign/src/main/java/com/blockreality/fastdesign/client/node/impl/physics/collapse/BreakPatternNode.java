package com.blockreality.fastdesign.client.node.impl.physics.collapse;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraft.nbt.CompoundTag;

/** C4-4: 破碎模式 */
@OnlyIn(Dist.CLIENT)
public class BreakPatternNode extends BRNode {
    public BreakPatternNode() {
        super("Break Pattern", "破碎模式", "physics", NodeColor.PHYSICS);
        addInput("fragmentCount", "碎片數", PortType.INT, 8).range(1, 64);
        addInput("debrisPhysics", "碎片物理", PortType.BOOL, true);
        addInput("soundEnabled", "音效", PortType.BOOL, true);
        addOutput("breakSpec", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        CompoundTag spec = new CompoundTag();
        spec.putInt("fragmentCount", getInput("fragmentCount").getInt());
        spec.putBoolean("debrisPhysics", getInput("debrisPhysics").getBool());
        spec.putBoolean("soundEnabled", getInput("soundEnabled").getBool());
        getOutput("breakSpec").setValue(spec);
    }

    @Override public String getTooltip() { return "破碎碎片生成模式配置"; }
    @Override public String typeId() { return "physics.collapse.BreakPattern"; }
}
