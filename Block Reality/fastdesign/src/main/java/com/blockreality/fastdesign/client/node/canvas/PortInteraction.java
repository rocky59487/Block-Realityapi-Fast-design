package com.blockreality.fastdesign.client.node.canvas;

import com.blockreality.fastdesign.client.node.*;
import net.minecraft.client.gui.GuiGraphics;

import javax.annotation.Nullable;

/**
 * 端口拖曳連線互動 — 設計報告 §12.1 N2-4
 *
 * 從一個端口拖曳到另一個端口建立 Wire。
 * 支援從 Output 拖到 Input，也支援從 Input 反向拖曳。
 */
public class PortInteraction {

    private static final float PORT_HIT_RADIUS = 8.0f;

    private final NodeGraph graph;
    private final CanvasTransform transform;

    private boolean dragging;
    @Nullable private OutputPort dragFrom;
    @Nullable private InputPort dragTo;
    private boolean dragFromOutput; // true=從output開始拖

    public PortInteraction(NodeGraph graph, CanvasTransform transform) {
        this.graph = graph;
        this.transform = transform;
    }

    public boolean isDragging() { return dragging; }

    /**
     * 嘗試在畫布座標 (cx, cy) 開始拖曳。
     * @return true 如果命中了某個端口
     */
    public boolean tryStartDrag(float cx, float cy, NodeGraph graph) {
        // 搜索所有端口
        for (BRNode node : graph.allNodes()) {
            // 檢查輸出端口
            for (int i = 0; i < node.outputs().size(); i++) {
                OutputPort port = node.outputs().get(i);
                float px = node.posX() + node.width();
                float py = node.posY() + 24 + i * 20;
                if (distSq(cx, cy, px, py) < PORT_HIT_RADIUS * PORT_HIT_RADIUS) {
                    dragging = true;
                    dragFrom = port;
                    dragTo = null;
                    dragFromOutput = true;
                    return true;
                }
            }
            // 檢查輸入端口
            for (int i = 0; i < node.inputs().size(); i++) {
                InputPort port = node.inputs().get(i);
                float px = node.posX();
                float py = node.posY() + 24 + i * 20;
                if (distSq(cx, cy, px, py) < PORT_HIT_RADIUS * PORT_HIT_RADIUS) {
                    dragging = true;
                    dragFrom = null;
                    dragTo = port;
                    dragFromOutput = false;
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 完成拖曳，嘗試建立連線。
     * @return 新建立的 Wire，或 null
     */
    @Nullable
    public Wire finishDrag(float cx, float cy, NodeGraph graph) {
        if (!dragging) return null;
        dragging = false;

        Wire result = null;

        if (dragFromOutput && dragFrom != null) {
            // 從 Output 拖到 Input
            InputPort target = findInputPortAt(cx, cy, graph);
            if (target != null) {
                result = graph.connect(dragFrom, target);
            }
        } else if (!dragFromOutput && dragTo != null) {
            // 從 Input 反向拖到 Output
            OutputPort target = findOutputPortAt(cx, cy, graph);
            if (target != null) {
                result = graph.connect(target, dragTo);
            }
        }

        dragFrom = null;
        dragTo = null;
        return result;
    }

    /**
     * 渲染拖曳中的臨時連線。
     */
    public void renderDragWire(GuiGraphics gui, int mouseX, int mouseY, CanvasTransform transform) {
        if (!dragging) return;

        WireRenderer wireRenderer = new WireRenderer();
        int color = 0xFFCCCCCC;

        if (dragFromOutput && dragFrom != null) {
            float[] from = NodeWidgetRenderer.getPortScreenPos(dragFrom, transform);
            color = dragFrom.type().wireColor();
            wireRenderer.renderTempWire(gui, from[0], from[1], mouseX, mouseY, color);
        } else if (!dragFromOutput && dragTo != null) {
            float[] to = NodeWidgetRenderer.getPortScreenPos(dragTo, transform);
            color = dragTo.type().wireColor();
            wireRenderer.renderTempWire(gui, mouseX, mouseY, to[0], to[1], color);
        }
    }

    // ─── 端口搜尋 ───

    @Nullable
    private InputPort findInputPortAt(float cx, float cy, NodeGraph graph) {
        for (BRNode node : graph.allNodes()) {
            for (int i = 0; i < node.inputs().size(); i++) {
                InputPort port = node.inputs().get(i);
                float px = node.posX();
                float py = node.posY() + 24 + i * 20;
                if (distSq(cx, cy, px, py) < PORT_HIT_RADIUS * PORT_HIT_RADIUS) {
                    return port;
                }
            }
        }
        return null;
    }

    @Nullable
    private OutputPort findOutputPortAt(float cx, float cy, NodeGraph graph) {
        for (BRNode node : graph.allNodes()) {
            for (int i = 0; i < node.outputs().size(); i++) {
                OutputPort port = node.outputs().get(i);
                float px = node.posX() + node.width();
                float py = node.posY() + 24 + i * 20;
                if (distSq(cx, cy, px, py) < PORT_HIT_RADIUS * PORT_HIT_RADIUS) {
                    return port;
                }
            }
        }
        return null;
    }

    private static float distSq(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2, dy = y1 - y2;
        return dx * dx + dy * dy;
    }
}
