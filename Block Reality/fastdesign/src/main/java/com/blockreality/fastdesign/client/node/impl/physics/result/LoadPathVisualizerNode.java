package com.blockreality.fastdesign.client.node.impl.physics.result;

import com.blockreality.fastdesign.client.node.*;
import net.minecraft.nbt.CompoundTag;

/** C3-2: 荷載路徑視覺化 */
public class LoadPathVisualizerNode extends BRNode {
    public LoadPathVisualizerNode() {
        super("Load Path Visualizer", "荷載路徑視覺化", "physics", NodeColor.PHYSICS);
        addInput("loadPaths", "荷載路徑", PortType.STRUCT, null);
        addInput("showArrows", "顯示箭頭", PortType.BOOL, true);
        addInput("lineWidth", "線寬", PortType.FLOAT, 2f).range(1f, 5f);
        addInput("colorByMagnitude", "依大小著色", PortType.BOOL, true);
        addOutput("pathRenderData", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        CompoundTag data = new CompoundTag();
        data.putBoolean("showArrows", getInput("showArrows").getBool());
        data.putFloat("lineWidth", getInput("lineWidth").getFloat());
        data.putBoolean("colorByMagnitude", getInput("colorByMagnitude").getBool());
        getOutput("pathRenderData").setValue(data);
    }

    @Override public String getTooltip() { return "荷載傳遞路徑箭頭視覺化"; }
    @Override public String typeId() { return "physics.result.LoadPathVisualizer"; }
}
