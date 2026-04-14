package com.blockreality.fastdesign.client.node.impl.material.base;

import com.blockreality.api.material.DynamicMaterial;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import com.blockreality.fastdesign.client.node.*;

/** B1-2: 自定義材料 — 手動輸入工程參數建立材料 */
@OnlyIn(Dist.CLIENT)
public class CustomMaterialNode extends BRNode {
    public CustomMaterialNode() {
        super("Custom Material", "自定義材料", "material", NodeColor.MATERIAL);
        addInput("materialId", "材料 ID", PortType.ENUM, "custom");
        addInput("rcomp", "抗壓強度", PortType.FLOAT, 30.0f).range(0f, 10000f);
        addInput("rtens", "抗拉強度", PortType.FLOAT, 3.0f).range(0f, 10000f);
        addInput("rshear", "抗剪強度", PortType.FLOAT, 2.0f).range(0f, 10000f);
        addInput("density", "密度", PortType.FLOAT, 2400.0f).range(100f, 10000f);
        addInput("youngsModulus", "楊氏模量(GPa)", PortType.FLOAT, 30.0f).range(0.001f, 1000f);
        addInput("poissonsRatio", "泊松比", PortType.FLOAT, 0.2f).range(0f, 0.499f);
        addOutput("material", PortType.MATERIAL);
        addOutput("validation", "驗證", PortType.BOOL);
        addOutput("warnings", "警告", PortType.ENUM);
        // ─── Inspector 屬性 ───
        registerProperty("rcomp",        "抗壓強度（MPa），混凝土約 30，鋼材約 250");
        registerProperty("rtens",        "抗拉強度（MPa），混凝土約 3，鋼材約 250");
        registerProperty("rshear",       "抗剪強度（MPa），混凝土約 2，鋼材約 145");
        registerProperty("density",      "材料密度（kg/m³），混凝土約 2400");
        registerProperty("youngsModulus","楊氏模量（GPa），混凝土約 30，鋼材約 200");
        registerProperty("poissonsRatio","泊松比（0–0.499），混凝土約 0.20");
    }

    @Override
    public void evaluate() {
        String id = getInput("materialId").getValue();
        float rcomp = getInput("rcomp").getFloat();
        float rtens = getInput("rtens").getFloat();
        float rshear = getInput("rshear").getFloat();
        float density = getInput("density").getFloat();

        boolean valid = density > 0 && rcomp >= 0 && rtens >= 0 && rshear >= 0;
        String warning = "";
        if (density <= 0) { warning = "密度必須大於零"; valid = false; }
        else if (rtens > rcomp * 2) { warning = "抗拉強度異常偏高"; }

        if (valid) {
            DynamicMaterial mat = DynamicMaterial.ofCustom(
                    id != null ? id : "custom", rcomp, rtens, rshear, density);
            getOutput("material").setValue(mat);
        } else {
            getOutput("material").setValue(null);
        }
        getOutput("validation").setValue(valid);
        getOutput("warnings").setValue(warning);
    }

    @Override public String getTooltip() { return "手動輸入工程參數建立自訂材料定義"; }
    @Override public String typeId() { return "material.base.CustomMaterial"; }
}
