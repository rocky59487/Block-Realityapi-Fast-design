package com.blockreality.fastdesign.client.node.impl.render.water;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;

/** A7-1: 水面 */
@OnlyIn(Dist.CLIENT)
public class WaterSurfaceNode extends BRNode {
    public WaterSurfaceNode() {
        super("WaterSurface", "水面", "render", NodeColor.RENDER);
        addInput("enabled", "啟用", PortType.BOOL, true);
        addInput("level", "水面高度", PortType.FLOAT, 63f).range(0f, 320f);
        addInput("reflectionScale", "反射倍率", PortType.FLOAT, 0.5f).range(0.25f, 1f);
        addInput("waveCount", "波浪數", PortType.INT, 4).range(1, 8);
        addInput("waveAmplitude", "波幅", PortType.FLOAT, 0.3f).range(0f, 1f);
        // ★ PFSF-Fluid: 物理驅動水面高度（覆蓋靜態 level 值）
        addInput("fluidLevel", "物理水面高度", PortType.FLOAT, -1f).range(-1f, 320f);
        addOutput("waterEnabled", PortType.BOOL);
        addOutput("waterReflectionScale", PortType.FLOAT);
        addOutput("waterWaveCount", PortType.INT);
        addOutput("waterSpec", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        getOutput("waterEnabled").setValue(getInput("enabled").getBool());
        getOutput("waterReflectionScale").setValue(getInput("reflectionScale").getFloat());
        getOutput("waterWaveCount").setValue(getInput("waveCount").getInt());
    }

    @Override public String getTooltip() { return "水面高度、反射倍率、波浪數與波幅"; }
    @Override public String typeId() { return "render.water.WaterSurface"; }
}
