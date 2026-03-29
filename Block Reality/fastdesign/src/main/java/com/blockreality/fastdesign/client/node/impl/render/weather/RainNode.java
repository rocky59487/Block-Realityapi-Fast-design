package com.blockreality.fastdesign.client.node.impl.render.weather;

import com.blockreality.fastdesign.client.node.*;

/** A6-4: 降雨 */
public class RainNode extends BRNode {
    public RainNode() {
        super("Rain", "降雨", "render", NodeColor.RENDER);
        addInput("enabled", "啟用", PortType.BOOL, true);
        addInput("dropsPerTick", "每刻雨滴數", PortType.INT, 64).range(0, 256);
        addInput("puddleIntensity", "水坑強度", PortType.FLOAT, 0.5f).range(0f, 1f);
        addInput("splashSize", "濺射大小", PortType.FLOAT, 0.3f).range(0.1f, 1f);
        addOutput("rainDropsPerTick", PortType.INT);
        addOutput("rainSpec", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        getOutput("rainDropsPerTick").setValue(getInput("dropsPerTick").getInt());
    }

    @Override public String getTooltip() { return "降雨雨滴數、水坑強度與濺射大小"; }
    @Override public String typeId() { return "render.weather.Rain"; }
}
