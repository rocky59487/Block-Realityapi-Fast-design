package com.blockreality.fastdesign.client.node.impl.physics.collapse;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraft.nbt.CompoundTag;

/** C4-1: 崩塌配置 */
@OnlyIn(Dist.CLIENT)
public class CollapseConfigNode extends BRNode {
    public CollapseConfigNode() {
        super("Collapse Config", "崩塌配置", "physics", NodeColor.PHYSICS);
        addInput("maxQueueSize", "最大佇列", PortType.INT, 100000);
        addInput("blocksPerTick", "每 tick 方塊數", PortType.INT, 200).range(1, 1000);
        addInput("cascadeEnabled", "連鎖崩塌", PortType.BOOL, true);
        addOutput("collapseSpec", PortType.STRUCT);
        // ─── Inspector 屬性 ───
        registerProperty("maxQueueSize",   "崩塌事件佇列大小上限（超出時溢出到下一 tick）");
        registerProperty("blocksPerTick",  "每 tick 最多觸發崩塌的方塊數（影響爆炸響應速度）");
        registerProperty("cascadeEnabled", "啟用連鎖崩塌：相鄰失支撐方塊繼續觸發崩塌");
    }

    @Override
    public void evaluate() {
        CompoundTag spec = new CompoundTag();
        spec.putInt("maxQueueSize", getInput("maxQueueSize").getInt());
        spec.putInt("blocksPerTick", getInput("blocksPerTick").getInt());
        spec.putBoolean("cascadeEnabled", getInput("cascadeEnabled").getBool());
        getOutput("collapseSpec").setValue(spec);
    }

    @Override public String getTooltip() { return "崩塌模擬引擎配置"; }
    @Override public String typeId() { return "physics.collapse.CollapseConfig"; }
}
