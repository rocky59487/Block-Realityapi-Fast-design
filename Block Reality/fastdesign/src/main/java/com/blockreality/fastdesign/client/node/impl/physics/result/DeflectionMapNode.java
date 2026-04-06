package com.blockreality.fastdesign.client.node.impl.physics.result;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;

/** C3-3: 變形量分佈 */
@OnlyIn(Dist.CLIENT)
public class DeflectionMapNode extends BRNode {
    public DeflectionMapNode() {
        super("Deflection Map", "變形量分佈", "physics", NodeColor.PHYSICS);
        addInput("deflectionData", "變形資料", PortType.STRUCT, null);
        addInput("scale", "放大倍率", PortType.FLOAT, 10f).range(1f, 100f);
        addOutput("mapTexture", PortType.TEXTURE);
        addOutput("maxDeflection", PortType.FLOAT);
    }

    @Override
    public void evaluate() {
        getOutput("mapTexture").setValue(0);
        getOutput("maxDeflection").setValue(0.0f);
    }

    @Override public String getTooltip() { return "結構變形量分佈紋理映射"; }
    @Override public String typeId() { return "physics.result.DeflectionMap"; }
}
