package com.blockreality.fastdesign.client.node.impl.render.postfx;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;

/** A3-9: 動態模糊 */
@OnlyIn(Dist.CLIENT)
public class MotionBlurNode extends BRNode {
    public MotionBlurNode() {
        super("MotionBlur", "動態模糊", "render", NodeColor.RENDER);
        addInput("enabled", "啟用", PortType.BOOL, false);
        addInput("intensity", "強度", PortType.FLOAT, 0.5f).range(0f, 1f);
        addInput("samples", "取樣數", PortType.INT, 8).range(4, 32);
        addInput("velocityScale", "速度縮放", PortType.FLOAT, 1.0f).range(0f, 3f);
        addOutput("cinematicMotionBlur", PortType.FLOAT);
        addOutput("cinematicMotionBlurSamples", PortType.INT);
    }

    @Override
    public void evaluate() {
        getOutput("cinematicMotionBlur").setValue(getInput("intensity").getFloat());
        getOutput("cinematicMotionBlurSamples").setValue(getInput("samples").getInt());
    }

    @Override public String getTooltip() { return "動態模糊，根據運動速度產生方向性模糊"; }
    @Override public String typeId() { return "render.postfx.MotionBlur"; }
}
