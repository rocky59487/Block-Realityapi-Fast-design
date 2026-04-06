package com.blockreality.fastdesign.client.node.impl.render.postfx;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;

/** A3-8: 景深 */
@OnlyIn(Dist.CLIENT)
public class DOFNode extends BRNode {
    public DOFNode() {
        super("DOF", "景深", "render", NodeColor.RENDER);
        addInput("enabled", "啟用", PortType.BOOL, false);
        addInput("focusDist", "對焦距離", PortType.FLOAT, 16f).range(1f, 128f);
        addInput("aperture", "光圈", PortType.FLOAT, 2.8f).range(1.0f, 16f);
        addInput("sampleCount", "取樣數", PortType.INT, 32).range(8, 64);
        addOutput("dofEnabled", PortType.BOOL);
        addOutput("dofFocusDist", PortType.FLOAT);
        addOutput("dofAperture", PortType.FLOAT);
        addOutput("dofSampleCount", PortType.INT);
    }

    @Override
    public void evaluate() {
        getOutput("dofEnabled").setValue(getInput("enabled").getBool());
        getOutput("dofFocusDist").setValue(getInput("focusDist").getFloat());
        getOutput("dofAperture").setValue(getInput("aperture").getFloat());
        getOutput("dofSampleCount").setValue(getInput("sampleCount").getInt());
    }

    @Override public String getTooltip() { return "景深效果，模擬相機光圈的散景模糊"; }
    @Override public String typeId() { return "render.postfx.DOF"; }
}
