package com.blockreality.fastdesign.client.node.impl.material.operation;

import com.blockreality.api.material.DynamicMaterial;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import com.blockreality.api.material.RMaterial;
import com.blockreality.fastdesign.client.node.*;

/** B2-4: 養護過程 — 模擬混凝土養護的強度增長曲線 */
@OnlyIn(Dist.CLIENT)
public class CuringProcessNode extends BRNode {
    public CuringProcessNode() {
        super("Curing Process", "養護過程", "material", NodeColor.MATERIAL);
        addInput("baseMaterial", "基礎材料", PortType.MATERIAL, null);
        addInput("curingTicks", "養護總時間", PortType.INT, 2400).range(0, 72000);
        addInput("currentTick", "當前時刻", PortType.INT, 0).range(0, 72000);
        addInput("ambientTemp", "環境溫度", PortType.FLOAT, 25.0f).range(0f, 50f);
        addOutput("curedMaterial", "養護材料", PortType.MATERIAL);
        addOutput("curingProgress", "養護進度", PortType.FLOAT);
        addOutput("strengthPercent", "強度百分比", PortType.FLOAT);
    }

    @Override
    public void evaluate() {
        RMaterial base = getInput("baseMaterial").getValue();
        if (base == null) {
            getOutput("curedMaterial").setValue(null);
            getOutput("curingProgress").setValue(0f);
            getOutput("strengthPercent").setValue(0f);
            return;
        }

        int totalTicks = getInput("curingTicks").getInt();
        int current = getInput("currentTick").getInt();
        float temp = getInput("ambientTemp").getFloat();

        // 養護進度 0~1
        float progress = totalTicks > 0 ? Math.min(1.0f, (float) current / totalTicks) : 1.0f;

        // 對數曲線: strength% = ln(1 + progress*e) / ln(1+e) * tempFactor
        // 溫度因子: 最佳 20-25C, 偏離則降低
        float tempFactor = 1.0f - Math.abs(temp - 22.5f) / 50.0f;
        tempFactor = Math.max(0.5f, Math.min(1.0f, tempFactor));
        float strengthPct = (float) (Math.log(1.0 + progress * Math.E) / Math.log(1.0 + Math.E)) * tempFactor;

        double rcomp   = base.getRcomp()   * strengthPct;
        double rtens   = base.getRtens()   * strengthPct;
        double rshear  = base.getRshear()  * strengthPct;
        double density = base.getDensity();

        DynamicMaterial cured = DynamicMaterial.ofCustom(
                base.getMaterialId() + "_cured", rcomp, rtens, rshear, density);

        getOutput("curedMaterial").setValue(cured);
        getOutput("curingProgress").setValue(progress);
        getOutput("strengthPercent").setValue(strengthPct * 100.0f);
    }

    @Override public String getTooltip() { return "模擬混凝土養護過程，使用對數曲線計算強度隨時間增長"; }
    @Override public String typeId() { return "material.operation.CuringProcess"; }
}
