package com.blockreality.fastdesign.client.node.impl.render.postfx;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;

/** A3-6: 體積光 */
@OnlyIn(Dist.CLIENT)
public class VolumetricLightNode extends BRNode {
    public VolumetricLightNode() {
        super("VolumetricLight", "體積光", "render", NodeColor.RENDER);
        addInput("enabled", "啟用", PortType.BOOL, true);
        addInput("raySteps", "光線步數", PortType.INT, 32).range(8, 128);
        addInput("fogDensity", "霧密度", PortType.FLOAT, 0.02f).range(0f, 0.1f);
        addInput("scatterStrength", "散射強度", PortType.FLOAT, 1.5f).range(0f, 5f);
        addOutput("volumetricEnabled", PortType.BOOL);
        addOutput("volumetricRaySteps", PortType.INT);
        addOutput("volumetricFogDensity", PortType.FLOAT);
        addOutput("volumetricScatterStrength", PortType.FLOAT);
    }

    @Override
    public void evaluate() {
        getOutput("volumetricEnabled").setValue(getInput("enabled").getBool());
        getOutput("volumetricRaySteps").setValue(getInput("raySteps").getInt());
        getOutput("volumetricFogDensity").setValue(getInput("fogDensity").getFloat());
        getOutput("volumetricScatterStrength").setValue(getInput("scatterStrength").getFloat());
    }

    @Override public String getTooltip() { return "體積光效果，模擬光線穿透霧氣的散射"; }
    @Override public String typeId() { return "render.postfx.VolumetricLight"; }
}
