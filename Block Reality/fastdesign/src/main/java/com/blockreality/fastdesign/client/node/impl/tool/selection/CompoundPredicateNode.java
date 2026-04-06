package com.blockreality.fastdesign.client.node.impl.tool.selection;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraft.nbt.CompoundTag;

/** D1-5: 邏輯組合 */
@OnlyIn(Dist.CLIENT)
public class CompoundPredicateNode extends BRNode {
    public CompoundPredicateNode() {
        super("Compound Predicate", "邏輯組合", "tool", NodeColor.TOOL);
        addInput("predicateA", "條件 A", PortType.STRUCT, null);
        addInput("predicateB", "條件 B", PortType.STRUCT, null);
        addInput("logic", "邏輯", PortType.ENUM, "AND");
        addOutput("combined", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        CompoundTag combined = new CompoundTag();
        String logic = getInput("logic").getValue();
        combined.putString("logic", logic != null ? logic : "AND");
        CompoundTag a = getInput("predicateA").getValue();
        if (a != null) combined.put("predicateA", a);
        CompoundTag b = getInput("predicateB").getValue();
        if (b != null) combined.put("predicateB", b);
        getOutput("combined").setValue(combined);
    }

    @Override public String getTooltip() { return "AND/OR/NOT 邏輯組合兩個條件"; }
    @Override public String typeId() { return "tool.selection.CompoundPredicate"; }
}
