package com.blockreality.fastdesign.client.node.impl.render.postfx;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** A3-4: 像素藝術 */
@OnlyIn(Dist.CLIENT)
public class PixelArtNode extends BRNode {
    public PixelArtNode() {
        super("PixelArt", "像素藝術", "render", NodeColor.RENDER);
        addInput("enabled", "啟用", PortType.BOOL, false);
        addInput("pixelSize", "像素大小", PortType.INT, 4).range(1, 16);
        addInput("preserveUI", "保留 UI", PortType.BOOL, true);
        addInput("ditherPattern", "抖動圖案", PortType.ENUM, "NONE");
        addOutput("result", PortType.BOOL);
    }

    @Override
    public void evaluate() {
        boolean enabled = getInput("enabled").getBool();
        int pixelSize = getInput("pixelSize").getInt();

        // 像素大小限制在 [1, 16]
        if (pixelSize < 1) {
            pixelSize = 1;
        } else if (pixelSize > 16) {
            pixelSize = 16;
        }

        getOutput("result").setValue(enabled);
    }

    @Override public String getTooltip() { return "像素藝術效果，將畫面像素化以產生復古視覺風格"; }
    @Override public String typeId() { return "pixel_art"; }
}
