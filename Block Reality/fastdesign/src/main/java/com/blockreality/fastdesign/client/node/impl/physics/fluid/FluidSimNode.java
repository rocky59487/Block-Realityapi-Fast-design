package com.blockreality.fastdesign.client.node.impl.physics.fluid;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;

/**
 * 流體模擬控制節點 — 啟用/停用流體、設定區域和擴散參數。
 *
 * <p>輸出 fluidEnabled 和相關參數，由 FluidBinder 綁定到
 * BRConfig 和 FluidGPUEngine 的運行時設定。
 */
@OnlyIn(Dist.CLIENT)
public class FluidSimNode extends BRNode {

    public FluidSimNode() {
        super("FluidSim", "流體模擬", "physics", NodeColor.PHYSICS);
        addInput("enabled", "啟用", PortType.BOOL, false);
        addInput("regionSize", "區域大小", PortType.INT, 64).range(16, 128);
        addInput("diffusionRate", "擴散率", PortType.FLOAT, 0.25f).range(0.05f, 0.45f);
        addInput("iterationsPerTick", "每Tick迭代", PortType.INT, 4).range(1, 8);
        addInput("tickBudgetMs", "Tick預算(ms)", PortType.INT, 4).range(1, 15);
        addOutput("fluidEnabled", PortType.BOOL);
        addOutput("fluidConfig", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        boolean enabled = getInput("enabled").getBool();
        getOutput("fluidEnabled").setValue(enabled);
        // fluidConfig struct 由 FluidBinder 處理
    }

    @Override public String getTooltip() { return "PFSF-Fluid 流體模擬系統控制"; }
    @Override public String typeId() { return "physics.fluid.FluidSim"; }
}
