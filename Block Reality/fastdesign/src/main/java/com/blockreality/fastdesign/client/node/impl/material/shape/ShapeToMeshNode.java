package com.blockreality.fastdesign.client.node.impl.material.shape;

import com.blockreality.api.chisel.SubBlockShape;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import com.blockreality.fastdesign.client.node.*;
import net.minecraft.nbt.CompoundTag;

/** B3-6: 形狀轉網格 — 將形狀轉換為三角網格資料 */
@OnlyIn(Dist.CLIENT)
public class ShapeToMeshNode extends BRNode {
    public ShapeToMeshNode() {
        super("Shape To Mesh", "形狀轉網格", "material", NodeColor.MATERIAL);
        addInput("shape", "形狀", PortType.SHAPE, null);
        addOutput("meshData", "網格資料", PortType.STRUCT);
        addOutput("triangleCount", "三角形數", PortType.INT);
    }

    @Override
    public void evaluate() {
        SubBlockShape shape = getInput("shape").getValue();
        if (shape == null) {
            shape = SubBlockShape.FULL;
        }

        // 估算三角形數量：基於填充率
        // 完整方塊 = 12 triangles (6 faces × 2)
        // 複雜形狀按體素面數估算
        int triCount;
        if (shape == SubBlockShape.FULL) {
            triCount = 12;
        } else if (shape == SubBlockShape.CUSTOM) {
            triCount = 200; // 自訂形狀估計值
        } else {
            // 根據填充率粗略估計
            triCount = (int) (12 + shape.getFillRatio() * 100);
        }

        CompoundTag meshData = new CompoundTag();
        meshData.putString("shapeName", shape.getSerializedName());
        meshData.putDouble("fillRatio", shape.getFillRatio());
        meshData.putInt("triangleCount", triCount);
        meshData.putDouble("surfaceArea", shape.getCrossSectionArea() * 6.0); // 粗略估計

        getOutput("meshData").setValue(meshData);
        getOutput("triangleCount").setValue(triCount);
    }

    @Override public String getTooltip() { return "將形狀轉換為三角網格資料，輸出網格統計資訊"; }
    @Override public String typeId() { return "material.shape.ShapeToMesh"; }
}
