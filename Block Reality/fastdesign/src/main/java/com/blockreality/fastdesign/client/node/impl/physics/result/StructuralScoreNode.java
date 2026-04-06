package com.blockreality.fastdesign.client.node.impl.physics.result;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraft.nbt.CompoundTag;

/** C3-5: 結構健康度 */
@OnlyIn(Dist.CLIENT)
public class StructuralScoreNode extends BRNode {
    public StructuralScoreNode() {
        super("Structural Score", "結構健康度", "physics", NodeColor.PHYSICS);
        addInput("analysisResult", "分析結果", PortType.STRUCT, null);
        addOutput("score", PortType.INT);
        addOutput("grade", PortType.ENUM);
        addOutput("details", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        getOutput("score").setValue(100);
        getOutput("grade").setValue("A");
        getOutput("details").setValue(new CompoundTag());
    }

    @Override public String getTooltip() { return "綜合結構健康度評分 (A-F)"; }
    @Override public String typeId() { return "physics.result.StructuralScore"; }
}
