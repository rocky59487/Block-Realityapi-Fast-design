package com.blockreality.fastdesign.client.node.impl.material.shape;

import com.blockreality.api.chisel.SubBlockShape;
import com.blockreality.fastdesign.client.node.*;

/** B3-1: 形狀選擇 — 從 SubBlockShape 枚舉選取形狀並輸出截面屬性 */
public class ShapeSelectorNode extends BRNode {
    public ShapeSelectorNode() {
        super("Shape Selector", "形狀選擇", "material", NodeColor.MATERIAL);
        addInput("shape", "形狀", PortType.ENUM, "FULL");
        addOutput("shape", "形狀", PortType.SHAPE);
        addOutput("fillRatio", "填充率", PortType.FLOAT);
        addOutput("crossSectionArea", "截面積", PortType.FLOAT);
        addOutput("momentOfInertiaX", "慣性矩 Ix", PortType.FLOAT);
        addOutput("momentOfInertiaY", "慣性矩 Iy", PortType.FLOAT);
    }

    @Override
    public void evaluate() {
        String shapeName = getInput("shape").getValue();
        SubBlockShape shape = SubBlockShape.fromString(
                shapeName != null ? shapeName.toLowerCase() : "full");

        getOutput("shape").setValue(shape);
        getOutput("fillRatio").setValue((float) shape.getFillRatio());
        getOutput("crossSectionArea").setValue((float) shape.getCrossSectionArea());
        getOutput("momentOfInertiaX").setValue((float) shape.getMomentOfInertiaX());
        getOutput("momentOfInertiaY").setValue((float) shape.getMomentOfInertiaY());
    }

    @Override public String getTooltip() { return "從預定義形狀模板選取形狀，輸出填充率和截面力學屬性"; }
    @Override public String typeId() { return "material.shape.ShapeSelector"; }
}
