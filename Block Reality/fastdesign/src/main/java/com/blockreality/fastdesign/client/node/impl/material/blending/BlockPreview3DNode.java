package com.blockreality.fastdesign.client.node.impl.material.blending;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;

/** B5-5: 方塊即時預覽 — 提供方塊的 3D 即時預覽紋理輸出 */
@OnlyIn(Dist.CLIENT)
public class BlockPreview3DNode extends BRNode {
    public BlockPreview3DNode() {
        super("Block Preview 3D", "方塊即時預覽", "material", NodeColor.BLENDING);
        addInput("block", "方塊", PortType.BLOCK, null);
        addInput("showStressOverlay", "應力覆蓋", PortType.BOOL, false);
        addInput("rotateSpeed", "旋轉速度", PortType.FLOAT, 0.5f).range(0f, 2f);
        addInput("showGrid", "顯示網格", PortType.BOOL, true);
        addInput("compareWith", "比較方塊", PortType.BLOCK, null);
        addOutput("previewTexture", "預覽紋理", PortType.TEXTURE);
    }

    @Override
    public void evaluate() {
        BRBlockDef block = getInput("block").getValue();
        // 預覽紋理 ID 由渲染系統在 client tick 中更新
        // 此處僅設定佔位紋理 ID
        getOutput("previewTexture").setValue(block != null ? 1 : 0);
    }

    @Override public String getTooltip() { return "提供方塊的 3D 即時旋轉預覽，可疊加應力覆蓋和比較檢視"; }
    @Override public String typeId() { return "material.blending.BlockPreview3D"; }
}
