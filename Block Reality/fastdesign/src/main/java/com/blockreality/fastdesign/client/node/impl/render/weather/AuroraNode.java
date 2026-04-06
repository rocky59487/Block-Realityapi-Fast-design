package com.blockreality.fastdesign.client.node.impl.render.weather;

import com.blockreality.fastdesign.client.node.*;

import java.util.LinkedHashMap;
import java.util.Map;

/** A6-6: 極光 */
public class AuroraNode extends BRNode {
    public AuroraNode() {
        super("Aurora", "極光", "render", NodeColor.RENDER);
        addInput("enabled", "啟用", PortType.BOOL, false);
        addInput("intensity", "強度", PortType.FLOAT, 0.8f).range(0f, 2f);
        addInput("height", "高度", PortType.FLOAT, 200f).range(100f, 400f);
        addInput("waveSpeed", "波動速度", PortType.FLOAT, 0.5f).range(0f, 2f);
        addInput("color1", "顏色 1", PortType.COLOR, 0xFF00FF88);
        addInput("color2", "顏色 2", PortType.COLOR, 0xFF8800FF);
        addOutput("auroraHeight", PortType.FLOAT);
        addOutput("auroraSpec", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        boolean enabled   = getInput("enabled").getBool();
        float intensity   = getInput("intensity").getFloat();
        float height      = getInput("height").getFloat();
        float waveSpeed   = getInput("waveSpeed").getFloat();
        int color1        = getInput("color1").getInt();
        int color2        = getInput("color2").getInt();

        getOutput("auroraHeight").setValue(height);

        // auroraSpec bundles all parameters for downstream render pipeline consumption
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("enabled",   enabled);
        spec.put("intensity", intensity);
        spec.put("height",    height);
        spec.put("waveSpeed", waveSpeed);
        spec.put("color1",    color1);
        spec.put("color2",    color2);
        getOutput("auroraSpec").setValue(spec);
    }

    @Override public String getTooltip() { return "極光強度、高度、波動速度與雙色設定"; }
    @Override public String typeId() { return "render.weather.Aurora"; }
}
