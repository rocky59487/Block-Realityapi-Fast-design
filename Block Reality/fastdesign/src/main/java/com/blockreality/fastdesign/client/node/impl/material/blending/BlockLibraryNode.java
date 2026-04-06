package com.blockreality.fastdesign.client.node.impl.material.blending;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraft.nbt.CompoundTag;

/** B5-8: 自訂方塊庫 — 管理和匯入/匯出自訂方塊集合 */
@OnlyIn(Dist.CLIENT)
public class BlockLibraryNode extends BRNode {
    public BlockLibraryNode() {
        super("Block Library", "自訂方塊庫", "material", NodeColor.BLENDING);
        addInput("importFile", "匯入檔案", PortType.ENUM, "");
        addOutput("allBlocks", "所有方塊", PortType.STRUCT);
        addOutput("count", "數量", PortType.INT);
        addOutput("totalDiskSizeKB", "檔案大小(KB)", PortType.FLOAT);
    }

    @Override
    public void evaluate() {
        String importFile = getInput("importFile").getValue();

        CompoundTag library = new CompoundTag();
        int count = 0;
        float sizeKB = 0f;

        if (importFile != null && !importFile.isEmpty()) {
            // 實際檔案讀取由 BlockLibraryManager 處理
            // 此處設定佔位資料
            library.putString("source", importFile);
            library.putString("status", "pending_load");
        } else {
            library.putString("status", "empty");
        }

        getOutput("allBlocks").setValue(library);
        getOutput("count").setValue(count);
        getOutput("totalDiskSizeKB").setValue(sizeKB);
    }

    @Override public String getTooltip() { return "管理自訂方塊庫，支援匯入/匯出方塊定義檔案"; }
    @Override public String typeId() { return "material.blending.BlockLibrary"; }
}
