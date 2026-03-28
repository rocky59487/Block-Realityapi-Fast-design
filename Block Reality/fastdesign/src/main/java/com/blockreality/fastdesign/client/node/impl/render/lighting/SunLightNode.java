package com.blockreality.fastdesign.client.node.impl.render.lighting;

import com.blockreality.fastdesign.client.node.*;

/** A4-1: 主光源 */
public class SunLightNode extends BRNode {
    public SunLightNode() {
        super("SunLight", "主光源", "render", NodeColor.RENDER);
        addInput("angle", "角度", PortType.FLOAT, 0.0f).range(0f, 360f);
        addInput("color", "顏色", PortType.COLOR, 0xFFFFEEDD);
        addInput("intensity", "強度", PortType.FLOAT, 1.0f).range(0f, 5f);
        addInput("shadowBias", "陰影偏移", PortType.FLOAT, 0.001f).range(0.0001f, 0.01f);
        addOutput("lightDir", PortType.VEC3);
        addOutput("lightColor", PortType.VEC3);
        addOutput("shadowMatrix", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        float angle = getInput("angle").getFloat();
        double rad = Math.toRadians(angle);
        float[] dir = new float[]{(float) Math.cos(rad), (float) -Math.sin(rad), 0.0f};
        getOutput("lightDir").setValue(dir);

        int c = getInput("color").getColor();
        float intensity = getInput("intensity").getFloat();
        float r = ((c >> 16) & 0xFF) / 255.0f * intensity;
        float g = ((c >> 8) & 0xFF) / 255.0f * intensity;
        float b = (c & 0xFF) / 255.0f * intensity;
        getOutput("lightColor").setValue(new float[]{r, g, b});
    }

    @Override public String getTooltip() { return "太陽主光源方向、顏色與陰影偏移"; }
    @Override public String typeId() { return "render.lighting.SunLight"; }
}
