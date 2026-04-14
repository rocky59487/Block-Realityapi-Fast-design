package com.blockreality.fastdesign.client.node.impl.physics.solver;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraft.nbt.CompoundTag;

/** C1-5: 物理精度分層 */
@OnlyIn(Dist.CLIENT)
public class PhysicsLODNode extends BRNode {
    public PhysicsLODNode() {
        super("Physics LOD", "物理精度分層", "physics", NodeColor.PHYSICS);
        addInput("fullPrecisionDist", "全精度距離", PortType.INT, 32).range(8, 128);
        addInput("standardDist", "標準距離", PortType.INT, 96).range(32, 256);
        addInput("coarseDist", "粗略距離", PortType.INT, 256).range(96, 512);
        addOutput("physicsLodSpec", PortType.STRUCT);
        // ─── Inspector 屬性 ───
        registerProperty("fullPrecisionDist", "全精度物理最大距離（格）：BeamStress + ForceEquilibrium");
        registerProperty("standardDist",      "標準精度物理最大距離（格）：SupportPathAnalyzer");
        registerProperty("coarseDist",        "粗略精度物理最大距離（格）：LoadPathEngine 僅路徑傳遞");
    }

    @Override
    public void evaluate() {
        CompoundTag spec = new CompoundTag();
        spec.putInt("fullPrecisionDist", getInput("fullPrecisionDist").getInt());
        spec.putInt("standardDist", getInput("standardDist").getInt());
        spec.putInt("coarseDist", getInput("coarseDist").getInt());
        getOutput("physicsLodSpec").setValue(spec);
    }

    @Override public String getTooltip() { return "依距離分級物理模擬精度"; }
    @Override public String typeId() { return "physics.solver.PhysicsLOD"; }
}
