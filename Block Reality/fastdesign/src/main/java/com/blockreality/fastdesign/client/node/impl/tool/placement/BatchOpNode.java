package com.blockreality.fastdesign.client.node.impl.tool.placement;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraft.nbt.CompoundTag;

/** D2-2: 批量操作 */
@OnlyIn(Dist.CLIENT)
public class BatchOpNode extends BRNode {
    public BatchOpNode() {
        super("Batch Op", "批量操作", "tool", NodeColor.TOOL);
        addInput("op", "操作", PortType.ENUM, "FILL");
        addInput("undoDepth", "復原深度", PortType.INT, 32);
        addOutput("batchSpec", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        CompoundTag spec = new CompoundTag();
        String op = getInput("op").getValue();
        spec.putString("op", op != null ? op : "FILL");
        spec.putInt("undoDepth", getInput("undoDepth").getInt());
        getOutput("batchSpec").setValue(spec);
    }

    @Override public String getTooltip() { return "批量填充/替換/清除操作"; }
    @Override public String typeId() { return "tool.placement.BatchOp"; }
}
