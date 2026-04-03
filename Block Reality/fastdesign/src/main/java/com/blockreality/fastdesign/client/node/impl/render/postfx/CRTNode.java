package com.blockreality.fastdesign.client.node.impl.render.postfx;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** A3-4: CRT 掃描線 */
@OnlyIn(Dist.CLIENT)
public class CRTNode extends BRNode {
    public CRTNode() {
        super("CRT", "CRT 掃描線", "render", NodeColor.RENDER);
        addInput("enabled", "啟用", PortType.BOOL, false);
        addInput("scanlineIntensity", "掃描線強度", PortType.FLOAT, 0.4f).range(0f, 1.0f);
        addInput("curvature", "螢幕曲度", PortType.FLOAT, 0.1f).range(0f, 0.5f);
        addInput("flickerSpeed", "閃爍速度", PortType.FLOAT, 0.5f).range(0f, 5.0f);
        addInput("phosphorGlow", "磷粉發光", PortType.BOOL, true);
        addOutput("result", PortType.BOOL);
    }

    @Override
    public void evaluate() {
        boolean enabled = getInput("enabled").getBool();
        float scanlineIntensity = getInput("scanlineIntensity").getFloat();

        // 掃描線強度限制在 [0, 1]
        if (scanlineIntensity < 0f) {
            scanlineIntensity = 0f;
        } else if (scanlineIntensity > 1.0f) {
            scanlineIntensity = 1.0f;
        }

        getOutput("result").setValue(enabled);
    }

    @Override public String getTooltip() { return "CRT 掃描線效果，模擬經典陰極射線管顯示器的視覺風格"; }
    @Override public String typeId() { return "crt"; }
}
