package com.blockreality.fastdesign.client.node.impl.render.postfx;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** A3-4: LUT 色彩分級 */
@OnlyIn(Dist.CLIENT)
public class ColorGradingLUTNode extends BRNode {
    public ColorGradingLUTNode() {
        super("ColorGradingLUT", "LUT 色彩分級", "render", NodeColor.RENDER);
        addInput("enabled", "啟用", PortType.BOOL, false);
        addInput("lutPath", "LUT 路徑", PortType.ENUM, "none");
        addInput("intensity", "強度", PortType.FLOAT, 1.0f).range(0f, 1.0f);
        addInput("blendMode", "混合模式", PortType.ENUM, "NORMAL");
        addOutput("result", PortType.BOOL);
    }

    @Override
    public void evaluate() {
        boolean enabled = getInput("enabled").getBool();
        float intensity = getInput("intensity").getFloat();

        // 強度限制在 [0, 1]
        if (intensity < 0f) {
            intensity = 0f;
        } else if (intensity > 1.0f) {
            intensity = 1.0f;
        }

        getOutput("result").setValue(enabled);
    }

    @Override public String getTooltip() { return "LUT 色彩分級，使用 3D LUT 查詢表進行色彩校正"; }
    @Override public String typeId() { return "color_grading_lut"; }
}
