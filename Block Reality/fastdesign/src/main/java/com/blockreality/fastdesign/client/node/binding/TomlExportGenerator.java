package com.blockreality.fastdesign.client.node.binding;

import com.blockreality.fastdesign.client.node.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * TOML 匯出生成器 — 設計報告 §12.1 N3-6, E1-1 ConfigExport
 *
 * 從節點圖收集所有配置值，生成 ForgeConfigSpec 格式的 TOML 檔案。
 * 輸出路徑：config/blockreality-common.toml, config/fastdesign-common.toml
 */
public final class TomlExportGenerator {

    private static final Logger LOGGER = LogManager.getLogger("TomlExport");

    private TomlExportGenerator() {}

    /**
     * 從節點圖匯出 TOML 配置。
     */
    public static String generate(NodeGraph graph) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Block Reality Node Graph Configuration Export\n");
        sb.append("# Generated from node graph: ").append(graph.name()).append("\n");
        sb.append("# Date: ").append(new Date()).append("\n\n");

        // 按 category 分組
        Map<String, List<BRNode>> byCategory = new LinkedHashMap<>();
        for (BRNode node : graph.topologicalOrder()) {
            if (!node.isEnabled()) continue;
            byCategory.computeIfAbsent(node.category(), k -> new ArrayList<>()).add(node);
        }

        for (var entry : byCategory.entrySet()) {
            sb.append("[").append(entry.getKey()).append("]\n");
            for (BRNode node : entry.getValue()) {
                sb.append("# ").append(node.displayName()).append("\n");
                for (InputPort port : node.inputs()) {
                    if (port.isConnected()) continue; // 連線的端口值由上游決定
                    Object value = port.getRawValue();
                    if (value == null) continue;

                    String key = node.typeId().replace('.', '_') + "." + port.name();
                    sb.append(key).append(" = ").append(toTomlValue(value, port.type())).append("\n");
                }
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * 生成並寫入檔案。
     */
    public static void exportToFile(NodeGraph graph, Path path) throws IOException {
        String content = generate(graph);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
        LOGGER.info("TOML 匯出完成：{}", path);
    }

    /**
     * 從 MutableRenderConfig 匯出渲染設定 TOML。
     */
    public static String generateFromRenderConfig(MutableRenderConfig config) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Block Reality Render Configuration\n\n");

        sb.append("[pipeline]\n");
        sb.append("shadowMapResolution = ").append(config.shadowMapResolution).append("\n");
        sb.append("shadowMaxDistance = ").append(config.shadowMaxDistance).append("\n");
        sb.append("hdrEnabled = ").append(config.hdrEnabled).append("\n");
        sb.append("ssaoEnabled = ").append(config.ssaoEnabled).append("\n");
        sb.append("ssaoRadius = ").append(config.ssaoRadius).append("\n");
        sb.append("gtaoEnabled = ").append(config.gtaoEnabled).append("\n");

        sb.append("\n[postfx]\n");
        sb.append("taaEnabled = ").append(config.taaEnabled).append("\n");
        sb.append("ssrEnabled = ").append(config.ssrEnabled).append("\n");
        sb.append("volumetricEnabled = ").append(config.volumetricEnabled).append("\n");
        sb.append("bloomThreshold = ").append(config.bloomThreshold).append("\n");
        sb.append("bloomIntensity = ").append(config.bloomIntensity).append("\n");
        sb.append("dofEnabled = ").append(config.dofEnabled).append("\n");
        sb.append("ssgiEnabled = ").append(config.ssgiEnabled).append("\n");

        sb.append("\n[atmosphere]\n");
        sb.append("cloudEnabled = ").append(config.cloudEnabled).append("\n");
        sb.append("fogEnabled = ").append(config.fogEnabled).append("\n");
        sb.append("weatherEnabled = ").append(config.weatherEnabled).append("\n");

        sb.append("\n[lod]\n");
        sb.append("lodMaxDistance = ").append(config.lodMaxDistance).append("\n");
        sb.append("lodVramBudgetMb = ").append(config.lodVramBudgetMb).append("\n");

        return sb.toString();
    }

    // ─── TOML 值格式化 ───

    private static String toTomlValue(Object value, PortType type) {
        if (value instanceof Boolean b) return b.toString();
        if (value instanceof Integer i) return i.toString();
        if (value instanceof Float f) return String.format("%.4f", f);
        if (value instanceof Double d) return String.format("%.4f", d);
        if (value instanceof String s) return "\"" + s.replace("\"", "\\\"") + "\"";
        if (value instanceof Enum<?> e) return "\"" + e.name() + "\"";
        if (value instanceof float[] arr) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(String.format("%.4f", arr[i]));
            }
            return sb.append("]").toString();
        }
        return "\"" + value + "\"";
    }
}
