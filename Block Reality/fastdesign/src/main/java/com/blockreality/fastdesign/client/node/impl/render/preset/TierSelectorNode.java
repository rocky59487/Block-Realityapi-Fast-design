package com.blockreality.fastdesign.client.node.impl.render.preset;

import com.blockreality.fastdesign.client.node.*;

/** A1-3: 渲染 Tier 選擇 — 設計報告 §5 A1-3 */
public class TierSelectorNode extends BRNode {
    public TierSelectorNode() {
        super("TierSelector", "渲染 Tier", "render", NodeColor.RENDER);
        addInput("tier", "渲染 Tier", PortType.ENUM, "Tier1_Quality");
        addOutput("glVersion", PortType.INT);
        addOutput("featureFlags", PortType.INT);
        addOutput("shaderComplexity", PortType.ENUM);
        addOutput("maxTextureUnits", PortType.INT);
    }

    @Override
    public void evaluate() {
        String tier = String.valueOf(getInput("tier").getRawValue());
        switch (tier) {
            case "Tier0_Compat"  -> set(33, 0x01, "LOW", 8);
            case "Tier1_Quality" -> set(43, 0x0F, "MEDIUM", 16);
            case "Tier2_Ultra"   -> set(45, 0x3F, "HIGH", 32);
            case "Tier3_RT"      -> set(46, 0xFF, "ULTRA", 32);
            default              -> set(43, 0x0F, "MEDIUM", 16);
        }
    }

    private void set(int gl, int flags, String complexity, int texUnits) {
        getOutput("glVersion").setValue(gl);
        getOutput("featureFlags").setValue(flags);
        getOutput("shaderComplexity").setValue(complexity);
        getOutput("maxTextureUnits").setValue(texUnits);
    }

    @Override public String getTooltip() { return "選擇渲染 Tier（Compat/Quality/Ultra/RT）"; }
    @Override public String typeId() { return "render.preset.TierSelector"; }
}
