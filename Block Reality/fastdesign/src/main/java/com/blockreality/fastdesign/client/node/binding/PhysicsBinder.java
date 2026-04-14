package com.blockreality.fastdesign.client.node.binding;

import com.blockreality.api.config.BRConfig;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import com.blockreality.fastdesign.client.node.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * 物理綁定器 — 設計報告 §12.1 N3-3
 *
 * <p>將 Category C 的物理節點輸出映射到 {@link BRConfig} 的 ForgeConfigSpec 值，
 * 影響 PFSF GPU 引擎、SupportPathAnalyzer、CollapseManager、LOD 分層等。
 *
 * <p>路由鍵格式：{@code nodeTypeId + "." + portName}，例如
 * {@code "physics.solver.ForceEquilibrium.maxIterations"}。
 * 這確保不同節點的同名輸出埠（如 {@code maxIterations}）能正確路由到不同的 BRConfig 欄位。
 */
@OnlyIn(Dist.CLIENT)
public class PhysicsBinder implements IBinder<BRConfig> {

    private static final Logger LOGGER = LogManager.getLogger("PhysicsBinder");

    private final List<PhysicsBinding> bindings = new ArrayList<>();
    private boolean dirty = false;

    @Override
    public void bind(NodeGraph graph) {
        bindings.clear();
        for (BRNode node : graph.allNodes()) {
            if (!"physics".equals(node.category())) continue;
            for (OutputPort port : node.outputs()) {
                bindings.add(new PhysicsBinding(node, port));
            }
        }
        LOGGER.info("PhysicsBinder: {} bindings", bindings.size());
    }

    @Override
    public void apply(BRConfig config) {
        for (PhysicsBinding b : bindings) {
            if (!b.node.isEnabled()) continue;
            Object value = b.port.getRawValue();
            if (value == null) continue;

            try {
                String routingKey = b.node.typeId() + "." + b.port.name();
                applyToConfig(config, routingKey, value);
            } catch (Exception e) {
                // ForgeConfigSpec 驗證失敗時靜默跳過
            }
        }
        dirty = false;
    }

    @Override
    public void pull(BRConfig config) {
        // 從 ForgeConfigSpec 讀取當前值到節點輸出埠（初始化同步）
        for (PhysicsBinding b : bindings) {
            String routingKey = b.node.typeId() + "." + b.port.name();
            Object value = readFromConfig(config, routingKey);
            if (value != null) {
                b.port.setValue(value);
            }
        }
    }

    @Override
    public boolean isDirty() { return dirty; }

    @Override
    public void clearDirty() { dirty = false; }

    @Override
    public int bindingCount() { return bindings.size(); }

    public void markDirty() { dirty = true; }

    // ─── 路由表：nodeTypeId.portName → BRConfig setter ───────────────────────

    private void applyToConfig(BRConfig config, String key, Object value) {
        switch (key) {

            // ── ForceEquilibriumNode ──────────────────────────────────────────
            case "physics.solver.ForceEquilibrium.enabled" ->
                BRConfig.setPFSFEnabled(toBool(value));
            case "physics.solver.ForceEquilibrium.maxIterations" ->
                BRConfig.setPFSFMaxIterations(toInt(value));
            case "physics.solver.ForceEquilibrium.omega" ->
                BRConfig.setPFSFOmega(toDouble(value));
            case "physics.solver.ForceEquilibrium.convergenceThreshold" ->
                BRConfig.setPFSFConvergenceThreshold(toDouble(value));
            case "physics.solver.ForceEquilibrium.autoOmega" ->
                BRConfig.setPFSFPCGEnabled(toBool(value));   // autoOmega ≈ hybrid PCG
            // warmStartEntries → 無對應 BRConfig 欄位，靜默跳過

            // ── BeamAnalysisNode ──────────────────────────────────────────────
            case "physics.solver.BeamAnalysis.maxBlocks" ->
                config.structureBfsMaxBlocks.set(toInt(value));

            // ── CoarseFEMNode ─────────────────────────────────────────────────
            case "physics.solver.CoarseFEM.interval" ->
                config.coarseFEMInterval.set(toInt(value));
            case "physics.solver.CoarseFEM.maxIterations" ->
                BRConfig.setPFSFMaxIterations(toInt(value));
            case "physics.solver.CoarseFEM.omega" ->
                BRConfig.setPFSFOmega(toDouble(value));
            case "physics.solver.CoarseFEM.convergenceThreshold" ->
                BRConfig.setPFSFConvergenceThreshold(toDouble(value));

            // ── PhysicsLODNode ────────────────────────────────────────────────
            case "physics.solver.PhysicsLOD.fullPrecisionDist" ->
                config.lodFullPrecisionDistance.set(toInt(value));
            case "physics.solver.PhysicsLOD.standardDist" ->
                config.lodStandardDistance.set(toInt(value));
            case "physics.solver.PhysicsLOD.coarseDist" ->
                config.lodCoarseDistance.set(toInt(value));

            // ── CollapseConfigNode ────────────────────────────────────────────
            case "physics.collapse.CollapseConfig.blocksPerTick" ->
                BRConfig.setMaxCollapsePerTick(toInt(value));
            case "physics.collapse.CollapseConfig.cascadeEnabled" ->
                BRConfig.setCascadeEnabled(toBool(value));
            // maxQueueSize → no direct hot-reload setter, skip

            // ── RC Fusion (legacy portName-only keys, kept for backward compat) ──
            case "bfsMaxBlocks" -> config.structureBfsMaxBlocks.set(toInt(value));
            case "bfsMaxMs"     -> config.structureBfsMaxMs.set(toInt(value));
            case "threadCount"  -> config.physicsThreadCount.set(toInt(value));
            case "phiTens"      -> config.rcFusionPhiTens.set(toDouble(value));
            case "phiShear"     -> config.rcFusionPhiShear.set(toDouble(value));
            case "compBoost"    -> config.rcFusionCompBoost.set(toDouble(value));
            case "curingTicks"  -> config.rcFusionCuringTicks.set(toInt(value));

            default -> {}
        }
    }

    private Object readFromConfig(BRConfig config, String key) {
        return switch (key) {
            // ForceEquilibriumNode pull-back
            case "physics.solver.ForceEquilibrium.enabled" ->
                BRConfig.isPFSFEnabled();
            case "physics.solver.ForceEquilibrium.maxIterations" ->
                BRConfig.getPFSFMaxIterations();
            case "physics.solver.ForceEquilibrium.omega" ->
                (float) BRConfig.getPFSFOmega();
            case "physics.solver.ForceEquilibrium.convergenceThreshold" ->
                (float) BRConfig.getPFSFConvergenceThreshold();
            case "physics.solver.ForceEquilibrium.autoOmega" ->
                BRConfig.isPFSFPCGEnabled();

            // BeamAnalysisNode pull-back
            case "physics.solver.BeamAnalysis.maxBlocks" ->
                config.structureBfsMaxBlocks.get();

            // CoarseFEMNode pull-back
            case "physics.solver.CoarseFEM.interval" ->
                config.coarseFEMInterval.get();
            case "physics.solver.CoarseFEM.maxIterations" ->
                BRConfig.getPFSFMaxIterations();
            case "physics.solver.CoarseFEM.omega" ->
                (float) BRConfig.getPFSFOmega();

            // PhysicsLODNode pull-back
            case "physics.solver.PhysicsLOD.fullPrecisionDist" ->
                config.lodFullPrecisionDistance.get();
            case "physics.solver.PhysicsLOD.standardDist" ->
                config.lodStandardDistance.get();
            case "physics.solver.PhysicsLOD.coarseDist" ->
                config.lodCoarseDistance.get();

            // CollapseConfigNode pull-back
            case "physics.collapse.CollapseConfig.blocksPerTick" ->
                BRConfig.getMaxCollapsePerTick();
            case "physics.collapse.CollapseConfig.cascadeEnabled" ->
                BRConfig.isCascadeEnabled();

            // Legacy keys
            case "bfsMaxBlocks" -> config.structureBfsMaxBlocks.get();
            case "bfsMaxMs"     -> config.structureBfsMaxMs.get();
            case "threadCount"  -> config.physicsThreadCount.get();
            case "phiTens"      -> (float) config.rcFusionPhiTens.get().doubleValue();
            case "phiShear"     -> (float) config.rcFusionPhiShear.get().doubleValue();
            case "compBoost"    -> (float) config.rcFusionCompBoost.get().doubleValue();

            default -> null;
        };
    }

    // ─── 型別轉換輔助 ───────────────────────────────────────────────────────

    private static int toInt(Object v) {
        return v instanceof Number n ? n.intValue() : 0;
    }

    private static float toFloat(Object v) {
        return v instanceof Number n ? n.floatValue() : 0.0f;
    }

    private static double toDouble(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static boolean toBool(Object v) {
        return Boolean.TRUE.equals(v);
    }

    private record PhysicsBinding(BRNode node, OutputPort port) {}
}
