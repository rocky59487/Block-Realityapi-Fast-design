package com.blockreality.fastdesign.client.node.impl.material.base;

import com.blockreality.api.material.DefaultMaterial;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import com.blockreality.fastdesign.client.node.*;

/** B1-13: 鋼筋混凝土節點材料常數 */
@OnlyIn(Dist.CLIENT)
public class RCNodeMaterialNode extends BRNode {
    private static final DefaultMaterial MAT = DefaultMaterial.RC_NODE;

    public RCNodeMaterialNode() {
        super("RC Node", "鋼筋混凝土節點", "material", NodeColor.MATERIAL);
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

    @Override public String getTooltip() { return "RC 融合節點預設材料常數"; }
    @Override public String typeId() { return "material.base.RCNodeMaterial"; }
}
