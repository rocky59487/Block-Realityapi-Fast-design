package com.blockreality.fastdesign.client.node.impl.material.shape;

import com.blockreality.api.chisel.SubBlockShape;
import com.blockreality.fastdesign.client.node.*;

/** B3-3: 形狀合併 — 對兩個形狀進行布林運算(Union/Intersect/Subtract) */
public class ShapeCombineNode extends BRNode {
    public ShapeCombineNode() {
        super("Shape Combine", "形狀合併", "material", NodeColor.MATERIAL);
        addInput("shapeA", "形狀 A", PortType.SHAPE, null);
        addInput("shapeB", "形狀 B", PortType.SHAPE, null);
        addInput("operation", "運算", PortType.ENUM, "Union");
        addOutput("result", "結果", PortType.SHAPE);
    }

    @Override
    public void evaluate() {
        SubBlockShape a = getInput("shapeA").getValue();
        SubBlockShape b = getInput("shapeB").getValue();

        // 布林運算結果暫以 CUSTOM 形狀表示，實際體素合併由雕刻系統處理
        // 若任一輸入為空則回傳另一方，兩者皆空則回傳 FULL
        if (a == null && b == null) {
            getOutput("result").setValue(SubBlockShape.FULL);
        } else if (a == null) {
            getOutput("result").setValue(b);
        } else if (b == null) {
            getOutput("result").setValue(a);
        } else {
            // 合併操作由底層 VoxelGrid 實現，此處以 CUSTOM 佔位
            getOutput("result").setValue(SubBlockShape.CUSTOM);
        }
    }

    @Override public String getTooltip() { return "對兩個形狀執行布林運算（聯集、交集、差集）"; }
    @Override public String typeId() { return "material.shape.ShapeCombine"; }
}
