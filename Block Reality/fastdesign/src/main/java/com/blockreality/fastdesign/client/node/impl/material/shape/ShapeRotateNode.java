package com.blockreality.fastdesign.client.node.impl.material.shape;

import com.blockreality.api.chisel.SubBlockShape;
import com.blockreality.fastdesign.client.node.*;

/** B3-4: 旋轉形狀 — 將形狀繞 Y 軸旋轉 90 度的倍數 */
public class ShapeRotateNode extends BRNode {
    public ShapeRotateNode() {
        super("Shape Rotate", "旋轉形狀", "material", NodeColor.MATERIAL);
        addInput("shape", "形狀", PortType.SHAPE, null);
        addInput("rotations", "旋轉次數(×90°)", PortType.INT, 0).range(0, 3);
        addOutput("rotated", "旋轉形狀", PortType.SHAPE);
    }

    @Override
    public void evaluate() {
        SubBlockShape shape = getInput("shape").getValue();
        if (shape == null) {
            getOutput("rotated").setValue(SubBlockShape.FULL);
            return;
        }

        int rot = getInput("rotations").getInt() % 4;
        if (rot == 0) {
            getOutput("rotated").setValue(shape);
            return;
        }

        // 對稱形狀旋轉後不變
        if (shape == SubBlockShape.FULL || shape == SubBlockShape.PILLAR || shape == SubBlockShape.CUSTOM) {
            getOutput("rotated").setValue(shape);
            return;
        }

        // 方向形狀的旋轉映射 — 實際的體素旋轉由雕刻系統處理
        // 此處回傳 CUSTOM 作為旋轉結果佔位
        getOutput("rotated").setValue(SubBlockShape.CUSTOM);
    }

    @Override public String getTooltip() { return "將形狀繞 Y 軸旋轉 0/90/180/270 度"; }
    @Override public String typeId() { return "material.shape.ShapeRotate"; }
}
