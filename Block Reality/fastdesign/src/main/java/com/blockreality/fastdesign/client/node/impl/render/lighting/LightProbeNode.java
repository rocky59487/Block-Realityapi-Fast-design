package com.blockreality.fastdesign.client.node.impl.render.lighting;

import com.blockreality.fastdesign.client.node.*;

/** A4-7: 光照探針 */
public class LightProbeNode extends BRNode {
    public LightProbeNode() {
        super("LightProbe", "光照探針", "render", NodeColor.RENDER);
        addInput("enabled", "啟用", PortType.BOOL, false);
        addInput("resolution", "解析度", PortType.INT, 32).range(16, 128);
        addInput("updateInterval", "更新間隔", PortType.INT, 60).range(10, 300);
        addOutput("probeSpec", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        // probeSpec populated by downstream binder
    }

    @Override public String getTooltip() { return "光照探針解析度與更新間隔設定"; }
    @Override public String typeId() { return "render.lighting.LightProbe"; }
}
