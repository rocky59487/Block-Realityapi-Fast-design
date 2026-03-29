package com.blockreality.fastdesign.client.node.impl.render.weather;

import com.blockreality.fastdesign.client.node.*;

/** A6-1: 大氣散射 */
public class AtmosphereNode extends BRNode {
    public AtmosphereNode() {
        super("Atmosphere", "大氣散射", "render", NodeColor.RENDER);
        addInput("enabled", "啟用", PortType.BOOL, true);
        addInput("rayleighScale", "瑞利係數", PortType.FLOAT, 1.0f).range(0f, 3f);
        addInput("mieScale", "米氏係數", PortType.FLOAT, 1.0f).range(0f, 3f);
        addInput("sunAngle", "太陽角度", PortType.FLOAT, 45f).range(0f, 180f);
        addInput("turbidity", "濁度", PortType.FLOAT, 2f).range(1f, 10f);
        addInput("sunIntensity", "太陽強度", PortType.FLOAT, 20f).range(1f, 100f);
        addOutput("atmosphereEnabled", PortType.BOOL);
        addOutput("atmosphereSunIntensity", PortType.FLOAT);
        addOutput("atmosphereSpec", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        getOutput("atmosphereEnabled").setValue(getInput("enabled").getBool());
        getOutput("atmosphereSunIntensity").setValue(getInput("sunIntensity").getFloat());
    }

    @Override public String getTooltip() { return "大氣散射瑞利/米氏係數、濁度與太陽強度"; }
    @Override public String typeId() { return "render.weather.Atmosphere"; }
}
