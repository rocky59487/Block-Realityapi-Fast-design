package com.blockreality.fastdesign.client.node.impl.render.weather;

import com.blockreality.fastdesign.client.node.*;

import java.util.LinkedHashMap;
import java.util.Map;

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
        boolean enabled    = getInput("enabled").getBool();
        int flakesPerTick  = getInput("flakesPerTick").getInt();
        float coverage     = getInput("coverage").getFloat();
        float meltRate     = getInput("meltRate").getFloat();

        getOutput("snowFlakesPerTick").setValue(enabled ? flakesPerTick : 0);

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("enabled",       enabled);
        spec.put("flakesPerTick", flakesPerTick);
        spec.put("coverage",      coverage);
        spec.put("meltRate",      meltRate);
        getOutput("snowSpec").setValue(spec);
    }

    @Override public String getTooltip() { return "降雪雪花數、覆蓋率與融化速率"; }
    @Override public String typeId() { return "render.weather.Snow"; }
}
