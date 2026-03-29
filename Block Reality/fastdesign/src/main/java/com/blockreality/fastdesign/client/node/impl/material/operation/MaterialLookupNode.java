package com.blockreality.fastdesign.client.node.impl.material.operation;

import com.blockreality.api.material.DefaultMaterial;
import com.blockreality.api.material.VanillaMaterialMap;
import com.blockreality.fastdesign.client.node.*;

/** B2-8: 方塊材料查詢 — 從 VanillaMaterialMap 查詢方塊對應的材料 */
public class MaterialLookupNode extends BRNode {
    public MaterialLookupNode() {
        super("Material Lookup", "方塊材料查詢", "material", NodeColor.MATERIAL);
        addInput("blockId", "方塊 ID", PortType.ENUM, "minecraft:stone");
        addOutput("material", PortType.MATERIAL);
        addOutput("rcomp", "抗壓強度", PortType.FLOAT);
        addOutput("rtens", "抗拉強度", PortType.FLOAT);
    }

    @Override
    public void evaluate() {
        String blockId = getInput("blockId").getValue();
        if (blockId == null || blockId.isEmpty()) {
            blockId = "minecraft:stone";
        }

        DefaultMaterial mat = VanillaMaterialMap.getInstance().getMaterial(blockId);
        getOutput("material").setValue(mat);
        getOutput("rcomp").setValue((float) mat.getRcomp());
        getOutput("rtens").setValue((float) mat.getRtens());
    }

    @Override public String getTooltip() { return "從 VanillaMaterialMap 查詢指定方塊 ID 對應的材料與工程參數"; }
    @Override public String typeId() { return "material.operation.MaterialLookup"; }
}
