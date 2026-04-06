package com.blockreality.fastdesign.client.node.impl.physics.collapse;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraft.nbt.CompoundTag;

/** C4-2: 破壞模式 */
@OnlyIn(Dist.CLIENT)
public class FailureModeNode extends BRNode {
    public FailureModeNode() {
        super("Failure Mode", "破壞模式", "physics", NodeColor.PHYSICS);
        addInput("type", "類型", PortType.ENUM, "CANTILEVER");
        addInput("threshold", "閾值", PortType.FLOAT, 1.0f).range(0f, 2f);
        addOutput("failureSpec", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        CompoundTag spec = new CompoundTag();
        String type = getInput("type").getValue();
        spec.putString("type", type != null ? type : "CANTILEVER");
        spec.putFloat("threshold", getInput("threshold").getFloat());
        getOutput("failureSpec").setValue(spec);
    }

    @Override public String getTooltip() { return "破壞模式類型與閾值定義"; }
    @Override public String typeId() { return "physics.collapse.FailureMode"; }
}
