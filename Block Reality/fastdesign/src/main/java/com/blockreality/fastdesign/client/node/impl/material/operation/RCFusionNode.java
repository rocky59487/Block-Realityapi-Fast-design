package com.blockreality.fastdesign.client.node.impl.material.operation;

import com.blockreality.api.material.DynamicMaterial;
import com.blockreality.api.material.RMaterial;
import com.blockreality.fastdesign.client.node.*;

/** B2-1: 鋼筋混凝土融合 — 依公式計算 RC 融合材料 */
public class RCFusionNode extends BRNode {
    public RCFusionNode() {
        super("RC Fusion", "鋼筋混凝土融合", "material", NodeColor.MATERIAL);
        addInput("concrete", "混凝土", PortType.MATERIAL, null);
        addInput("rebar", "鋼筋", PortType.MATERIAL, null);
        addInput("phiTens", "抗拉融合係數", PortType.FLOAT, 0.8f).range(0f, 2f);
        addInput("phiShear", "抗剪融合係數", PortType.FLOAT, 0.6f).range(0f, 2f);
        addInput("compBoost", "抗壓增幅", PortType.FLOAT, 1.1f).range(1f, 3f);
        addInput("hasHoneycomb", "蜂窩空洞", PortType.BOOL, false);
        addInput("rebarSpacing", "鋼筋間距", PortType.INT, 4).range(1, 8);
        addOutput("rcMaterial", "RC 材料", PortType.MATERIAL);
        addOutput("rcRcomp", "RC 抗壓", PortType.FLOAT);
        addOutput("rcRtens", "RC 抗拉", PortType.FLOAT);
        addOutput("rcRshear", "RC 抗剪", PortType.FLOAT);
        addOutput("strengthGainPercent", "強度增益%", PortType.FLOAT);
    }

    @Override
    public void evaluate() {
        RMaterial concrete = getInput("concrete").getValue();
        RMaterial rebar = getInput("rebar").getValue();
        if (concrete == null || rebar == null) {
            getOutput("rcMaterial").setValue(null);
            getOutput("rcRcomp").setValue(0f);
            getOutput("rcRtens").setValue(0f);
            getOutput("rcRshear").setValue(0f);
            getOutput("strengthGainPercent").setValue(0f);
            return;
        }

        float phiTens = getInput("phiTens").getFloat();
        float phiShear = getInput("phiShear").getFloat();
        float compBoost = getInput("compBoost").getFloat();
        boolean honeycomb = getInput("hasHoneycomb").getBool();

        DynamicMaterial rc = DynamicMaterial.ofRCFusion(
                concrete, rebar, phiTens, phiShear, compBoost, honeycomb);

        getOutput("rcMaterial").setValue(rc);
        getOutput("rcRcomp").setValue((float) rc.getRcomp());
        getOutput("rcRtens").setValue((float) rc.getRtens());
        getOutput("rcRshear").setValue((float) rc.getRshear());

        double baseStrength = concrete.getCombinedStrength();
        double rcStrength = rc.getCombinedStrength();
        float gain = baseStrength > 0 ? (float) ((rcStrength - baseStrength) / baseStrength * 100.0) : 0f;
        getOutput("strengthGainPercent").setValue(gain);
    }

    @Override public String getTooltip() { return "將混凝土與鋼筋融合為 RC 材料，依 DynamicMaterial.ofRCFusion 公式計算"; }
    @Override public String typeId() { return "material.operation.RCFusion"; }
}
