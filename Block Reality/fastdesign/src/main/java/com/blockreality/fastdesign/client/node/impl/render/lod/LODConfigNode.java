package com.blockreality.fastdesign.client.node.impl.render.lod;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;

/** A5-1: LOD 總控 */
@OnlyIn(Dist.CLIENT)
public class LODConfigNode extends BRNode {
    public LODConfigNode() {
        super("LODConfig", "LOD 總控", "render", NodeColor.RENDER);
        addInput("maxDistance", "最大距離", PortType.FLOAT, 1024f).range(64f, 1024f);
        addInput("levelCount", "等級數", PortType.INT, 5).range(3, 5);
        addInput("hysteresis", "遲滯", PortType.FLOAT, 8f).range(0f, 32f);
        addInput("transitionBand", "過渡帶", PortType.FLOAT, 8f).range(0f, 32f);
        addInput("fogMatchEnabled", "霧匹配", PortType.BOOL, true);
        addOutput("lodSpec", PortType.STRUCT);
        addOutput("lodMaxDist", PortType.FLOAT);
        addOutput("visibleSections", PortType.INT);
        addOutput("totalVRAM", PortType.FLOAT);
    }

    @Override
    public void evaluate() {
        float maxDist = getInput("maxDistance").getFloat();
        getOutput("lodMaxDist").setValue(maxDist);
        // Estimate visible sections as cube of (maxDist/16)
        int sections = (int) Math.pow(maxDist / 16.0, 2) * 4;
        getOutput("visibleSections").setValue(sections);
        // Rough VRAM estimate in MB
        getOutput("totalVRAM").setValue(sections * 0.25f);
    }

    @Override public String getTooltip() { return "LOD 系統總控：距離、等級數與過渡設定"; }
    @Override public String typeId() { return "render.lod.LODConfig"; }
}
