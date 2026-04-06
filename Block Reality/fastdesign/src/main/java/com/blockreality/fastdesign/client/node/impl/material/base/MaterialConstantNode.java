package com.blockreality.fastdesign.client.node.impl.material.base;

import com.blockreality.api.material.DefaultMaterial;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import com.blockreality.api.material.RMaterial;
import com.blockreality.fastdesign.client.node.*;

/** B1-1: 材料常數 — 從 DefaultMaterial 枚舉查詢完整工程參數 */
@OnlyIn(Dist.CLIENT)
public class MaterialConstantNode extends BRNode {
    public MaterialConstantNode() {
        super("Material Constant", "材料常數", "material", NodeColor.MATERIAL);
        addInput("materialType", "材料類型", PortType.ENUM, "CONCRETE");
        addOutput("material", PortType.MATERIAL);
        addOutput("rcomp", "抗壓強度", PortType.FLOAT);
        addOutput("rtens", "抗拉強度", PortType.FLOAT);
        addOutput("rshear", "抗剪強度", PortType.FLOAT);
        addOutput("density", "密度", PortType.FLOAT);
        addOutput("youngsModulus", "楊氏模量", PortType.FLOAT);
        addOutput("poissonsRatio", "泊松比", PortType.FLOAT);
        addOutput("yieldStrength", "降伏強度", PortType.FLOAT);
        addOutput("maxSpan", "最大跨距", PortType.INT);
    }

    @Override
    public void evaluate() {
        String typeStr = getInput("materialType").getValue();
        DefaultMaterial mat = DefaultMaterial.fromId(typeStr != null ? typeStr.toLowerCase() : "concrete");
        getOutput("material").setValue(mat);
        getOutput("rcomp").setValue((float) mat.getRcomp());
        getOutput("rtens").setValue((float) mat.getRtens());
        getOutput("rshear").setValue((float) mat.getRshear());
        getOutput("density").setValue((float) mat.getDensity());
        getOutput("youngsModulus").setValue((float) (mat.getYoungsModulusPa() / 1e9));
        getOutput("poissonsRatio").setValue((float) mat.getPoissonsRatio());
        getOutput("yieldStrength").setValue((float) mat.getYieldStrength());
        getOutput("maxSpan").setValue(mat.getMaxSpan());
    }

    @Override public String getTooltip() { return "從預設材料庫查詢完整工程參數（強度、密度、彈性模量等）"; }
    @Override public String typeId() { return "material.base.MaterialConstant"; }
}
