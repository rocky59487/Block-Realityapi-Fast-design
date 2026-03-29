package com.blockreality.fastdesign.client.node.impl.material.visualization;

import com.blockreality.api.material.RMaterial;
import com.blockreality.fastdesign.client.node.*;
import net.minecraft.nbt.CompoundTag;

/** B4-4: 方塊屬性表 — 將材料的所有工程參數整理為表格格式 */
public class BlockPropertyTableNode extends BRNode {
    public BlockPropertyTableNode() {
        super("Block Property Table", "方塊屬性表", "material", NodeColor.MATERIAL);
        addInput("material", "材料", PortType.MATERIAL, null);
        addOutput("tableData", "表格資料", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        RMaterial mat = getInput("material").getValue();
        CompoundTag table = new CompoundTag();

        if (mat != null) {
            table.putString("materialId", mat.getMaterialId());
            table.putDouble("rcomp", mat.getRcomp());
            table.putDouble("rtens", mat.getRtens());
            table.putDouble("rshear", mat.getRshear());
            table.putDouble("density", mat.getDensity());
            table.putDouble("youngsModulusPa", mat.getYoungsModulusPa());
            table.putDouble("poissonsRatio", mat.getPoissonsRatio());
            table.putDouble("yieldStrength", mat.getYieldStrength());
            table.putDouble("shearModulusPa", mat.getShearModulusPa());
            table.putDouble("combinedStrength", mat.getCombinedStrength());
            table.putBoolean("isDuctile", mat.isDuctile());
            table.putInt("maxSpan", mat.getMaxSpan());
        } else {
            table.putString("materialId", "none");
        }

        getOutput("tableData").setValue(table);
    }

    @Override public String getTooltip() { return "將材料的所有工程參數（強度、彈性模量、泊松比等）整理為表格"; }
    @Override public String typeId() { return "material.visualization.BlockPropertyTable"; }
}
