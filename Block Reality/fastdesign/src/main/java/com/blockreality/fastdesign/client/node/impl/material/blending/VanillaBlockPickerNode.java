package com.blockreality.fastdesign.client.node.impl.material.blending;

import com.blockreality.api.material.DefaultMaterial;
import com.blockreality.api.material.VanillaMaterialMap;
import com.blockreality.fastdesign.client.node.*;
import net.minecraft.resources.ResourceLocation;

/** B5-1: 原版方塊選取器 — 選取 Minecraft 原版方塊並查詢其材料屬性 */
public class VanillaBlockPickerNode extends BRNode {
    public VanillaBlockPickerNode() {
        super("Vanilla Block Picker", "原版方塊選取器", "material", NodeColor.BLENDING);
        addInput("blockId", "方塊 ID", PortType.ENUM, "minecraft:oak_planks");
        addInput("showVanillaStats", "顯示原版屬性", PortType.BOOL, true);
        addOutput("block", "方塊", PortType.BLOCK);
        addOutput("baseMaterial", "基礎材料", PortType.MATERIAL);
        addOutput("rcomp", "抗壓強度", PortType.FLOAT);
        addOutput("rtens", "抗拉強度", PortType.FLOAT);
        addOutput("rshear", "抗剪強度", PortType.FLOAT);
        addOutput("density", "密度", PortType.FLOAT);
        addOutput("youngsModulus", "楊氏模量", PortType.FLOAT);
        addOutput("categoryTag", "材料類別", PortType.ENUM);
    }

    @Override
    public void evaluate() {
        String blockId = getInput("blockId").getValue();
        if (blockId == null || blockId.isEmpty()) {
            blockId = "minecraft:oak_planks";
        }

        DefaultMaterial mat = VanillaMaterialMap.getInstance().getMaterial(blockId);
        ResourceLocation resLoc = new ResourceLocation(blockId);
        BRBlockDef blockDef = BRBlockDef.ofVanilla(resLoc, mat);

        getOutput("block").setValue(blockDef);
        getOutput("baseMaterial").setValue(mat);
        getOutput("rcomp").setValue((float) mat.getRcomp());
        getOutput("rtens").setValue((float) mat.getRtens());
        getOutput("rshear").setValue((float) mat.getRshear());
        getOutput("density").setValue((float) mat.getDensity());
        getOutput("youngsModulus").setValue((float) (mat.getYoungsModulusPa() / 1e9));
        getOutput("categoryTag").setValue(mat.getMaterialId());
    }

    @Override public String getTooltip() { return "選取 Minecraft 原版方塊，查詢 VanillaMaterialMap 中的材料屬性"; }
    @Override public String typeId() { return "material.blending.VanillaBlockPicker"; }
}
