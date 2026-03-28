package com.blockreality.fastdesign.client.node.impl.render.postfx;

import com.blockreality.fastdesign.client.node.*;

/** A3-1: 環境遮蔽（SSAO / GTAO） */
public class SSAO_GTAONode extends BRNode {
    public SSAO_GTAONode() {
        super("SSAO_GTAO", "環境遮蔽", "render", NodeColor.RENDER);
        addInput("enabled", "啟用", PortType.BOOL, true);
        addInput("mode", "模式", PortType.ENUM, "GTAO");
        addInput("kernelSize", "核心大小", PortType.INT, 16).range(4, 64);
        addInput("radius", "半徑", PortType.FLOAT, 0.5f).range(0.1f, 5f);
        addInput("gtaoSlices", "GTAO 切片數", PortType.INT, 3).range(1, 8);
        addInput("gtaoStepsPerSlice", "每切片步數", PortType.INT, 4).range(1, 16);
        addInput("gtaoRadius", "GTAO 半徑", PortType.FLOAT, 1.5f).range(0.1f, 5f);
        addInput("gtaoFalloff", "GTAO 衰減", PortType.FLOAT, 2.0f).range(0.5f, 5f);
        addInput("intensity", "強度", PortType.FLOAT, 1.0f).range(0f, 2f);
        addOutput("aoTexture", PortType.TEXTURE);
        addOutput("gpuTimeMs", PortType.FLOAT);
    }

    @Override
    public void evaluate() {
        getOutput("aoTexture").setValue(0);
        getOutput("gpuTimeMs").setValue(0.0f);
    }

    @Override public String getTooltip() { return "螢幕空間環境遮蔽，支援 SSAO 與 GTAO 模式"; }
    @Override public String typeId() { return "render.postfx.SSAO_GTAO"; }
}
