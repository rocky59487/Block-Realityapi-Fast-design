package com.blockreality.fastdesign.client.node.impl.material.base;

import com.blockreality.api.material.DefaultMaterial;
import com.blockreality.fastdesign.client.node.*;

/** B1-3: 混凝土材料常數 */
public class ConcreteMaterialNode extends BRNode {
    private static final DefaultMaterial MAT = DefaultMaterial.CONCRETE;

    public ConcreteMaterialNode() {
        super("Concrete", "混凝土", "material", NodeColor.MATERIAL);
        addOutput("material", PortType.MATERIAL);
        addOutput("rcomp", "抗壓強度", PortType.FLOAT);
        addOutput("rtens", "抗拉強度", PortType.FLOAT);
        addOutput("density", "密度", PortType.FLOAT);
    }

    @Override
    public void evaluate() {
        getOutput("material").setValue(MAT);
        getOutput("rcomp").setValue((float) MAT.getRcomp());
        getOutput("rtens").setValue((float) MAT.getRtens());
        getOutput("density").setValue((float) MAT.getDensity());
    }

    @Override public String getTooltip() { return "C30 混凝土材料常數"; }
    @Override public String typeId() { return "material.base.ConcreteMaterial"; }
}
