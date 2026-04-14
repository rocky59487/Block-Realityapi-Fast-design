package com.blockreality.fastdesign.client.node.impl.render.water;

import com.blockreality.api.client.render.effect.BRWaterRenderer;
import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;

/** A7-2: 焦散 */
@OnlyIn(Dist.CLIENT)
public class WaterCausticsNode extends BRNode {
    public WaterCausticsNode() {
        super("WaterCaustics", "焦散", "render", NodeColor.RENDER);
        addInput("enabled", "啟用", PortType.BOOL, true);
        addInput("intensity", "強度", PortType.FLOAT, 0.3f).range(0f, 1f);
        addInput("speed", "速度", PortType.FLOAT, 1.0f).range(0f, 3f);
        addInput("scale", "縮放", PortType.FLOAT, 1.0f).range(0.5f, 4f);
        // ★ PFSF-Fluid: 流速調制焦散光強度
        addInput("fluidVelocity", "物理流速", PortType.FLOAT, 0f).range(0f, 10f);
        addOutput("waterCausticsIntensity", PortType.FLOAT);
        addOutput("causticsSpec", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        float vel = getInput("fluidVelocity").getFloat();  // 0-10 m/s
        float baseSpeed = getInput("speed").getFloat();
        // 高流速 → 焦散動畫加速（最多 5× 於 10 m/s）
        float speedMult = 1.0f + vel * 0.4f;
        BRWaterRenderer.setCausticsAnimSpeed(baseSpeed * speedMult);
        getOutput("waterCausticsIntensity").setValue(getInput("intensity").getFloat());
    }

    @Override public String getTooltip() { return "水面焦散強度、速度與縮放"; }
    @Override public String typeId() { return "render.water.WaterCaustics"; }
}
