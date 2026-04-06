package com.blockreality.fastdesign.client.node.impl.material.blending;

import com.blockreality.api.material.DynamicMaterial;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import com.blockreality.api.material.RMaterial;
import com.blockreality.fastdesign.client.node.*;

/** B5-2: 數值微調器 — 選擇性覆寫材料的個別工程參數 */
@OnlyIn(Dist.CLIENT)
public class PropertyTunerNode extends BRNode {
    public PropertyTunerNode() {
        super("Property Tuner", "數值微調器", "material", NodeColor.BLENDING);
        addInput("baseMaterial", "基礎材料", PortType.MATERIAL, null);
        addInput("overrideRcomp", "覆寫抗壓", PortType.BOOL, false);
        addInput("rcomp", "抗壓強度", PortType.FLOAT, 0.0f).range(0f, 10000f);
        addInput("overrideRtens", "覆寫抗拉", PortType.BOOL, false);
        addInput("rtens", "抗拉強度", PortType.FLOAT, 0.0f).range(0f, 10000f);
        addInput("overrideRshear", "覆寫抗剪", PortType.BOOL, false);
        addInput("rshear", "抗剪強度", PortType.FLOAT, 0.0f).range(0f, 10000f);
        addInput("overrideDensity", "覆寫密度", PortType.BOOL, false);
        addInput("density", "密度", PortType.FLOAT, 0.0f).range(100f, 10000f);
        addOutput("tunedMaterial", "微調材料", PortType.MATERIAL);
        addOutput("deltaReport", "差異報告", PortType.ENUM);
        addOutput("validation", "驗證", PortType.BOOL);
        addOutput("warnings", "警告", PortType.ENUM);
    }

    @Override
    public void evaluate() {
        RMaterial base = getInput("baseMaterial").getValue();
        if (base == null) {
            getOutput("tunedMaterial").setValue(null);
            getOutput("deltaReport").setValue("無基礎材料");
            getOutput("validation").setValue(false);
            getOutput("warnings").setValue("需要連接基礎材料");
            return;
        }

        double rcomp = getInput("overrideRcomp").getBool() ? getInput("rcomp").getFloat() : base.getRcomp();
        double rtens = getInput("overrideRtens").getBool() ? getInput("rtens").getFloat() : base.getRtens();
        double rshear = getInput("overrideRshear").getBool() ? getInput("rshear").getFloat() : base.getRshear();
        double density = getInput("overrideDensity").getBool() ? getInput("density").getFloat() : base.getDensity();

        boolean valid = density > 0;
        String warning = "";
        if (!valid) warning = "密度必須大於零";
        else if (rtens > rcomp * 5) warning = "抗拉/抗壓比例異常";

        DynamicMaterial tuned = valid
                ? DynamicMaterial.ofCustom(base.getMaterialId() + "_tuned", rcomp, rtens, rshear, density)
                : null;

        String delta = String.format("Rcomp: %.1f→%.1f, Rtens: %.1f→%.1f",
                base.getRcomp(), rcomp, base.getRtens(), rtens);

        getOutput("tunedMaterial").setValue(tuned);
        getOutput("deltaReport").setValue(delta);
        getOutput("validation").setValue(valid);
        getOutput("warnings").setValue(warning);
    }

    @Override public String getTooltip() { return "選擇性覆寫材料的個別工程參數，保留未覆寫的原始值"; }
    @Override public String typeId() { return "material.blending.PropertyTuner"; }
}
