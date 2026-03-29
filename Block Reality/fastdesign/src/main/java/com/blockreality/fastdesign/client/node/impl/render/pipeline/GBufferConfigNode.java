package com.blockreality.fastdesign.client.node.impl.render.pipeline;

import com.blockreality.fastdesign.client.node.*;

/** A2-2: GBuffer 配置 */
public class GBufferConfigNode extends BRNode {
    public GBufferConfigNode() {
        super("GBufferConfig", "GBuffer 配置", "render", NodeColor.RENDER);
        addInput("attachmentCount", "附件數", PortType.INT, 5).range(3, 5);
        addInput("hdrEnabled", "HDR", PortType.BOOL, true);
        addInput("format", "格式", PortType.ENUM, "RGBA16F");
        addOutput("gbufferSpec", PortType.STRUCT);
        addOutput("vramUsageMB", PortType.FLOAT);
    }

    @Override
    public void evaluate() {
        int att = getInput("attachmentCount").getInt();
        boolean hdr = getInput("hdrEnabled").getBool();
        int bpp = hdr ? 8 : 4; // bytes per pixel per attachment
        float vram = att * bpp * 1920 * 1080 / (1024f * 1024f);
        getOutput("vramUsageMB").setValue(vram);
    }

    @Override public String getTooltip() { return "GBuffer 附件數量與格式"; }
    @Override public String typeId() { return "render.pipeline.GBufferConfig"; }
}
