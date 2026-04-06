package com.blockreality.fastdesign.client.node.impl.render.pipeline;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;

/** A2-4: FBO 鏈設定 */
@OnlyIn(Dist.CLIENT)
public class FramebufferChainNode extends BRNode {
    public FramebufferChainNode() {
        super("FramebufferChain", "FBO 鏈", "render", NodeColor.RENDER);
        addInput("screenScale", "縮放", PortType.FLOAT, 1.0f).range(0.25f, 2.0f);
        addInput("pingPongEnabled", "Ping-Pong", PortType.BOOL, true);
        addInput("taaHistoryEnabled", "TAA 歷史", PortType.BOOL, true);
        addOutput("fboSpec", PortType.STRUCT);
        addOutput("totalVRAM", PortType.FLOAT);
    }

    @Override
    public void evaluate() {
        float scale = getInput("screenScale").getFloat();
        boolean pp = getInput("pingPongEnabled").getBool();
        boolean taaHist = getInput("taaHistoryEnabled").getBool();
        int fboCount = 2 + (pp ? 2 : 0) + (taaHist ? 1 : 0);
        float vram = fboCount * scale * scale * 1920 * 1080 * 8 / (1024f * 1024f);
        getOutput("totalVRAM").setValue(vram);
    }

    @Override public String getTooltip() { return "Framebuffer 鏈配置與 VRAM 估算"; }
    @Override public String typeId() { return "render.pipeline.FramebufferChain"; }
}
