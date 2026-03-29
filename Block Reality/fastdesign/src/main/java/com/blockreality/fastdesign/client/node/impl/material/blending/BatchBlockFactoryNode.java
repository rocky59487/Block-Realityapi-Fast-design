package com.blockreality.fastdesign.client.node.impl.material.blending;

import com.blockreality.api.material.DynamicMaterial;
import com.blockreality.fastdesign.client.node.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/** B5-6: 批量方塊工廠 — 從基底方塊生成多個縮放變體 */
public class BatchBlockFactoryNode extends BRNode {
    public BatchBlockFactoryNode() {
        super("Batch Block Factory", "批量方塊工廠", "material", NodeColor.BLENDING);
        addInput("baseBlock", "基底方塊", PortType.BLOCK, null);
        addInput("variants", "變體數量", PortType.INT, 3).range(2, 8);
        addInput("scaleProperty", "縮放屬性", PortType.ENUM, "Rcomp");
        addInput("scaleMin", "最小倍率", PortType.FLOAT, 0.5f).range(0.5f, 1f);
        addInput("scaleMax", "最大倍率", PortType.FLOAT, 3.0f).range(1f, 10f);
        addInput("distribution", "分佈", PortType.ENUM, "Linear");
        addInput("nameSuffix", "名稱後綴", PortType.ENUM, "_tier_{n}");
        addOutput("blocks", "方塊列表", PortType.STRUCT);
        addOutput("count", "數量", PortType.INT);
        addOutput("summaryTable", "摘要表", PortType.ENUM);
    }

    @Override
    public void evaluate() {
        BRBlockDef base = getInput("baseBlock").getValue();
        if (base == null) {
            getOutput("blocks").setValue(new CompoundTag());
            getOutput("count").setValue(0);
            getOutput("summaryTable").setValue("無基底方塊");
            return;
        }

        int variants = getInput("variants").getInt();
        float scaleMin = getInput("scaleMin").getFloat();
        float scaleMax = getInput("scaleMax").getFloat();
        String suffix = getInput("nameSuffix").getValue();
        if (suffix == null) suffix = "_tier_{n}";

        CompoundTag blocksTag = new CompoundTag();
        ListTag list = new ListTag();
        StringBuilder summary = new StringBuilder();

        for (int i = 0; i < variants; i++) {
            float t = variants > 1 ? (float) i / (variants - 1) : 0.5f;
            float scale = scaleMin + (scaleMax - scaleMin) * t;

            double rcomp = base.rcomp() * scale;
            double rtens = base.rtens() * scale;
            double rshear = base.rshear() * scale;
            double density = base.material().getDensity();

            String variantName = base.materialId() + suffix.replace("{n}", String.valueOf(i + 1));
            DynamicMaterial varMat = DynamicMaterial.ofCustom(variantName, rcomp, rtens, rshear, density);
            BRBlockDef variant = base.withMaterial(varMat);

            list.add(variant.serialize());
            summary.append(String.format("Tier %d: ×%.2f (Rcomp=%.1f)\n", i + 1, scale, rcomp));
        }

        blocksTag.put("variants", list);
        getOutput("blocks").setValue(blocksTag);
        getOutput("count").setValue(variants);
        getOutput("summaryTable").setValue(summary.toString().trim());
    }

    @Override public String getTooltip() { return "從基底方塊批量生成多個強度等級的方塊變體"; }
    @Override public String typeId() { return "material.blending.BatchBlockFactory"; }
}
