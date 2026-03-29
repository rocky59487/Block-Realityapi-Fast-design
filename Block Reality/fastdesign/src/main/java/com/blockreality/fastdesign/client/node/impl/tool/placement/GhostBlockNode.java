package com.blockreality.fastdesign.client.node.impl.tool.placement;

import com.blockreality.fastdesign.client.node.*;
import net.minecraft.nbt.CompoundTag;

/** D2-5: 幽靈方塊 */
public class GhostBlockNode extends BRNode {
    public GhostBlockNode() {
        super("Ghost Block", "幽靈方塊", "tool", NodeColor.TOOL);
        addInput("alpha", "透明度", PortType.FLOAT, 0.5f).range(0f, 1f);
        addInput("breatheAmp", "呼吸振幅", PortType.FLOAT, 0.02f).range(0f, 0.1f);
        addInput("scanSpeed", "掃描速度", PortType.FLOAT, 0.3f).range(0f, 1f);
        addInput("collisionColor", "碰撞顏色", PortType.COLOR, 0xFFFF4444);
        addOutput("ghostBlockAlpha", PortType.FLOAT);
        addOutput("ghostBreatheAmp", PortType.FLOAT);
        addOutput("ghostScanSpeed", PortType.FLOAT);
        addOutput("ghostSpec", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        float alpha = getInput("alpha").getFloat();
        float breathe = getInput("breatheAmp").getFloat();
        float scan = getInput("scanSpeed").getFloat();
        getOutput("ghostBlockAlpha").setValue(alpha);
        getOutput("ghostBreatheAmp").setValue(breathe);
        getOutput("ghostScanSpeed").setValue(scan);
        CompoundTag spec = new CompoundTag();
        spec.putFloat("alpha", alpha);
        spec.putFloat("breatheAmp", breathe);
        spec.putFloat("scanSpeed", scan);
        spec.putInt("collisionColor", getInput("collisionColor").getColor());
        getOutput("ghostSpec").setValue(spec);
    }

    @Override public String getTooltip() { return "幽靈方塊預覽效果：呼吸動畫、掃描線"; }
    @Override public String typeId() { return "tool.placement.GhostBlock"; }
}
