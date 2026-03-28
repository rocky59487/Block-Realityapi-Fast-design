package com.blockreality.fastdesign.client.node.impl.render.weather;

import com.blockreality.fastdesign.client.node.*;

/** A6-3: 體積霧 */
public class FogNode extends BRNode {
    public FogNode() {
        super("Fog", "體積霧", "render", NodeColor.RENDER);
        addInput("enabled", "啟用", PortType.BOOL, true);
        addInput("density", "密度", PortType.FLOAT, 0.002f).range(0f, 0.01f);
        addInput("heightFalloff", "高度衰減", PortType.FLOAT, 0.05f).range(0f, 0.2f);
        addInput("inscattering", "內散射", PortType.FLOAT, 0.3f).range(0f, 1f);
        addOutput("fogEnabled", PortType.BOOL);
        addOutput("fogDistanceDensity", PortType.FLOAT);
        addOutput("fogHeightFalloff", PortType.FLOAT);
    }

    @Override
    public void evaluate() {
        getOutput("fogEnabled").setValue(getInput("enabled").getBool());
        getOutput("fogDistanceDensity").setValue(getInput("density").getFloat());
        getOutput("fogHeightFalloff").setValue(getInput("heightFalloff").getFloat());
    }

    @Override public String getTooltip() { return "體積霧密度、高度衰減與內散射"; }
    @Override public String typeId() { return "render.weather.Fog"; }
}
