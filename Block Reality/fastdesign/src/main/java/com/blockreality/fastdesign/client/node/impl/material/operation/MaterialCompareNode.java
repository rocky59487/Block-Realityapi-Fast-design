package com.blockreality.fastdesign.client.node.impl.material.operation;

import com.blockreality.api.material.RMaterial;
import com.blockreality.fastdesign.client.node.*;

/** B2-7: 材料比較 — 計算兩種材料各參數的差值 */
public class MaterialCompareNode extends BRNode {
    public MaterialCompareNode() {
        super("Material Compare", "材料比較", "material", NodeColor.MATERIAL);
        addInput("materialA", "材料 A", PortType.MATERIAL, null);
        addInput("materialB", "材料 B", PortType.MATERIAL, null);
        addOutput("compDiff", "抗壓差", PortType.FLOAT);
        addOutput("tensDiff", "抗拉差", PortType.FLOAT);
        addOutput("densityDiff", "密度差", PortType.FLOAT);
    }

    @Override
    public void evaluate() {
        RMaterial a = getInput("materialA").getValue();
        RMaterial b = getInput("materialB").getValue();
        if (a == null || b == null) {
            getOutput("compDiff").setValue(0f);
            getOutput("tensDiff").setValue(0f);
            getOutput("densityDiff").setValue(0f);
            return;
        }

        getOutput("compDiff").setValue((float) (a.getRcomp() - b.getRcomp()));
        getOutput("tensDiff").setValue((float) (a.getRtens() - b.getRtens()));
        getOutput("densityDiff").setValue((float) (a.getDensity() - b.getDensity()));
    }

    @Override public String getTooltip() { return "比較兩種材料的抗壓、抗拉強度和密度差值"; }
    @Override public String typeId() { return "material.operation.MaterialCompare"; }
}
