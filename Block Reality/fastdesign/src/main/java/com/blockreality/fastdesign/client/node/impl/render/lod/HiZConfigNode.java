package com.blockreality.fastdesign.client.node.impl.render.lod;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;

/** A5-5: Hi-Z 金字塔 */
@OnlyIn(Dist.CLIENT)
public class HiZConfigNode extends BRNode {
    public HiZConfigNode() {
        super("HiZConfig", "Hi-Z 金字塔", "render", NodeColor.RENDER);
        addInput("enabled", "啟用", PortType.BOOL, true);
        addInput("mipLevels", "Mip 層數", PortType.INT, 6).range(3, 10);
        addOutput("hizSpec", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        // hizSpec populated by downstream binder
    }

    @Override public String getTooltip() { return "Hi-Z 深度金字塔 Mip 層數設定"; }
    @Override public String typeId() { return "render.lod.HiZConfig"; }
}
