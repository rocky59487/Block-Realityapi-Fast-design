package com.blockreality.fastdesign.client.node.impl.physics.solver;

import com.blockreality.fastdesign.client.node.*;
import net.minecraft.nbt.CompoundTag;

/** C1-5: 物理精度分層 */
public class PhysicsLODNode extends BRNode {
    public PhysicsLODNode() {
        super("Physics LOD", "物理精度分層", "physics", NodeColor.PHYSICS);
        addInput("fullPrecisionDist", "全精度距離", PortType.INT, 32).range(8, 128);
        addInput("standardDist", "標準距離", PortType.INT, 96).range(32, 256);
        addInput("coarseDist", "粗略距離", PortType.INT, 256).range(96, 512);
        addOutput("physicsLodSpec", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        CompoundTag spec = new CompoundTag();
        spec.putInt("fullPrecisionDist", getInput("fullPrecisionDist").getInt());
        spec.putInt("standardDist", getInput("standardDist").getInt());
        spec.putInt("coarseDist", getInput("coarseDist").getInt());
        getOutput("physicsLodSpec").setValue(spec);
    }

    @Override public String getTooltip() { return "依距離分級物理模擬精度"; }
    @Override public String typeId() { return "physics.solver.PhysicsLOD"; }
}
