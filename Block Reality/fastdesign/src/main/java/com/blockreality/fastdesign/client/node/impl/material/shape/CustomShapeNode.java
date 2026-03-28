package com.blockreality.fastdesign.client.node.impl.material.shape;

import com.blockreality.api.chisel.SubBlockShape;
import com.blockreality.fastdesign.client.node.*;
import net.minecraft.nbt.CompoundTag;

/** B3-2: 自訂形狀 — 透過體素編輯器建立自訂方塊形狀 */
public class CustomShapeNode extends BRNode {
    public CustomShapeNode() {
        super("Custom Shape", "自訂形狀", "material", NodeColor.MATERIAL);
        addInput("voxelEditor", "體素編輯器", PortType.STRUCT, null);
        addInput("autoCalcProperties", "自動計算屬性", PortType.BOOL, true);
        addOutput("shape", "形狀", PortType.SHAPE);
        addOutput("properties", "形狀屬性", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        // 自訂形狀預設使用 CUSTOM 模板，實際體素資料由 UI 編輯器提供
        SubBlockShape shape = SubBlockShape.CUSTOM;
        getOutput("shape").setValue(shape);

        CompoundTag props = new CompoundTag();
        props.putDouble("fillRatio", shape.getFillRatio());
        props.putDouble("crossSectionArea", shape.getCrossSectionArea());
        props.putDouble("momentOfInertiaX", shape.getMomentOfInertiaX());
        props.putDouble("momentOfInertiaY", shape.getMomentOfInertiaY());
        props.putDouble("sectionModulusX", shape.getSectionModulusX());
        props.putDouble("sectionModulusY", shape.getSectionModulusY());
        getOutput("properties").setValue(props);
    }

    @Override public String getTooltip() { return "透過體素編輯器自訂方塊形狀，自動計算截面力學屬性"; }
    @Override public String typeId() { return "material.shape.CustomShape"; }
}
