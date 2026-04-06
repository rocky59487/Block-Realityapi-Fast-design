package com.blockreality.fastdesign.client.node.impl.material.blending;

import com.blockreality.api.chisel.SubBlockShape;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import com.blockreality.api.material.RMaterial;
import com.blockreality.fastdesign.client.node.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/** B5-4: 自訂方塊生成器 — 從材料和參數建立自訂方塊定義 */
@OnlyIn(Dist.CLIENT)
public class BlockCreatorNode extends BRNode {
    public BlockCreatorNode() {
        super("Block Creator", "自訂方塊生成器", "material", NodeColor.BLENDING);
        addInput("material", "材料", PortType.MATERIAL, null);
        addInput("blockName", "方塊名稱", PortType.ENUM, "custom_block");
        addInput("displayName", "顯示名稱", PortType.ENUM, "Custom Block");
        addInput("baseTexture", "基底紋理方塊", PortType.BLOCK, null);
        addInput("tintColor", "著色", PortType.COLOR, 0xFFFFFFFF);
        addInput("tintIntensity", "著色強度", PortType.FLOAT, 0.0f).range(0f, 1f);
        addInput("creativeTab", "創造模式頁籤", PortType.ENUM, "BlockReality");
        addInput("shape", "形狀", PortType.SHAPE, null);
        addInput("autoRegister", "自動註冊", PortType.BOOL, true);
        addOutput("customBlock", "自訂方塊", PortType.BLOCK);
        addOutput("registryId", "註冊 ID", PortType.ENUM);
        addOutput("nbtData", "NBT 資料", PortType.STRUCT);
        addOutput("isValid", "有效", PortType.BOOL);
    }

    @Override
    public void evaluate() {
        RMaterial mat = getInput("material").getValue();
        String name = getInput("blockName").getValue();
        String displayName = getInput("displayName").getValue();
        int tint = getInput("tintColor").getColor();
        float tintIntensity = getInput("tintIntensity").getFloat();
        SubBlockShape shape = getInput("shape").getValue();

        if (mat == null || name == null || name.isEmpty()) {
            getOutput("customBlock").setValue(null);
            getOutput("registryId").setValue("");
            getOutput("nbtData").setValue(new CompoundTag());
            getOutput("isValid").setValue(false);
            return;
        }

        if (shape == null) shape = SubBlockShape.FULL;

        // 基底紋理方塊作為紋理參考
        BRBlockDef baseTexBlock = getInput("baseTexture").getValue();
        ResourceLocation texId = baseTexBlock != null ? baseTexBlock.blockId() : null;

        BRBlockDef custom = BRBlockDef.ofCustom(
                name, mat, texId, shape,
                displayName != null ? displayName : name,
                tint, tintIntensity);

        getOutput("customBlock").setValue(custom);
        getOutput("registryId").setValue("blockreality:" + name);
        getOutput("nbtData").setValue(custom.serialize());
        getOutput("isValid").setValue(true);
    }

    @Override public String getTooltip() { return "從材料、形狀和紋理參數組合建立自訂方塊定義"; }
    @Override public String typeId() { return "material.blending.BlockCreator"; }
}
