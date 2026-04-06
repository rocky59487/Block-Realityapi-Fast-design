package com.blockreality.fastdesign.client.node.impl.material.operation;

import com.blockreality.api.material.DynamicMaterial;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import com.blockreality.api.material.RMaterial;
import com.blockreality.fastdesign.client.node.*;

/** B2-5: 風化劣化 — 模擬材料長期風化的強度衰減 */
@OnlyIn(Dist.CLIENT)
public class WeatherDegradationNode extends BRNode {
    public WeatherDegradationNode() {
        super("Weather Degradation", "風化劣化", "material", NodeColor.MATERIAL);
        addInput("material", "材料", PortType.MATERIAL, null);
        addInput("degradation", "劣化程度", PortType.FLOAT, 0.0f).range(0f, 1f);
        addOutput("degraded", "劣化材料", PortType.MATERIAL);
    }

    @Override
    public void evaluate() {
        RMaterial mat = getInput("material").getValue();
        if (mat == null) {
            getOutput("degraded").setValue(null);
            return;
        }

        float deg = getInput("degradation").getFloat();
        float factor = 1.0f - deg;

        double rcomp   = mat.getRcomp()   * factor;
        double rtens   = mat.getRtens()   * factor;
        double rshear  = mat.getRshear()  * factor;
        double density = mat.getDensity() * (1.0 - deg * 0.1); // 風化略微降低密度

        DynamicMaterial degraded = DynamicMaterial.ofCustom(
                mat.getMaterialId() + "_degraded", rcomp, rtens, rshear, Math.max(100, density));
        getOutput("degraded").setValue(degraded);
    }

    @Override public String getTooltip() { return "模擬材料風化劣化，依劣化程度線性降低強度參數"; }
    @Override public String typeId() { return "material.operation.WeatherDegradation"; }
}
