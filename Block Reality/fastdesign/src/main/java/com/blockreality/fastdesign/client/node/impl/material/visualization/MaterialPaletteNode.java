package com.blockreality.fastdesign.client.node.impl.material.visualization;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraft.nbt.CompoundTag;

/** B4-3: 材料色板 — 將多種材料的參數組織為調色板式的比較檢視 */
@OnlyIn(Dist.CLIENT)
public class MaterialPaletteNode extends BRNode {
    public MaterialPaletteNode() {
        super("Material Palette", "材料色板", "material", NodeColor.MATERIAL);
        addInput("materials", "材料集合", PortType.STRUCT, null);
        addOutput("paletteData", "色板資料", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        CompoundTag input = getInput("materials").getValue();
        CompoundTag palette = new CompoundTag();

        if (input != null) {
            palette.put("source", input.copy());
        }
        palette.putString("type", "materialPalette");
        palette.putInt("count", input != null ? input.size() : 0);

        getOutput("paletteData").setValue(palette);
    }

    @Override public String getTooltip() { return "將多種材料組織為色板式的比較檢視，便於視覺化選材"; }
    @Override public String typeId() { return "material.visualization.MaterialPalette"; }
}
