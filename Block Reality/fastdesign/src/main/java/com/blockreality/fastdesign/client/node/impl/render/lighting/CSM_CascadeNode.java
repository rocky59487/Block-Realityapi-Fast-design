package com.blockreality.fastdesign.client.node.impl.render.lighting;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;

/** A4-3: 級聯陰影 */
@OnlyIn(Dist.CLIENT)
public class CSM_CascadeNode extends BRNode {
    public CSM_CascadeNode() {
        super("CSM_Cascade", "級聯陰影", "render", NodeColor.RENDER);
        addInput("enabled", "啟用", PortType.BOOL, true);
        addInput("cascadeCount", "級聯數", PortType.INT, 4).range(1, 4);
        addInput("maxDistance", "最大距離", PortType.FLOAT, 256f).range(64f, 512f);
        addInput("blending", "混合", PortType.FLOAT, 0.5f).range(0f, 1f);
        addOutput("csmEnabled", PortType.BOOL);
        addOutput("csmCascadeCount", PortType.INT);
        addOutput("csmMaxDistance", PortType.FLOAT);
    }

    @Override
    public void evaluate() {
        getOutput("csmEnabled").setValue(getInput("enabled").getBool());
        getOutput("csmCascadeCount").setValue(getInput("cascadeCount").getInt());
        getOutput("csmMaxDistance").setValue(getInput("maxDistance").getFloat());
    }

    @Override public String getTooltip() { return "級聯陰影貼圖（CSM）級數與距離設定"; }
    @Override public String typeId() { return "render.lighting.CSM_Cascade"; }
}
