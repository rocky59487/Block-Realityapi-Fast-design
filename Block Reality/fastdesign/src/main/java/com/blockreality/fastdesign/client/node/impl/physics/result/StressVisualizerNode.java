package com.blockreality.fastdesign.client.node.impl.physics.result;

import com.blockreality.fastdesign.client.node.*;

/** C3-1: 應力視覺化 */
public class StressVisualizerNode extends BRNode {
    public StressVisualizerNode() {
        super("Stress Visualizer", "應力視覺化", "physics", NodeColor.PHYSICS);
        addInput("stressField", "應力場", PortType.STRUCT, null);
        addInput("colorMap", "色彩映射", PortType.ENUM, "BlueRed");
        addInput("minStress", "最小應力", PortType.FLOAT, 0f);
        addInput("maxStress", "最大應力", PortType.FLOAT, 1f);
        addInput("showFailures", "顯示破壞", PortType.BOOL, true);
        addOutput("heatmapTexture", PortType.TEXTURE);
        addOutput("maxStressValue", PortType.FLOAT);
        addOutput("failureCount", PortType.INT);
    }

    @Override
    public void evaluate() {
        getOutput("heatmapTexture").setValue(0);
        getOutput("maxStressValue").setValue(getInput("maxStress").getFloat());
        getOutput("failureCount").setValue(0);
    }

    @Override public String getTooltip() { return "將應力場渲染為熱力圖紋理"; }
    @Override public String typeId() { return "physics.result.StressVisualizer"; }
}
