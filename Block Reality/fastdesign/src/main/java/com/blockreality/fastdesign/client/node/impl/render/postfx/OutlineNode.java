package com.blockreality.fastdesign.client.node.impl.render.postfx;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** A3-4: 描邊效果 */
@OnlyIn(Dist.CLIENT)
public class OutlineNode extends BRNode {
    public OutlineNode() {
        super("Outline", "描邊效果", "render", NodeColor.RENDER);
        addInput("enabled", "啟用", PortType.BOOL, false);
        addInput("thickness", "邊框厚度", PortType.FLOAT, 1.0f).range(0.5f, 3.0f);
        addInput("color", "顏色", PortType.COLOR, 0xFF000000);
        addInput("depthSensitivity", "深度靈敏度", PortType.FLOAT, 0.5f).range(0f, 1.0f);
        addInput("normalSensitivity", "法線靈敏度", PortType.FLOAT, 0.5f).range(0f, 1.0f);
        addOutput("result", PortType.BOOL);
    }

    @Override
    public void evaluate() {
        boolean enabled = getInput("enabled").getBool();
        float thickness = getInput("thickness").getFloat();

        // 邊框厚度限制在 [0.5, 3.0]
        if (thickness < 0.5f) {
            thickness = 0.5f;
        } else if (thickness > 3.0f) {
            thickness = 3.0f;
        }

        getOutput("result").setValue(enabled);
    }

    @Override public String getTooltip() { return "描邊效果，基於深度和法線邊界檢測物體邊緣"; }
    @Override public String typeId() { return "outline"; }
}
