package com.blockreality.fastdesign.client.node.impl.material.shape;

import com.blockreality.api.chisel.SubBlockShape;
import com.blockreality.fastdesign.client.node.*;

/** B3-5: 鏡像形狀 — 沿指定軸鏡像翻轉形狀 */
public class ShapeMirrorNode extends BRNode {
    public ShapeMirrorNode() {
        super("Shape Mirror", "鏡像形狀", "material", NodeColor.MATERIAL);
        addInput("shape", "形狀", PortType.SHAPE, null);
        addInput("axis", "鏡像軸", PortType.ENUM, "X");
        addOutput("mirrored", "鏡像形狀", PortType.SHAPE);
    }

    @Override
    public void evaluate() {
        SubBlockShape shape = getInput("shape").getValue();
        if (shape == null) {
            getOutput("mirrored").setValue(SubBlockShape.FULL);
            return;
        }

        // 對稱形狀鏡像後不變
        if (shape == SubBlockShape.FULL || shape == SubBlockShape.PILLAR
                || shape == SubBlockShape.SLAB_BOTTOM || shape == SubBlockShape.SLAB_TOP) {
            getOutput("mirrored").setValue(shape);
            return;
        }

        // 實際體素鏡像由雕刻系統處理，此處以 CUSTOM 佔位
        getOutput("mirrored").setValue(SubBlockShape.CUSTOM);
    }

    @Override public String getTooltip() { return "沿 X/Y/Z 軸鏡像翻轉形狀"; }
    @Override public String typeId() { return "material.shape.ShapeMirror"; }
}
