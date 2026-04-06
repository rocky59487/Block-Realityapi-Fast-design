package com.blockreality.fastdesign.client.node.impl.physics.fluid;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;

/**
 * 流體壓力耦合節點 — 配置流體壓力與結構引擎的耦合參數。
 *
 * <p>控制壓力耦合強度和閾值，影響水壓何時及如何
 * 被注入 PFSF 結構引擎的 source term。
 */
@OnlyIn(Dist.CLIENT)
public class FluidPressureNode extends BRNode {

    public FluidPressureNode() {
        super("FluidPressure", "流體壓力", "physics", NodeColor.PHYSICS);
        addInput("couplingFactor", "耦合係數", PortType.FLOAT, 1.0f).range(0f, 5f);
        addInput("minPressure", "最小壓力(Pa)", PortType.FLOAT, 100f).range(0f, 10000f);
        addInput("damBreachThreshold", "潰壩閾值(Pa)", PortType.FLOAT, 50000f).range(1000f, 500000f);
        addOutput("pressureConfig", PortType.STRUCT);
        addOutput("couplingActive", PortType.BOOL);
    }

    @Override
    public void evaluate() {
        float factor = getInput("couplingFactor").getFloat();
        getOutput("couplingActive").setValue(factor > 0f);
    }

    @Override public String getTooltip() { return "流體壓力→結構引擎耦合配置"; }
    @Override public String typeId() { return "physics.fluid.FluidPressure"; }
}
