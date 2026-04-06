package com.blockreality.fastdesign.client.node.impl.render.postfx;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;

/** A3-15: 次表面散射 */
@OnlyIn(Dist.CLIENT)
public class SSSNode extends BRNode {
    public SSSNode() {
        super("SSS", "次表面散射", "render", NodeColor.RENDER);
        addInput("enabled", "啟用", PortType.BOOL, true);
        addInput("scatterRadius", "散射半徑", PortType.FLOAT, 0.01f).range(0f, 0.05f);
        addInput("profile", "散射模型", PortType.ENUM, "Skin");
        addInput("strength", "強度", PortType.FLOAT, 0.5f).range(0f, 1f);
        addOutput("sssEnabled", PortType.BOOL);
        addOutput("sssWidth", PortType.FLOAT);
        addOutput("sssStrength", PortType.FLOAT);
    }

    @Override
    public void evaluate() {
        getOutput("sssEnabled").setValue(getInput("enabled").getBool());
        getOutput("sssWidth").setValue(getInput("scatterRadius").getFloat());
        getOutput("sssStrength").setValue(getInput("strength").getFloat());
    }

    @Override public String getTooltip() { return "次表面散射，模擬光線穿透半透明材質的散射效果"; }
    @Override public String typeId() { return "render.postfx.SSS"; }
}
