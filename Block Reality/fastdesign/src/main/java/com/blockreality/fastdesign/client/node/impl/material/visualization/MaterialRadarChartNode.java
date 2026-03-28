package com.blockreality.fastdesign.client.node.impl.material.visualization;

import com.blockreality.api.material.RMaterial;
import com.blockreality.fastdesign.client.node.*;
import net.minecraft.nbt.CompoundTag;

/** B4-1: 材料雷達圖 — 將材料參數正規化為雷達圖可視化資料 */
public class MaterialRadarChartNode extends BRNode {
    public MaterialRadarChartNode() {
        super("Material Radar Chart", "材料雷達圖", "material", NodeColor.MATERIAL);
        addInput("material", "材料", PortType.MATERIAL, null);
        addOutput("chartData", "圖表資料", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        RMaterial mat = getInput("material").getValue();
        CompoundTag data = new CompoundTag();

        if (mat == null) {
            data.putFloat("rcomp", 0f);
            data.putFloat("rtens", 0f);
            data.putFloat("rshear", 0f);
            data.putFloat("density", 0f);
            data.putFloat("youngsModulus", 0f);
            data.putFloat("ductility", 0f);
        } else {
            // 正規化到 0~1 範圍（以典型最大值為基準）
            data.putFloat("rcomp", (float) Math.min(1.0, mat.getRcomp() / 500.0));
            data.putFloat("rtens", (float) Math.min(1.0, mat.getRtens() / 500.0));
            data.putFloat("rshear", (float) Math.min(1.0, mat.getRshear() / 200.0));
            data.putFloat("density", (float) Math.min(1.0, mat.getDensity() / 8000.0));
            data.putFloat("youngsModulus", (float) Math.min(1.0, mat.getYoungsModulusPa() / 200e9));
            data.putFloat("ductility", mat.isDuctile() ? 0.8f : 0.2f);
            data.putString("materialId", mat.getMaterialId());
        }

        getOutput("chartData").setValue(data);
    }

    @Override public String getTooltip() { return "將材料的六軸工程參數正規化為雷達圖可視化資料"; }
    @Override public String typeId() { return "material.visualization.MaterialRadarChart"; }
}
