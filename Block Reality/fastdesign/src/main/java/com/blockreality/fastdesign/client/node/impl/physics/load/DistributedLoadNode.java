package com.blockreality.fastdesign.client.node.impl.physics.load;

import com.blockreality.fastdesign.client.node.*;

/** C2-2: 分布荷載 */
public class DistributedLoadNode extends BRNode {
    public DistributedLoadNode() {
        super("Distributed Load", "分布荷載", "physics", NodeColor.PHYSICS);
        addInput("magnitude", "大小", PortType.FLOAT, 1000f).range(0f, 100000f);
        addInput("direction", "方向", PortType.ENUM, "Down");
        addInput("area", "面積", PortType.FLOAT, 1f).range(0.01f, 100f);
        addOutput("totalForce", PortType.FLOAT);
        addOutput("loadVector", PortType.VEC3);
    }

    @Override
    public void evaluate() {
        float mag = getInput("magnitude").getFloat();
        float area = getInput("area").getFloat();
        float total = mag * area;
        getOutput("totalForce").setValue(total);
        String dir = getInput("direction").getValue();
        float[] vec;
        if ("Up".equals(dir)) vec = new float[]{0f, total, 0f};
        else if ("North".equals(dir)) vec = new float[]{0f, 0f, -total};
        else if ("South".equals(dir)) vec = new float[]{0f, 0f, total};
        else if ("East".equals(dir)) vec = new float[]{total, 0f, 0f};
        else if ("West".equals(dir)) vec = new float[]{-total, 0f, 0f};
        else vec = new float[]{0f, -total, 0f};
        getOutput("loadVector").setValue(vec);
    }

    @Override public String getTooltip() { return "均布荷載：magnitude × area = 總力"; }
    @Override public String typeId() { return "physics.load.DistributedLoad"; }
}
