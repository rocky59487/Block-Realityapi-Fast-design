package com.blockreality.fastdesign.client.node.canvas;

import com.blockreality.fastdesign.client.node.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 節點 Widget 渲染器 — 設計報告 §3.4, §12.1 N2-2
 *
 * 渲染單個節點的外觀：
 *   - 圓角矩形外框
 *   - 類別色 header
 *   - 輸入/輸出端口
 *   - 內嵌值顯示
 *   - 選中高亮 / 停用灰化
 */
public class NodeWidgetRenderer {

    /** ★ FTB-STYLE: 節點色調對齊 FTB 深色 UI — 更低飽和度、更清晰邊框 */
    private static final int NODE_BG = 0xF0181824;
    private static final int NODE_BORDER = 0xFF2E2E48;
    private static final int NODE_SELECTED_BORDER = 0xFFE8872D; // FTB 橙色系
    private static final int NODE_DISABLED_OVERLAY = 0x88000000;
    private static final int PORT_RADIUS = 5;
    private static final int PORT_Y_START = 24;
    private static final int PORT_SPACING = 20;
    private static final int TEXT_COLOR = 0xFFE0E0E0;
    private static final int TEXT_DIM = 0xFF808090;
    private static final int VALUE_COLOR = 0xFF88BBDD;

    public void renderNode(GuiGraphics gui, BRNode node, CanvasTransform transform,
                           boolean selected, int mouseX, int mouseY) {
        float sx = transform.toScreenX(node.posX());
        float sy = transform.toScreenY(node.posY());
        float sw = transform.toScreenSize(node.width());
        float sh = transform.toScreenSize(node.height());

        // 太小不畫
        if (sw < 8 || sh < 6) return;
        if (sx + sw < 0 || sy + sh < 0) return; // 離屏剔除

        int x = (int) sx, y = (int) sy, w = (int) sw, h = (int) sh;

        // ─── 陰影（4px 偏移） ───
        // ★ review-fix ICReM-8: 節點陰影增加層次感
        gui.fill(x + 3, y + 3, x + w + 3, y + h + 3, 0x40000000);

        // ─── 背景 ───
        gui.fill(x, y, x + w, y + h, NODE_BG);

        // ─── Header ───
        int headerH = Math.max(4, (int) transform.toScreenSize(22));
        int headerColor = selected ? node.color().headerHighlightColor() : node.color().headerColor();
        gui.fill(x, y, x + w, y + headerH, headerColor);

        // ─── 邊框 ───
        int borderColor = selected ? NODE_SELECTED_BORDER : NODE_BORDER;
        // 上
        gui.fill(x, y, x + w, y + 1, borderColor);
        // 下
        gui.fill(x, y + h - 1, x + w, y + h, borderColor);
        // 左
        gui.fill(x, y, x + 1, y + h, borderColor);
        // 右
        gui.fill(x + w - 1, y, x + w, y + h, borderColor);

        // 如果太小就不畫文字
        if (transform.zoom() < 0.3f) return;

        Font font = Minecraft.getInstance().font;

        // ─── 標題 ───
        String title = node.displayName();
        if (title.length() > 20) title = title.substring(0, 18) + "..";
        float textScale = Math.min(1.0f, transform.zoom());
        gui.drawString(font, title, x + 4, y + 4, TEXT_COLOR);

        if (node.isCollapsed()) return;

        // ─── 輸入端口 ───
        int portIdx = 0;
        for (InputPort port : node.inputs()) {
            int py = y + (int) transform.toScreenSize(PORT_Y_START + portIdx * PORT_SPACING);
            int pr = Math.max(2, (int) transform.toScreenSize(PORT_RADIUS));

            // 端口圓點
            int portColor = port.type().wireColor();
            // ★ review-fix ICReM-8: 端口滑鼠懸停高亮
            boolean portHover = Math.abs(mouseX - x) < pr + 4 && Math.abs(mouseY - py) < pr + 4;
            if (portHover) {
                // 光暈效果
                gui.fill(x - pr - 2, py - pr - 2, x + pr + 2, py + pr + 2,
                    (0x44 << 24) | (portColor & 0x00FFFFFF));
            }
            if (port.isConnected()) {
                gui.fill(x - pr, py - pr, x + pr, py + pr, portColor);
            } else {
                // 空心（畫邊框）
                gui.fill(x - pr, py - pr, x + pr, py - pr + 1, portColor);
                gui.fill(x - pr, py + pr - 1, x + pr, py + pr, portColor);
                gui.fill(x - pr, py - pr, x - pr + 1, py + pr, portColor);
                gui.fill(x + pr - 1, py - pr, x + pr, py + pr, portColor);
            }

            // 端口名稱 + 值
            if (transform.zoom() >= 0.5f) {
                String label = port.displayName();
                gui.drawString(font, label, x + pr + 4, py - 4, TEXT_DIM);

                // 顯示當前值（未連線時）
                if (!port.isConnected() && port.type().isNumeric()) {
                    String val = formatValue(port);
                    int valX = x + w - font.width(val) - 4;
                    gui.drawString(font, val, valX, py - 4, VALUE_COLOR);
                }
            }
            portIdx++;
        }

        // ─── 輸出端口 ───
        portIdx = 0;
        for (OutputPort port : node.outputs()) {
            int py = y + (int) transform.toScreenSize(PORT_Y_START + portIdx * PORT_SPACING);
            int pr = Math.max(2, (int) transform.toScreenSize(PORT_RADIUS));
            int px = x + w;

            int portColor = port.type().wireColor();
            gui.fill(px - pr, py - pr, px + pr, py + pr, portColor);

            if (transform.zoom() >= 0.5f) {
                String label = port.displayName();
                int labelX = px - pr - font.width(label) - 4;
                gui.drawString(font, label, labelX, py - 4, TEXT_DIM);
            }
            portIdx++;
        }

        // ─── 停用覆蓋 ───
        if (!node.isEnabled()) {
            gui.fill(x, y, x + w, y + h, NODE_DISABLED_OVERLAY);
        }

        // ─── 評估時間指示（超過 1ms 顯示紅點） ───
        if (node.lastEvalTimeNs() > 1_000_000) {
            gui.fill(x + w - 6, y + 2, x + w - 2, y + 6, 0xFFFF4444);
        }
    }

    /**
     * 取得端口在螢幕上的位置（用於連線渲染）。
     */
    public static float[] getPortScreenPos(NodePort port, CanvasTransform transform) {
        BRNode node = port.owner();
        if (node == null) return new float[]{0, 0};

        int idx;
        float px;
        if (port instanceof InputPort) {
            idx = node.inputs().indexOf(port);
            px = node.posX();
        } else {
            idx = node.outputs().indexOf(port);
            px = node.posX() + node.width();
        }
        float py = node.posY() + PORT_Y_START + idx * PORT_SPACING;

        return new float[]{
                transform.toScreenX(px),
                transform.toScreenY(py)
        };
    }

    private String formatValue(InputPort port) {
        Object val = port.getRawValue();
        if (val instanceof Float f) return String.format("%.2f", f);
        if (val instanceof Integer i) return i.toString();
        if (val instanceof Boolean b) return b ? "ON" : "OFF";
        return "";
    }
}
