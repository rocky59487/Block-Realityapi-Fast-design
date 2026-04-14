package com.blockreality.fastdesign.client.node.impl.render.water;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;

/** A7-3: 泡沫 */
@OnlyIn(Dist.CLIENT)
public class WaterFoamNode extends BRNode {
    public WaterFoamNode() {
        super("WaterFoam", "泡沫", "render", NodeColor.RENDER);
        addInput("threshold", "閾值", PortType.FLOAT, 0.8f).range(0f, 1f);
        addInput("fadeSpeed", "消退速度", PortType.FLOAT, 0.5f).range(0f, 2f);
        addInput("color", "顏色", PortType.COLOR, 0xFFEEEEFF);
        // ★ PFSF-Fluid: 湍流驅動泡沫生成
        addInput("fluidTurbulence", "物理湍流", PortType.FLOAT, 0f).range(0f, 5f);
        addOutput("waterFoamThreshold", PortType.FLOAT);
        addOutput("foamSpec", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        float baseThr = getInput("threshold").getFloat();
        float turbulence = getInput("fluidTurbulence").getFloat();
        // 高渦度 → 泡沫閾值降低（更容易起泡）
        float effectiveThr = baseThr / (1.0f + turbulence * 2.0f);
        getOutput("waterFoamThreshold").setValue(effectiveThr);
    }

    @Override public String getTooltip() { return "水面泡沫閾值、消退速度與顏色"; }
    @Override public String typeId() { return "render.water.WaterFoam"; }
}
