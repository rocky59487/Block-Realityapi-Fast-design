package com.blockreality.fastdesign.client.node.impl.render.postfx;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;

/** A3-7: 接觸陰影 */
@OnlyIn(Dist.CLIENT)
public class ContactShadowNode extends BRNode {
    public ContactShadowNode() {
        super("ContactShadow", "接觸陰影", "render", NodeColor.RENDER);
        addInput("enabled", "啟用", PortType.BOOL, true);
        addInput("maxDist", "最大距離", PortType.FLOAT, 3.0f).range(0.5f, 10f);
        addInput("steps", "步數", PortType.INT, 16).range(4, 64);
        addInput("thickness", "厚度", PortType.FLOAT, 0.05f).range(0.01f, 0.2f);
        addInput("intensity", "強度", PortType.FLOAT, 0.5f).range(0f, 1f);
        addOutput("contactShadowEnabled", PortType.BOOL);
        addOutput("contactShadowMaxDist", PortType.FLOAT);
        addOutput("contactShadowSteps", PortType.INT);
    }

    @Override
    public void evaluate() {
        getOutput("contactShadowEnabled").setValue(getInput("enabled").getBool());
        getOutput("contactShadowMaxDist").setValue(getInput("maxDist").getFloat());
        getOutput("contactShadowSteps").setValue(getInput("steps").getInt());
    }

    @Override public String getTooltip() { return "接觸陰影，為物體接觸面添加近距離陰影細節"; }
    @Override public String typeId() { return "render.postfx.ContactShadow"; }
}
