package com.blockreality.fastdesign.client.node.impl.output.monitor;

import com.blockreality.fastdesign.client.node.*;

/** E2-1: GPU 效能 */
public class GPUProfilerNode extends BRNode {
    public GPUProfilerNode() {
        super("GPU Profiler", "GPU 效能", "output", NodeColor.OUTPUT);
        addOutput("frameTimeMs", PortType.FLOAT);
        addOutput("gpuTimeMs", PortType.FLOAT);
        addOutput("drawCalls", PortType.INT);
        addOutput("triangles", PortType.INT);
        addOutput("vramUsedMB", PortType.FLOAT);
        addOutput("fpsHistory", PortType.CURVE);
    }

    @Override
    public void evaluate() {
        getOutput("frameTimeMs").setValue(0.0f);
        getOutput("gpuTimeMs").setValue(0.0f);
        getOutput("drawCalls").setValue(0);
        getOutput("triangles").setValue(0);
        getOutput("vramUsedMB").setValue(0.0f);
        getOutput("fpsHistory").setValue(new float[0]);
    }

    @Override public String getTooltip() { return "GPU 幀時間、繪製呼叫、VRAM 監控"; }
    @Override public String typeId() { return "output.monitor.GPUProfiler"; }
}
