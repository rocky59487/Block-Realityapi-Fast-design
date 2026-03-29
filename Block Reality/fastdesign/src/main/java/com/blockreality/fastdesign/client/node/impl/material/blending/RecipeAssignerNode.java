package com.blockreality.fastdesign.client.node.impl.material.blending;

import com.blockreality.fastdesign.client.node.*;
import net.minecraft.nbt.CompoundTag;

/** B5-7: 合成配方指派 — 為自訂方塊生成合成配方 JSON */
public class RecipeAssignerNode extends BRNode {
    public RecipeAssignerNode() {
        super("Recipe Assigner", "合成配方指派", "material", NodeColor.BLENDING);
        addInput("customBlock", "自訂方塊", PortType.BLOCK, null);
        addInput("recipeType", "配方類型", PortType.ENUM, "Shaped");
        addInput("ingredients", "材料表", PortType.STRUCT, null);
        addInput("outputCount", "輸出數量", PortType.INT, 1).range(1, 64);
        addOutput("recipeJson", "配方 JSON", PortType.ENUM);
        addOutput("isValid", "有效", PortType.BOOL);
    }

    @Override
    public void evaluate() {
        BRBlockDef block = getInput("customBlock").getValue();
        String recipeType = getInput("recipeType").getValue();
        int count = getInput("outputCount").getInt();

        if (block == null) {
            getOutput("recipeJson").setValue("{}");
            getOutput("isValid").setValue(false);
            return;
        }

        // 生成簡化的配方 JSON 字串
        String blockId = block.blockId().toString();
        String type = "Shapeless".equals(recipeType)
                ? "minecraft:crafting_shapeless"
                : "minecraft:crafting_shaped";

        String json = String.format(
                "{\"type\":\"%s\",\"result\":{\"item\":\"%s\",\"count\":%d}}",
                type, blockId, count);

        getOutput("recipeJson").setValue(json);
        getOutput("isValid").setValue(true);
    }

    @Override public String getTooltip() { return "為自訂方塊指派合成配方，輸出配方 JSON 格式"; }
    @Override public String typeId() { return "material.blending.RecipeAssigner"; }
}
