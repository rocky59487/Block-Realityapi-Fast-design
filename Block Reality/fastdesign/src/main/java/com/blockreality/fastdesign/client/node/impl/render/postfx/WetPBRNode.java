package com.blockreality.fastdesign.client.node.impl.render.postfx;

import com.blockreality.fastdesign.client.node.*;

/** A3-18: 濕潤 PBR */
public class WetPBRNode extends BRNode {
    public WetPBRNode() {
        super("WetPBR", "濕潤 PBR", "render", NodeColor.RENDER);
        addInput("enabled", "啟用", PortType.BOOL, true);
        addInput("wetness", "濕潤度", PortType.FLOAT, 0f).range(0f, 1f);
        addInput("snowCoverage", "積雪覆蓋", PortType.FLOAT, 0f).range(0f, 1f);
        addInput("puddleIntensity", "水坑強度", PortType.FLOAT, 0.5f).range(0f, 1f);
        addOutput("wetPBRSpec", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        net.minecraft.nbt.CompoundTag spec = new net.minecraft.nbt.CompoundTag();
        spec.putBoolean("enabled", getInput("enabled").getBool());
        spec.putFloat("wetness", getInput("wetness").getFloat());
        spec.putFloat("snowCoverage", getInput("snowCoverage").getFloat());
        spec.putFloat("puddleIntensity", getInput("puddleIntensity").getFloat());
        getOutput("wetPBRSpec").setValue(spec);
    }

    @Override public String getTooltip() { return "濕潤 PBR，模擬雨水、積雪與水坑對材質的影響"; }
    @Override public String typeId() { return "render.postfx.WetPBR"; }
}
