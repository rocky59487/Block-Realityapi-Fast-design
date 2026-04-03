package com.blockreality.fastdesign.client.node.impl.render.postfx;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** A3-4: 暈影 */
@OnlyIn(Dist.CLIENT)
public class VignetteNode extends BRNode {
    public VignetteNode() {
        super("Vignette", "暈影", "render", NodeColor.RENDER);
        addInput("enabled", "啟用", PortType.BOOL, false);
        addInput("radius", "半徑", PortType.FLOAT, 0.75f).range(0.3f, 1.0f);
        addInput("softness", "柔和度", PortType.FLOAT, 0.45f).range(0.1f, 1.0f);
        addInput("color", "顏色", PortType.COLOR, 0xFF000000);
        addOutput("result", PortType.BOOL);
    }

    @Override
    public void evaluate() {
        boolean enabled = getInput("enabled").getBool();
        float radius = getInput("radius").getFloat();

        // 半徑限制在 [0.3, 1.0]
        if (radius < 0.3f) {
            radius = 0.3f;
        } else if (radius > 1.0f) {
            radius = 1.0f;
        }

        getOutput("result").setValue(enabled);
    }

    @Override public String getTooltip() { return "暈影效果，向邊緣淡出至黑色"; }
    @Override public String typeId() { return "vignette"; }
}
