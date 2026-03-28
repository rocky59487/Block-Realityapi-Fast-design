package com.blockreality.fastdesign.client.node.impl.output.monitor;

import com.blockreality.fastdesign.client.node.*;
import net.minecraft.nbt.CompoundTag;

/** E2-2: 逐 Pass 效能 */
public class PassProfilerNode extends BRNode {
    public PassProfilerNode() {
        super("Pass Profiler", "逐 Pass 效能", "output", NodeColor.OUTPUT);
        addOutput("passTimings", PortType.STRUCT);
        addOutput("bottleneckPass", PortType.ENUM);
    }

    @Override
    public void evaluate() {
        getOutput("passTimings").setValue(new CompoundTag());
        getOutput("bottleneckPass").setValue("None");
    }

    @Override public String getTooltip() { return "逐渲染 Pass 時間分析"; }
    @Override public String typeId() { return "output.monitor.PassProfiler"; }
}
