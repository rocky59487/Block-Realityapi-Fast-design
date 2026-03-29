package com.blockreality.fastdesign.client.node.impl.material.blending;

import com.blockreality.api.material.DynamicMaterial;
import com.blockreality.api.material.RMaterial;
import com.blockreality.fastdesign.client.node.*;
import net.minecraft.resources.ResourceLocation;

/** B5-3: 方塊混合器 — 依比例混合兩個方塊的材料屬性 */
public class BlockBlenderNode extends BRNode {
    public BlockBlenderNode() {
        super("Block Blender", "方塊混合器", "material", NodeColor.BLENDING);
        addInput("blockA", "方塊 A", PortType.BLOCK, null);
        addInput("blockB", "方塊 B", PortType.BLOCK, null);
        addInput("ratio", "比例(A:B)", PortType.FLOAT, 0.5f).range(0f, 1f);
        addInput("mixMode", "混合模式", PortType.ENUM, "Linear");
        addInput("textureSource", "紋理來源", PortType.ENUM, "BlockA");
        addOutput("blendedMaterial", "混合材料", PortType.MATERIAL);
        addOutput("blendedBlock", "混合方塊", PortType.BLOCK);
        addOutput("rcomp", "抗壓強度", PortType.FLOAT);
        addOutput("rtens", "抗拉強度", PortType.FLOAT);
        addOutput("rshear", "抗剪強度", PortType.FLOAT);
        addOutput("density", "密度", PortType.FLOAT);
    }

    @Override
    public void evaluate() {
        BRBlockDef a = getInput("blockA").getValue();
        BRBlockDef b = getInput("blockB").getValue();
        if (a == null || b == null) {
            getOutput("blendedMaterial").setValue(null);
            getOutput("blendedBlock").setValue(null);
            getOutput("rcomp").setValue(0f);
            getOutput("rtens").setValue(0f);
            getOutput("rshear").setValue(0f);
            getOutput("density").setValue(0f);
            return;
        }

        float r = getInput("ratio").getFloat();
        float rb = 1.0f - r;
        RMaterial matA = a.material();
        RMaterial matB = b.material();

        double rcomp   = matA.getRcomp()   * r + matB.getRcomp()   * rb;
        double rtens   = matA.getRtens()   * r + matB.getRtens()   * rb;
        double rshear  = matA.getRshear()  * r + matB.getRshear()  * rb;
        double density = matA.getDensity() * r + matB.getDensity() * rb;

        DynamicMaterial blended = DynamicMaterial.ofCustom("blended", rcomp, rtens, rshear, density);

        // 紋理來源決定基底方塊
        String texSrc = getInput("textureSource").getValue();
        BRBlockDef sourceBlock = "BlockB".equals(texSrc) ? b : a;
        BRBlockDef blendedBlock = sourceBlock.withMaterial(blended);

        getOutput("blendedMaterial").setValue(blended);
        getOutput("blendedBlock").setValue(blendedBlock);
        getOutput("rcomp").setValue((float) rcomp);
        getOutput("rtens").setValue((float) rtens);
        getOutput("rshear").setValue((float) rshear);
        getOutput("density").setValue((float) density);
    }

    @Override public String getTooltip() { return "依比例線性混合兩個方塊的材料工程參數"; }
    @Override public String typeId() { return "material.blending.BlockBlender"; }
}
