package com.blockreality.fastdesign.client.node.impl.physics.solver;

import com.blockreality.fastdesign.client.node.*;
import net.minecraft.nbt.CompoundTag;

/** C1-6: 空間分割並行 */
public class SpatialPartitionNode extends BRNode {
    public SpatialPartitionNode() {
        super("Spatial Partition", "空間分割並行", "physics", NodeColor.PHYSICS);
        addInput("threadCount", "執行緒數", PortType.INT, 4).range(1, 16);
        addInput("asyncMode", "非同步模式", PortType.BOOL, true);
        addInput("timeoutMs", "超時毫秒", PortType.INT, 500).range(100, 5000);
        addOutput("executorSpec", PortType.STRUCT);
        addOutput("activePartitions", PortType.INT);
        addOutput("avgPartitionTimeMs", PortType.FLOAT);
    }

    @Override
    public void evaluate() {
        CompoundTag spec = new CompoundTag();
        spec.putInt("threadCount", getInput("threadCount").getInt());
        spec.putBoolean("asyncMode", getInput("asyncMode").getBool());
        spec.putInt("timeoutMs", getInput("timeoutMs").getInt());
        getOutput("executorSpec").setValue(spec);
        getOutput("activePartitions").setValue(0);
        getOutput("avgPartitionTimeMs").setValue(0.0f);
    }

    @Override public String getTooltip() { return "空間分割並行物理計算執行器"; }
    @Override public String typeId() { return "physics.solver.SpatialPartition"; }
}
