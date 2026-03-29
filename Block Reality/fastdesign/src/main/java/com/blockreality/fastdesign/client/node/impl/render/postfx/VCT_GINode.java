package com.blockreality.fastdesign.client.node.impl.render.postfx;

import com.blockreality.fastdesign.client.node.*;

/** A3-17: 體素錐追蹤 GI */
public class VCT_GINode extends BRNode {
    public VCT_GINode() {
        super("VCT_GI", "體素錐追蹤 GI", "render", NodeColor.RENDER);
        addInput("enabled", "啟用", PortType.BOOL, false);
        addInput("coneAngle", "錐角", PortType.FLOAT, 0.5f).range(0.1f, 1.5f);
        addInput("coneCount", "錐數量", PortType.INT, 6).range(1, 16);
        addInput("maxDist", "最大距離", PortType.FLOAT, 128f).range(32f, 512f);
        addInput("resolution", "解析度", PortType.INT, 128).range(64, 256);
        addOutput("vctSpec", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        net.minecraft.nbt.CompoundTag spec = new net.minecraft.nbt.CompoundTag();
        spec.putBoolean("enabled", getInput("enabled").getBool());
        spec.putFloat("coneAngle", getInput("coneAngle").getFloat());
        spec.putInt("coneCount", getInput("coneCount").getInt());
        spec.putFloat("maxDist", getInput("maxDist").getFloat());
        spec.putInt("resolution", getInput("resolution").getInt());
        getOutput("vctSpec").setValue(spec);
    }

    @Override public String getTooltip() { return "體素錐追蹤全域光照，基於 3D 體素化的間接光照"; }
    @Override public String typeId() { return "render.postfx.VCT_GI"; }
}
