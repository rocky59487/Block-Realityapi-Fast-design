package com.blockreality.fastdesign.client.node.impl.material.operation;

import com.blockreality.api.material.RMaterial;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import com.blockreality.fastdesign.client.node.*;

/** B2-6: 耐火等級 — 依溫度計算材料殘餘強度與耐火評級 */
@OnlyIn(Dist.CLIENT)
public class FireResistanceNode extends BRNode {
    public FireResistanceNode() {
        super("Fire Resistance", "耐火等級", "material", NodeColor.MATERIAL);
        addInput("material", "材料", PortType.MATERIAL, null);
        addInput("temperature", "溫度(°C)", PortType.FLOAT, 20.0f).range(20f, 1200f);
        addOutput("fireRating", "耐火等級", PortType.INT);
        addOutput("residualStrength", "殘餘強度%", PortType.FLOAT);
    }

    @Override
    public void evaluate() {
        RMaterial mat = getInput("material").getValue();
        if (mat == null) {
            getOutput("fireRating").setValue(0);
            getOutput("residualStrength").setValue(0f);
            return;
        }

        float temp = getInput("temperature").getFloat();

        // 殘餘強度：簡化模型
        // 20-300°C: 100%, 300-600°C: 線性降至 50%, 600-1200°C: 線性降至 0%
        float residual;
        if (temp <= 300f) {
            residual = 100f;
        } else if (temp <= 600f) {
            residual = 100f - (temp - 300f) / 300f * 50f;
        } else {
            residual = 50f - (temp - 600f) / 600f * 50f;
        }
        residual = Math.max(0f, residual);

        // 耐火等級: 1=最低(>800°C破壞), 4=最高(>1000°C仍存)
        // 基於材料抗壓強度和密度
        double baseScore = mat.getRcomp() * 0.5 + mat.getDensity() * 0.002;
        int rating;
        if (baseScore > 200) rating = 4;
        else if (baseScore > 100) rating = 3;
        else if (baseScore > 30) rating = 2;
        else rating = 1;

        // 高溫降低等級
        if (temp > 800) rating = Math.max(1, rating - 2);
        else if (temp > 500) rating = Math.max(1, rating - 1);

        getOutput("fireRating").setValue(rating);
        getOutput("residualStrength").setValue(residual);
    }

    @Override public String getTooltip() { return "依溫度計算材料殘餘強度百分比與耐火等級(1-4)"; }
    @Override public String typeId() { return "material.operation.FireResistance"; }
}
