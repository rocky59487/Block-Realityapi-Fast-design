package com.blockreality.fastdesign.client.node.impl.render.postfx;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** A3-4: 色差效果 */
@OnlyIn(Dist.CLIENT)
public class ChromaticAberrationNode extends BRNode {
    public ChromaticAberrationNode() {
        super("ChromaticAberration", "色差效果", "render", NodeColor.RENDER);
        addInput("enabled", "啟用", PortType.BOOL, false);
        addInput("strength", "強度", PortType.FLOAT, 0.004f).range(0f, 0.02f);
        addInput("radial", "徑向模式", PortType.BOOL, true);
        addOutput("result", PortType.BOOL);
    }

    @Override
    public void evaluate() {
        boolean enabled = getInput("enabled").getBool();
        float strength = getInput("strength").getFloat();

        // 強度限制在 [0, 0.02]
        if (strength < 0f) {
            strength = 0f;
        } else if (strength > 0.02f) {
            strength = 0.02f;
        }

        getOutput("result").setValue(enabled);
    }

    @Override public String getTooltip() { return "色差效果，模擬光學透鏡的色差現象"; }
    @Override public String typeId() { return "chromatic_aberration"; }
}
