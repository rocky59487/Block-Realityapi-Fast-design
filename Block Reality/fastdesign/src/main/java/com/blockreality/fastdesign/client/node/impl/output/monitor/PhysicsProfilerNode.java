package com.blockreality.fastdesign.client.node.impl.output.monitor;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;

/** E2-3: 物理效能 */
@OnlyIn(Dist.CLIENT)
public class PhysicsProfilerNode extends BRNode {
    public PhysicsProfilerNode() {
        super("Physics Profiler", "物理效能", "output", NodeColor.OUTPUT);
        addOutput("physicsTimeMs", PortType.FLOAT);
        addOutput("solverIterations", PortType.INT);
    }

    @Override
    public void evaluate() {
        getOutput("physicsTimeMs").setValue(0.0f);
        getOutput("solverIterations").setValue(0);
    }

    @Override public String getTooltip() { return "物理求解器時間與迭代次數監控"; }
    @Override public String typeId() { return "output.monitor.PhysicsProfiler"; }
}
