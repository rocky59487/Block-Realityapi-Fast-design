package com.blockreality.fastdesign.client.node.impl.render.postfx;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** A3-4: 膠片顆粒 */
@OnlyIn(Dist.CLIENT)
public class FilmGrainNode extends BRNode {
    public FilmGrainNode() {
        super("FilmGrain", "膠片顆粒", "render", NodeColor.RENDER);
        addInput("enabled", "啟用", PortType.BOOL, true);
        addInput("strength", "強度", PortType.FLOAT, 0.05f).range(0f, 0.3f);
        addInput("animated", "動畫效果", PortType.BOOL, true);
        addInput("colorNoise", "彩色噪點", PortType.BOOL, false);
        addOutput("result", PortType.BOOL);
    }

    @Override
    public void evaluate() {
        boolean enabled = getInput("enabled").getBool();
        float strength = getInput("strength").getFloat();

        // 強度限制在 [0, 0.3]
        if (strength > 0.3f) {
            strength = 0.3f;
        }

        getOutput("result").setValue(enabled);
    }

    @Override public String getTooltip() { return "膠片顆粒效果，模擬底片的顆粒感"; }
    @Override public String typeId() { return "film_grain"; }
}
