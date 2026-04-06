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
 * 將 Category C 的物理節點輸出映射到 BRConfig 的 ForgeConfigSpec 值。
 * 影響 PFSF GPU 引擎、SupportPathAnalyzer 等。
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
                applyToConfig(config, b.port.name(), value);
            } catch (Exception e) {
                // ForgeConfigSpec 驗證失敗時靜默跳過
            }
        }
        dirty = false;
    }

    @Override
    public void pull(BRConfig config) {
        // 從 ForgeConfigSpec 讀取當前值到節點
        for (PhysicsBinding b : bindings) {
            Object value = readFromConfig(config, b.port.name());
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

    private void applyToConfig(BRConfig config, String portName, Object value) {
        switch (portName) {
            case "maxIterations" -> {} // PFSF 內部常數，節點層記錄
            case "convergenceThreshold" -> {}
            case "omega" -> {}
            case "bfsMaxBlocks" -> config.structureBfsMaxBlocks.set(toInt(value));
            case "bfsMaxMs" -> config.structureBfsMaxMs.set(toInt(value));
            case "threadCount" -> config.physicsThreadCount.set(toInt(value));
            case "phiTens" -> config.rcFusionPhiTens.set((double) toFloat(value));
            case "phiShear" -> config.rcFusionPhiShear.set((double) toFloat(value));
            case "compBoost" -> config.rcFusionCompBoost.set((double) toFloat(value));
            case "curingTicks" -> config.rcFusionCuringTicks.set(toInt(value));
            default -> {}
        }
    }

    private Object readFromConfig(BRConfig config, String portName) {
        return switch (portName) {
            case "bfsMaxBlocks" -> config.structureBfsMaxBlocks.get();
            case "bfsMaxMs" -> config.structureBfsMaxMs.get();
            case "threadCount" -> config.physicsThreadCount.get();
            case "phiTens" -> (float) config.rcFusionPhiTens.get().doubleValue();
            case "phiShear" -> (float) config.rcFusionPhiShear.get().doubleValue();
            case "compBoost" -> (float) config.rcFusionCompBoost.get().doubleValue();
            default -> null;
        };
    }

    private static int toInt(Object v) {
        return v instanceof Number n ? n.intValue() : 0;
    }

    private static float toFloat(Object v) {
        return v instanceof Number n ? n.floatValue() : 0.0f;
    }

    private record PhysicsBinding(BRNode node, OutputPort port) {}
}
