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

    /** ★ UI/UX: 偏向 Create/Grasshopper 模組的原生簡潔風格 */
    private static final int NODE_BG = 0xEE202022; // 微透明深灰
    private static final int NODE_BORDER = 0xFF353538;
    private static final int NODE_SELECTED_BORDER = 0xFFFFF1A5; // 草蜢風格亮黃
    private static final int NODE_DISABLED_OVERLAY = 0xAA101010;
    private static final int PORT_RADIUS = 5;
    private static final int PORT_Y_START = 24;
    private static final int PORT_SPACING = 20;
    private static final int TEXT_COLOR = 0xFFF0F0F0;
    private static final int TEXT_DIM = 0xFFA0A0A5;
    private static final int VALUE_COLOR = 0xFF55CCFF; // 更亮的數值顏色

    public void renderNode(GuiGraphics gui, BRNode node, CanvasTransform transform,
                           boolean selected, int mouseX, int mouseY) {
        // 加上動畫縮放
        float scale = node.animScale();
        float width = node.width() * scale;
        float height = node.height() * scale;
        // 為了讓縮放以節點中心為準，稍微偏移位置
        float cx = node.posX() + node.width() / 2.0f;
        float cy = node.posY() + node.height() / 2.0f;

        float sx = transform.toScreenX(cx - width / 2.0f);
        float sy = transform.toScreenY(cy - height / 2.0f);
        float sw = transform.toScreenSize(width);
        float sh = transform.toScreenSize(height);

        // 太小不畫
        if (sw < 8 || sh < 6) return;
        if (sx + sw < 0 || sy + sh < 0) return; // 離屏剔除

        int x = (int) sx, y = (int) sy, w = (int) sw, h = (int) sh;

        // ─── 陰影（柔和化） ───
        gui.fill(x + 2, y + 2, x + w + 4, y + h + 4, 0x20000000);
        gui.fill(x + 4, y + 4, x + w + 6, y + h + 6, 0x10000000);

        // ─── 背景 ───
        gui.fill(x, y, x + w, y + h, NODE_BG);

        // ─── Header ───
        int headerH = Math.max(4, (int) transform.toScreenSize(22));
        int headerColor = selected ? node.color().headerHighlightColor() : node.color().headerColor();
        // 如果節點被停用，Header 變暗
        if (!node.isEnabled()) {
            headerColor = (headerColor & 0x00FFFFFF) | 0x88000000;
        }
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

                // 必填但未連接：紅色警告點或框
                if (port.isRequired()) {
                    gui.fill(x - pr - 2, py - pr - 2, x + pr + 2, py - pr, 0xFFFF0000);
                    gui.fill(x - pr - 2, py + pr, x + pr + 2, py + pr + 2, 0xFFFF0000);
                    gui.fill(x - pr - 2, py - pr, x - pr, py + pr, 0xFFFF0000);
                    gui.fill(x + pr, py - pr, x + pr + 2, py + pr, 0xFFFF0000);
                }
            }

            // 端口名稱 + 值
            if (transform.zoom() >= 0.5f) {
                String label = port.displayName();
                int labelColor = (port.isRequired() && !port.isConnected()) ? 0xFFFFAA00 : TEXT_DIM;
                gui.drawString(font, label, x + pr + 4, py - 4, labelColor);

                // ★ 草蜢風格：未連線且支援的類型，渲染 Inline Slider / Checkbox
                if (!port.isConnected()) {
                    if (port.type() == PortType.FLOAT || port.type() == PortType.INT) {
                        // Slider 背景
                        int sliderW = (int) transform.toScreenSize(40);
                        int sliderH = (int) transform.toScreenSize(10);
                        int sliderX = x + w - sliderW - 8;
                        int sliderY = py - sliderH / 2;

                        // 背景底色
                        gui.fill(sliderX, sliderY, sliderX + sliderW, sliderY + sliderH, 0xFF18181A);
                        // 邊框
                        gui.fill(sliderX, sliderY, sliderX + sliderW, sliderY + 1, 0xFF353538);
                        gui.fill(sliderX, sliderY + sliderH - 1, sliderX + sliderW, sliderY + sliderH, 0xFF353538);
                        gui.fill(sliderX, sliderY, sliderX + 1, sliderY + sliderH, 0xFF353538);
                        gui.fill(sliderX + sliderW - 1, sliderY, sliderX + sliderW, sliderY + sliderH, 0xFF353538);

                        // Slider 進度條
                        float min = port.min() == Float.NEGATIVE_INFINITY ? 0 : port.min();
                        float max = port.max() == Float.POSITIVE_INFINITY ? 100 : port.max();
                        float val = port.getRawValue() instanceof Number n ? n.floatValue() : 0f;

                        if (max <= min) max = min + 1;
                        float pct = Math.max(0, Math.min(1, (val - min) / (max - min)));
                        int fillW = (int) (sliderW * pct);

                        // 填色區域
                        if (fillW > 0) {
                            gui.fill(sliderX + 1, sliderY + 1, sliderX + fillW, sliderY + sliderH - 1, VALUE_COLOR);
                        }

                        // 數值文字
                        String valStr = formatValue(port);
                        gui.drawString(font, valStr, sliderX + sliderW / 2 - font.width(valStr) / 2, sliderY + 1, 0xFFFFFFFF);
                    } else if (port.type() == PortType.BOOL) {
                        // Toggle Checkbox
                        int boxSize = (int) transform.toScreenSize(10);
                        int boxX = x + w - boxSize - 8;
                        int boxY = py - boxSize / 2;

                        // 外框
                        gui.fill(boxX, boxY, boxX + boxSize, boxY + boxSize, 0xFF353538);
                        gui.fill(boxX + 1, boxY + 1, boxX + boxSize - 1, boxY + boxSize - 1, 0xFF18181A);

                        boolean bVal = port.getRawValue() instanceof Boolean b && b;
                        if (bVal) {
                            gui.fill(boxX + 3, boxY + 3, boxX + boxSize - 3, boxY + boxSize - 3, VALUE_COLOR);
                        }
                    } else if (port.type().isNumeric()) {
                        String valStr = formatValue(port);
                        int valX = x + w - font.width(valStr) - 4;
                        gui.drawString(font, valStr, valX, py - 4, VALUE_COLOR);
                    }
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
