package com.blockreality.fastdesign.client.node.impl.render.postfx;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;

/** A3-14: 各向異性反射 */
@OnlyIn(Dist.CLIENT)
public class AnisotropicNode extends BRNode {
    public AnisotropicNode() {
        super("Anisotropic", "各向異性反射", "render", NodeColor.RENDER);
        addInput("enabled", "啟用", PortType.BOOL, true);
        addInput("roughnessX", "X 粗糙度", PortType.FLOAT, 0.3f).range(0f, 1f);
        addInput("roughnessY", "Y 粗糙度", PortType.FLOAT, 0.1f).range(0f, 1f);
        addInput("metallic", "金屬度", PortType.FLOAT, 0.8f).range(0f, 1f);
        addOutput("anisotropicEnabled", PortType.BOOL);
        addOutput("anisotropicStrength", PortType.FLOAT);
    }

    @Override
    public void evaluate() {
        getOutput("anisotropicEnabled").setValue(getInput("enabled").getBool());
        float rx = getInput("roughnessX").getFloat();
        float ry = getInput("roughnessY").getFloat();
        getOutput("anisotropicStrength").setValue(Math.abs(rx - ry));
    }

    @Override public String getTooltip() { return "各向異性反射，模擬拉絲金屬等方向性反射效果"; }
    @Override public String typeId() { return "render.postfx.Anisotropic"; }
}
