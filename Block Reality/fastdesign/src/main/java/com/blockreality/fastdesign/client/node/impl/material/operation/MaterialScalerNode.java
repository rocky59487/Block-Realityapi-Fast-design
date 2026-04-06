package com.blockreality.fastdesign.client.node.impl.material.operation;

import com.blockreality.api.material.DynamicMaterial;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import com.blockreality.api.material.RMaterial;
import com.blockreality.fastdesign.client.node.*;

/** B2-3: 材料縮放 — 對材料各參數獨立縮放 */
@OnlyIn(Dist.CLIENT)
public class MaterialScalerNode extends BRNode {
    public MaterialScalerNode() {
        super("Material Scaler", "材料縮放", "material", NodeColor.MATERIAL);
        addInput("material", "材料", PortType.MATERIAL, null);
        addInput("compScale", "抗壓縮放", PortType.FLOAT, 1.0f).range(0.1f, 10f);
        addInput("tensScale", "抗拉縮放", PortType.FLOAT, 1.0f).range(0.1f, 10f);
        addInput("shearScale", "抗剪縮放", PortType.FLOAT, 1.0f).range(0.1f, 10f);
        addInput("densityScale", "密度縮放", PortType.FLOAT, 1.0f).range(0.1f, 10f);
        addOutput("scaled", "縮放材料", PortType.MATERIAL);
    }

    @Override
    public void evaluate() {
        RMaterial mat = getInput("material").getValue();
        if (mat == null) {
            getOutput("scaled").setValue(null);
            return;
        }

        double rcomp   = mat.getRcomp()   * getInput("compScale").getFloat();
        double rtens   = mat.getRtens()   * getInput("tensScale").getFloat();
        double rshear  = mat.getRshear()  * getInput("shearScale").getFloat();
        double density = mat.getDensity() * getInput("densityScale").getFloat();

        DynamicMaterial scaled = DynamicMaterial.ofCustom(
                mat.getMaterialId() + "_scaled", rcomp, rtens, rshear, density);
        getOutput("scaled").setValue(scaled);
    }

    @Override public String getTooltip() { return "對材料的抗壓、抗拉、抗剪強度和密度進行獨立倍率縮放"; }
    @Override public String typeId() { return "material.operation.MaterialScaler"; }
}
