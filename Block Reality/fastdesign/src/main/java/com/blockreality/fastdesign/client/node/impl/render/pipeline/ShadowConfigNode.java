package com.blockreality.fastdesign.client.node.impl.render.pipeline;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;

/** A2-3: 陰影設定 */
@OnlyIn(Dist.CLIENT)
public class ShadowConfigNode extends BRNode {
    public ShadowConfigNode() {
        super("ShadowConfig", "陰影設定", "render", NodeColor.RENDER);
        addInput("resolution", "解析度", PortType.INT, 2048).range(512, 4096).step(512);
        addInput("maxDistance", "最大距離", PortType.FLOAT, 128f).range(32, 256);
        addInput("cascadeCount", "級聯數", PortType.INT, 4).range(1, 4);
        addInput("pcfSamples", "PCF 取樣", PortType.INT, 4).range(1, 16);
        addInput("bias", "偏移", PortType.FLOAT, 0.001f).range(0.0001f, 0.01f);
        addOutput("shadowSpec", PortType.STRUCT);
        addOutput("shadowRes", PortType.INT);
        addOutput("shadowMaxDistance", PortType.FLOAT);
    }

    @Override
    public void evaluate() {
        getOutput("shadowRes").setValue(getInput("resolution").getInt());
        getOutput("shadowMaxDistance").setValue(getInput("maxDistance").getFloat());
    }

    @Override public String getTooltip() { return "陰影貼圖解析度、距離與 PCF"; }
    @Override public String typeId() { return "render.pipeline.ShadowConfig"; }
}
