package com.blockreality.fastdesign.client.node.impl.tool.input;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraft.nbt.CompoundTag;

/** D4-1: 鍵位映射 */
@OnlyIn(Dist.CLIENT)
public class KeyBindingsNode extends BRNode {
    public KeyBindingsNode() {
        super("Key Bindings", "鍵位映射", "tool", NodeColor.TOOL);
        addInput("category", "類別", PortType.ENUM, "All");
        addOutput("bindingsConfig", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        CompoundTag cfg = new CompoundTag();
        String cat = getInput("category").getValue();
        cfg.putString("category", cat != null ? cat : "All");
        getOutput("bindingsConfig").setValue(cfg);
    }

    @Override public String getTooltip() { return "鍵位映射配置"; }
    @Override public String typeId() { return "tool.input.KeyBindings"; }
}
