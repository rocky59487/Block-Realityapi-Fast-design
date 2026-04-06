package com.blockreality.fastdesign.client.node.impl.render.lighting;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;

/** A4-2: 環境光 */
@OnlyIn(Dist.CLIENT)
public class AmbientLightNode extends BRNode {
    public AmbientLightNode() {
        super("AmbientLight", "環境光", "render", NodeColor.RENDER);
        addInput("skyColor", "天空色", PortType.COLOR, 0xFF8899CC);
        addInput("groundColor", "地面色", PortType.COLOR, 0xFF443322);
        addInput("intensity", "強度", PortType.FLOAT, 0.5f).range(0f, 2f);
        addInput("aoStrength", "AO 強度", PortType.FLOAT, 0.7f).range(0f, 1f);
        addOutput("ambientSpec", PortType.STRUCT);
        addOutput("aoStrength", PortType.FLOAT);
    }

    @Override
    public void evaluate() {
        float ao = getInput("aoStrength").getFloat();
        getOutput("aoStrength").setValue(ao);
    }

    @Override public String getTooltip() { return "環境光天空色、地面色與 AO 強度"; }
    @Override public String typeId() { return "render.lighting.AmbientLight"; }
}
