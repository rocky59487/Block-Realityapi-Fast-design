package com.blockreality.fastdesign.client.node.impl.output.monitor;

import com.blockreality.fastdesign.client.node.*;

/** E2-4: 記憶體監控 */
public class MemoryProfilerNode extends BRNode {
    public MemoryProfilerNode() {
        super("Memory Profiler", "記憶體監控", "output", NodeColor.OUTPUT);
        addOutput("ramUsedMB", PortType.FLOAT);
        addOutput("vramUsedMB", PortType.FLOAT);
        addOutput("heapUsedMB", PortType.FLOAT);
    }

    @Override
    public void evaluate() {
        Runtime rt = Runtime.getRuntime();
        float heapUsed = (rt.totalMemory() - rt.freeMemory()) / (1024f * 1024f);
        getOutput("ramUsedMB").setValue(0.0f);
        getOutput("vramUsedMB").setValue(0.0f);
        getOutput("heapUsedMB").setValue(heapUsed);
    }

    @Override public String getTooltip() { return "RAM、VRAM、Heap 記憶體使用量監控"; }
    @Override public String typeId() { return "output.monitor.MemoryProfiler"; }
}
