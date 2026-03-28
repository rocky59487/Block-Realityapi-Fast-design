package com.blockreality.fastdesign.client.node.impl.render.weather;

import com.blockreality.fastdesign.client.node.*;

/** A6-5: 降雪 */
public class SnowNode extends BRNode {
    public SnowNode() {
        super("Snow", "降雪", "render", NodeColor.RENDER);
        addInput("enabled", "啟用", PortType.BOOL, true);
        addInput("flakesPerTick", "每刻雪花數", PortType.INT, 32).range(0, 128);
        addInput("coverage", "覆蓋率", PortType.FLOAT, 0f).range(0f, 1f);
        addInput("meltRate", "融化速率", PortType.FLOAT, 0.01f).range(0f, 0.1f);
        addOutput("snowFlakesPerTick", PortType.INT);
        addOutput("snowSpec", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        getOutput("snowFlakesPerTick").setValue(getInput("flakesPerTick").getInt());
    }

    @Override public String getTooltip() { return "降雪雪花數、覆蓋率與融化速率"; }
    @Override public String typeId() { return "render.weather.Snow"; }
}
