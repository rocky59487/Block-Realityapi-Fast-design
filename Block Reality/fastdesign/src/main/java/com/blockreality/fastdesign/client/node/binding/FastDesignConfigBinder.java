package com.blockreality.fastdesign.client.node.binding;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import com.blockreality.fastdesign.config.FastDesignConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * FastDesign 配置綁定器 — 設計報告 §12.1 N3-5
 *
 * 將 Category D 的工具/UI 節點輸出映射到 FastDesignConfig 的 ForgeConfigSpec 值。
 */
@OnlyIn(Dist.CLIENT)
public class FastDesignConfigBinder implements IBinder<FastDesignConfig> {

    private static final Logger LOGGER = LogManager.getLogger("FdConfigBinder");

    private final List<ConfigBinding> bindings = new ArrayList<>();
    private boolean dirty = false;

    @Override
    public void bind(NodeGraph graph) {
        bindings.clear();
        for (BRNode node : graph.allNodes()) {
            if (!"tool".equals(node.category())) continue;
            for (OutputPort port : node.outputs()) {
                bindings.add(new ConfigBinding(node, port));
            }
        }
        LOGGER.info("FastDesignConfigBinder: {} bindings", bindings.size());
    }

    @Override
    public void apply(FastDesignConfig config) {
        for (ConfigBinding b : bindings) {
            if (!b.node.isEnabled()) continue;
            Object value = b.port.getRawValue();
            if (value == null) continue;

            try {
                applyValue(b.port.name(), value);
            } catch (Exception e) {
                // ForgeConfigSpec 範圍驗證失敗時靜默
            }
        }
        dirty = false;
    }

    @Override
    public void pull(FastDesignConfig config) {
        for (ConfigBinding b : bindings) {
            Object value = readValue(b.port.name());
            if (value != null) b.port.setValue(value);
        }
    }

    @Override
    public boolean isDirty() { return dirty; }

    @Override
    public void clearDirty() { dirty = false; }

    @Override
    public int bindingCount() { return bindings.size(); }

    public void markDirty() { dirty = true; }

    private void applyValue(String portName, Object value) {
        switch (portName) {
            case "maxBlocks", "maxSelectionVolume" ->
                    FastDesignConfig.MAX_SELECTION_VOLUME.set(toInt(value));
            case "undoDepth", "undoStackSize" ->
                    FastDesignConfig.UNDO_STACK_SIZE.set(toInt(value));
            case "hologramMaxBlocks" ->
                    FastDesignConfig.HOLOGRAM_MAX_BLOCKS.set(toInt(value));
            case "ghostAlpha", "hologramGhostAlpha" ->
                    FastDesignConfig.HOLOGRAM_GHOST_ALPHA.set((double) toFloat(value));
            case "hologramCullDistance" ->
                    FastDesignConfig.HOLOGRAM_CULL_DISTANCE.set((double) toFloat(value));
            case "rebarSpacingMax" ->
                    FastDesignConfig.REBAR_SPACING_MAX.set(toInt(value));
            default -> {}
        }
    }

    private Object readValue(String portName) {
        return switch (portName) {
            case "maxBlocks", "maxSelectionVolume" -> FastDesignConfig.MAX_SELECTION_VOLUME.get();
            case "undoDepth", "undoStackSize" -> FastDesignConfig.UNDO_STACK_SIZE.get();
            case "hologramMaxBlocks" -> FastDesignConfig.HOLOGRAM_MAX_BLOCKS.get();
            case "ghostAlpha" -> (float) FastDesignConfig.HOLOGRAM_GHOST_ALPHA.get().doubleValue();
            default -> null;
        };
    }

    private static int toInt(Object v) { return v instanceof Number n ? n.intValue() : 0; }
    private static float toFloat(Object v) { return v instanceof Number n ? n.floatValue() : 0f; }

    private record ConfigBinding(BRNode node, OutputPort port) {}
}
