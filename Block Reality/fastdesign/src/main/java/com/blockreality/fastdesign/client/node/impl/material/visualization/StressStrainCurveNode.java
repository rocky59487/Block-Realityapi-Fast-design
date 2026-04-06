package com.blockreality.fastdesign.client.node.impl.material.visualization;

import com.blockreality.api.material.RMaterial;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import com.blockreality.fastdesign.client.node.*;

/** B4-2: 應力應變曲線 — 生成材料的簡化應力-應變曲線資料 */
@OnlyIn(Dist.CLIENT)
public class StressStrainCurveNode extends BRNode {
    public StressStrainCurveNode() {
        super("Stress-Strain Curve", "應力應變曲線", "material", NodeColor.MATERIAL);
        addInput("material", "材料", PortType.MATERIAL, null);
        addOutput("curveData", "曲線資料", PortType.CURVE);
    }

    @Override
    public void evaluate() {
        RMaterial mat = getInput("material").getValue();
        if (mat == null) {
            getOutput("curveData").setValue(new float[0]);
            return;
        }

        // 生成簡化應力-應變曲線（10 個取樣點）
        // 格式: [strain0, stress0, strain1, stress1, ...]
        double E = mat.getYoungsModulusPa();
        double fy = mat.getYieldStrength();       // MPa
        double ultimateStress = mat.getRcomp();   // MPa
        boolean ductile = mat.isDuctile();

        // 降伏應變 εy = fy / E (E in Pa, fy in MPa → convert)
        double yieldStrain = (fy * 1e6) / E;
        if (yieldStrain <= 0 || Double.isInfinite(yieldStrain)) yieldStrain = 0.002;

        float[] curve;
        if (ductile) {
            // 延性材料: 線性-屈服平台-硬化-斷裂
            curve = new float[]{
                0f, 0f,
                (float) (yieldStrain * 0.5), (float) (fy * 0.5f),
                (float) yieldStrain, (float) fy,
                (float) (yieldStrain * 5), (float) fy,
                (float) (yieldStrain * 10), (float) (ultimateStress),
                (float) (yieldStrain * 15), (float) (ultimateStress * 0.8),
                (float) (yieldStrain * 20), 0f
            };
        } else {
            // 脆性材料: 線性-峰值-急劇下降
            curve = new float[]{
                0f, 0f,
                (float) (yieldStrain * 0.25), (float) (ultimateStress * 0.25f),
                (float) (yieldStrain * 0.5), (float) (ultimateStress * 0.5f),
                (float) (yieldStrain * 0.75), (float) (ultimateStress * 0.8f),
                (float) yieldStrain, (float) ultimateStress,
                (float) (yieldStrain * 1.2), (float) (ultimateStress * 0.3f),
                (float) (yieldStrain * 1.5), 0f
            };
        }

        getOutput("curveData").setValue(curve);
    }

    @Override public String getTooltip() { return "根據材料楊氏模量和降伏強度生成簡化應力-應變曲線"; }
    @Override public String typeId() { return "material.visualization.StressStrainCurve"; }
}
