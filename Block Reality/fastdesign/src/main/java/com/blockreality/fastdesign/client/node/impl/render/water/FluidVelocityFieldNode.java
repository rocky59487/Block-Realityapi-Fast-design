package com.blockreality.fastdesign.client.node.impl.render.water;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;

/**
 * 流體速度場可視化節點 — 將物理引擎的速度場驅動渲染效果。
 *
 * <p>從 FluidRenderBridge 讀取 GPU 計算的速度場，
 * 輸出速度大小和湍流值供 WaterCaustics/WaterFoam 節點使用。
 */
@OnlyIn(Dist.CLIENT)
public class FluidVelocityFieldNode extends BRNode {

    public FluidVelocityFieldNode() {
        super("FluidVelocityField", "流體速度場", "render", NodeColor.RENDER);
        addInput("enabled", "啟用", PortType.BOOL, true);
        addInput("visualScale", "視覺倍率", PortType.FLOAT, 1.0f).range(0.1f, 5f);
        addInput("arrowDensity", "箭頭密度", PortType.FLOAT, 0.5f).range(0.1f, 2f);
        addOutput("velocityMagnitude", PortType.FLOAT);
        addOutput("turbulence", PortType.FLOAT);
        addOutput("flowDirection", PortType.VEC3);
        addOutput("velocitySpec", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        boolean enabled = getInput("enabled").getBool();
        if (!enabled) {
            getOutput("velocityMagnitude").setValue(0f);
            getOutput("turbulence").setValue(0f);
            getOutput("flowDirection").setValue(new float[]{0f, 0f, 0f});
            return;
        }
        // 實際值由 FluidBinder 從 FluidRenderBridge 拉取
    }

    @Override public String getTooltip() { return "流體速度場可視化（驅動水面渲染效果）"; }
    @Override public String typeId() { return "render.water.FluidVelocityField"; }
}
