package com.blockreality.fastdesign.client.node.impl.render.postfx;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;

/** A3-10: 色彩分級 */
@OnlyIn(Dist.CLIENT)
public class ColorGradingNode extends BRNode {
    public ColorGradingNode() {
        super("ColorGrading", "色彩分級", "render", NodeColor.RENDER);
        addInput("enabled", "啟用", PortType.BOOL, true);
        addInput("intensity", "強度", PortType.FLOAT, 0.85f).range(0f, 1f);
        addInput("temperature", "色溫", PortType.FLOAT, 6500f).range(2000f, 10000f);
        addInput("saturation", "飽和度", PortType.FLOAT, 1.05f).range(0f, 2f);
        addInput("contrast", "對比度", PortType.FLOAT, 1.05f).range(0.5f, 2f);
        addOutput("colorGradingEnabled", PortType.BOOL);
        addOutput("colorGradingIntensity", PortType.FLOAT);
        addOutput("colorGradingSaturation", PortType.FLOAT);
        addOutput("colorGradingContrast", PortType.FLOAT);
    }

    @Override
    public void evaluate() {
        getOutput("colorGradingEnabled").setValue(getInput("enabled").getBool());
        getOutput("colorGradingIntensity").setValue(getInput("intensity").getFloat());
        getOutput("colorGradingSaturation").setValue(getInput("saturation").getFloat());
        getOutput("colorGradingContrast").setValue(getInput("contrast").getFloat());
    }

    @Override public String getTooltip() { return "色彩分級，調整色溫、飽和度與對比度"; }
    @Override public String typeId() { return "render.postfx.ColorGrading"; }
}
