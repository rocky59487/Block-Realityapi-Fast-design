package com.blockreality.fastdesign.client.node.panel;

import com.blockreality.fastdesign.client.node.*;
import com.blockreality.fastdesign.client.node.binding.MutableRenderConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 雙向同步 — 設計報告 §11.3, §12.1 N5-2
 *
 * 簡化面板的滑桿/勾選 ↔ 節點圖的端口值雙向綁定。
 * - 簡化面板修改品質 → 節點圖對應節點更新
 * - 節點圖手動連線 → 簡化面板對應項目變為「自訂」
 */
public class BidirectionalSync {

    private static final Logger LOGGER = LogManager.getLogger("BidirectionalSync");

    private NodeGraph graph;
    private MutableRenderConfig config;

    public BidirectionalSync(NodeGraph graph) {
        this.graph = graph;
        this.config = MutableRenderConfig.getInstance();
    }

    /**
     * 從簡化面板推送值到節點圖。
     * 找到對應的節點輸入端口並設定值。
     */
    public void pushToGraph(String fieldName, Object value) {
        for (BRNode node : graph.allNodes()) {
            InputPort port = node.getInput(fieldName);
            if (port != null) {
                port.setLocalValue(value);
                LOGGER.debug("Sync panel→graph: {}.{} = {}", node.displayName(), fieldName, value);
                return;
            }
        }
    }

    /**
     * 從節點圖拉取值到簡化面板（透過 MutableRenderConfig）。
     */
    public void pullFromGraph() {
        for (BRNode node : graph.allNodes()) {
            if (!"render".equals(node.category())) continue;
            for (OutputPort port : node.outputs()) {
                Object value = port.getRawValue();
                if (value == null) continue;
                applyToConfig(port.name(), value);
            }
        }
    }

    /**
     * 檢查某個設定是否被節點圖手動覆蓋（有連線 = 自訂）。
     */
    public boolean isCustomOverridden(String fieldName) {
        for (BRNode node : graph.allNodes()) {
            InputPort port = node.getInput(fieldName);
            if (port != null && port.isConnected()) return true;
        }
        return false;
    }

    private void applyToConfig(String name, Object value) {
        switch (name) {
            case "ssaoEnabled" -> config.ssaoEnabled = toBool(value);
            case "ssrEnabled" -> config.ssrEnabled = toBool(value);
            case "taaEnabled" -> config.taaEnabled = toBool(value);
            case "volumetricEnabled" -> config.volumetricEnabled = toBool(value);
            case "shadowRes" -> config.shadowMapResolution = toInt(value);
            case "lodMaxDist" -> config.lodMaxDistance = toFloat(value);
            case "bloomEnabled" -> { if (!toBool(value)) config.bloomIntensity = 0; }
            case "cloudEnabled" -> config.cloudEnabled = toBool(value);
            default -> {}
        }
    }

    private static boolean toBool(Object v) { return v instanceof Boolean b ? b : v instanceof Number n && n.intValue() != 0; }
    private static int toInt(Object v) { return v instanceof Number n ? n.intValue() : 0; }
    private static float toFloat(Object v) { return v instanceof Number n ? n.floatValue() : 0f; }
}
