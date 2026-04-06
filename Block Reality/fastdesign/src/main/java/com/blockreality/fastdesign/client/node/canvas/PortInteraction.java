package com.blockreality.fastdesign.client.node.canvas;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraft.client.gui.GuiGraphics;

import javax.annotation.Nullable;

/**
 * 端口拖曳連線互動 — 設計報告 §12.1 N2-4
 *
 * ★ review-fix ICReM-9: 修復已知問題：
 *   - Port 碰撞半徑隨 zoom 縮放
 *   - 端口位置使用共享常數
 *   - WireRenderer 快取避免每幀重建
 *   - 統一 findPortAt 方法消除重複代碼
 */
@OnlyIn(Dist.CLIENT)
public class PortInteraction {

    /** ★ ICReM-9: 共享端口佈局常數（消除 3 處硬編碼） */
    public static final float PORT_Y_START = 24.0f;
    public static final float PORT_SPACING = 20.0f;

    /** 畫布空間基礎碰撞半徑 */
    private static final float BASE_PORT_HIT_RADIUS = 8.0f;

    private final NodeGraph graph;
    private final CanvasTransform transform;
    /** ★ ICReM-9: 快取 WireRenderer，不再每幀 new */
    private final WireRenderer wireRenderer = new WireRenderer();

    private boolean dragging;
    @Nullable private OutputPort dragFrom;
    @Nullable private InputPort dragTo;
    private boolean dragFromOutput;

    public PortInteraction(NodeGraph graph, CanvasTransform transform) {
        this.graph = graph;
        this.transform = transform;
    }

    public boolean isDragging() { return dragging; }

    /**
     * ★ ICReM-9: 計算 zoom 自適應的碰撞半徑。
     * 低 zoom 時放大碰撞區域（容易點擊），高 zoom 時縮小（精確操作）。
     */
    private float getHitRadius() {
        float zoom = transform.zoom();
        if (zoom <= 0.01f) return BASE_PORT_HIT_RADIUS;
        // 碰撞半徑反比於 zoom，但限制在 [4, 16] 範圍內
        return Math.max(4.0f, Math.min(16.0f, BASE_PORT_HIT_RADIUS / zoom));
    }

    /**
     * 計算端口在畫布空間的位置。
     */
    public static float[] getPortCanvasPos(BRNode node, int portIndex, boolean isOutput) {
        float px = isOutput ? node.posX() + node.width() : node.posX();
        float py = node.posY() + PORT_Y_START + portIndex * PORT_SPACING;
        return new float[]{px, py};
    }

    /**
     * 嘗試在畫布座標 (cx, cy) 開始拖曳。
     */
    public boolean tryStartDrag(float cx, float cy, NodeGraph graph) {
        float hitRadSq = getHitRadius() * getHitRadius();

        for (BRNode node : graph.allNodes()) {
            // 輸出端口
            for (int i = 0; i < node.outputs().size(); i++) {
                float[] pos = getPortCanvasPos(node, i, true);
                if (distSq(cx, cy, pos[0], pos[1]) < hitRadSq) {
                    dragging = true;
                    dragFrom = node.outputs().get(i);
                    dragTo = null;
                    dragFromOutput = true;
                    return true;
                }
            }
            // 輸入端口
            for (int i = 0; i < node.inputs().size(); i++) {
                float[] pos = getPortCanvasPos(node, i, false);
                if (distSq(cx, cy, pos[0], pos[1]) < hitRadSq) {
                    dragging = true;
                    dragFrom = null;
                    dragTo = node.inputs().get(i);
                    dragFromOutput = false;
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 完成拖曳，嘗試建立連線。
     */
    @Nullable
    public Wire finishDrag(float cx, float cy, NodeGraph graph) {
        if (!dragging) return null;
        dragging = false;

        Wire result = null;
        float hitRadSq = getHitRadius() * getHitRadius();

        if (dragFromOutput && dragFrom != null) {
            InputPort target = findInputPortAt(cx, cy, graph, hitRadSq);
            if (target != null) {
                result = graph.connect(dragFrom, target);
            }
        } else if (!dragFromOutput && dragTo != null) {
            OutputPort target = findOutputPortAt(cx, cy, graph, hitRadSq);
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

        int color = 0xFFCCCCCC;
        float hitRadSq = getHitRadius() * getHitRadius();
        float cx = transform.toCanvasX((float) mouseX);
        float cy = transform.toCanvasY((float) mouseY);

        if (dragFromOutput && dragFrom != null) {
            float[] from = NodeWidgetRenderer.getPortScreenPos(dragFrom, transform);
            color = dragFrom.type().wireColor();

            // Check for valid connection target
            InputPort target = findInputPortAt(cx, cy, graph, hitRadSq);
            if (target != null) {
                if (!TypeChecker.canConnect(dragFrom.type(), target.type()) || dragFrom.owner() == target.owner()) {
                    color = 0xFFFF0000; // Red for invalid connection
                } else {
                    color = 0xFF00FF00; // Green for valid connection
                }
            }

            wireRenderer.renderTempWire(gui, from[0], from[1], mouseX, mouseY, color);
        } else if (!dragFromOutput && dragTo != null) {
            float[] to = NodeWidgetRenderer.getPortScreenPos(dragTo, transform);
            color = dragTo.type().wireColor();

            // Check for valid connection target
            OutputPort target = findOutputPortAt(cx, cy, graph, hitRadSq);
            if (target != null) {
                if (!TypeChecker.canConnect(target.type(), dragTo.type()) || dragTo.owner() == target.owner()) {
                    color = 0xFFFF0000; // Red for invalid connection
                } else {
                    color = 0xFF00FF00; // Green for valid connection
                }
            }

            wireRenderer.renderTempWire(gui, mouseX, mouseY, to[0], to[1], color);
        }
    }

    // ─── 統一端口搜尋 ───

    @Nullable
    private InputPort findInputPortAt(float cx, float cy, NodeGraph graph, float hitRadSq) {
        for (BRNode node : graph.allNodes()) {
            for (int i = 0; i < node.inputs().size(); i++) {
                float[] pos = getPortCanvasPos(node, i, false);
                if (distSq(cx, cy, pos[0], pos[1]) < hitRadSq) {
                    return node.inputs().get(i);
                }
            }
        }
        return null;
    }

    @Nullable
    private OutputPort findOutputPortAt(float cx, float cy, NodeGraph graph, float hitRadSq) {
        for (BRNode node : graph.allNodes()) {
            for (int i = 0; i < node.outputs().size(); i++) {
                float[] pos = getPortCanvasPos(node, i, true);
                if (distSq(cx, cy, pos[0], pos[1]) < hitRadSq) {
                    return node.outputs().get(i);
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
