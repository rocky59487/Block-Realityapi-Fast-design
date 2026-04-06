package com.blockreality.fastdesign.client.node.impl.material.operation;

import com.blockreality.api.material.DynamicMaterial;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import com.blockreality.api.material.RMaterial;
import com.blockreality.fastdesign.client.node.*;

/** B2-2: 材料混合 — 依權重混合兩種材料的工程參數 */
@OnlyIn(Dist.CLIENT)
public class MaterialMixerNode extends BRNode {
    public MaterialMixerNode() {
        super("Material Mixer", "材料混合", "material", NodeColor.MATERIAL);
        addInput("materialA", "材料 A", PortType.MATERIAL, null);
        addInput("materialB", "材料 B", PortType.MATERIAL, null);
        addInput("ratio", "比例(A:B)", PortType.FLOAT, 0.5f).range(0f, 1f);
        addInput("mixMode", "混合模式", PortType.ENUM, "WeightedAvg");
        addOutput("mixed", "混合材料", PortType.MATERIAL);
    }

    @Override
    public void evaluate() {
        RMaterial a = getInput("materialA").getValue();
        RMaterial b = getInput("materialB").getValue();
        if (a == null || b == null) {
            getOutput("mixed").setValue(null);
            return;
        }

        float r = getInput("ratio").getFloat();
        float rb = 1.0f - r;

        double rcomp   = a.getRcomp()   * r + b.getRcomp()   * rb;
        double rtens   = a.getRtens()   * r + b.getRtens()   * rb;
        double rshear  = a.getRshear()  * r + b.getRshear()  * rb;
        double density = a.getDensity() * r + b.getDensity() * rb;

        DynamicMaterial mixed = DynamicMaterial.ofCustom("mixed", rcomp, rtens, rshear, density);
        getOutput("mixed").setValue(mixed);
    }

    @Override public String getTooltip() { return "依權重比例混合兩種材料的所有工程參數"; }
    @Override public String typeId() { return "material.operation.MaterialMixer"; }
}
