package com.blockreality.fastdesign.client.node.canvas;

import com.blockreality.fastdesign.client.node.BRNode;
import com.blockreality.fastdesign.client.node.PortType;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 節點 Inspector 面板 — 顯示選中節點的可調屬性
 *
 * <p>設計為 {@link NodeCanvasScreen} 右側 200px 懸浮面板，
 * 每個 {@link BRNode.NodeProperty} 渲染為對應控件：
 * <ul>
 *   <li>FLOAT / INT — 橫條滑桿（附數值文字）</li>
 *   <li>BOOL       — 勾選框</li>
 *   <li>其他       — 唯讀文字顯示</li>
 * </ul>
 *
 * <p>已連線的屬性（wire 驅動）顯示為灰色唯讀。
 */
@OnlyIn(Dist.CLIENT)
public class NodeInspectorPanel {

    // ─── 版面常數 ───
    public static final int PANEL_WIDTH   = 210;
    private static final int HEADER_H     = 28;
    private static final int ROW_H        = 22;
    private static final int PADDING      = 6;
    private static final int CTRL_H       = 10;
    private static final int SCROLL_SPEED = 12;

    // ─── 顏色 ───
    private static final int BG_COLOR        = 0xE8181818;
    private static final int HEADER_BG       = 0xFF242424;
    private static final int BORDER_COLOR    = 0xFF444444;
    private static final int LABEL_COLOR     = 0xFFCCCCCC;
    private static final int VALUE_COLOR     = 0xFFFFFFFF;
    private static final int WIRED_COLOR     = 0xFF888888;
    private static final int SLIDER_BG      = 0xFF333333;
    private static final int SLIDER_FG      = 0xFF4D9AFF;
    private static final int CHECKBOX_BG    = 0xFF333333;
    private static final int CHECKBOX_TRUE  = 0xFF4ABA5E;
    private static final int TOOLTIP_BG     = 0xE0111111;
    private static final int TOOLTIP_COLOR  = 0xFFAAAAAA;

    // ─── 狀態 ───
    @Nullable private BRNode node;
    private int scrollOffset = 0;       // 滾動偏移（px）
    private int maxScroll    = 0;
    private int panelX, panelY, panelH; // 最後一次 render 時的座標

    /** 追蹤滑桿拖曳 */
    @Nullable private BRNode.NodeProperty<?> draggingProp;
    private int draggingSliderX, draggingSliderW;

    // ─── 懸停 tooltip ───
    @Nullable private String hoverTooltip;
    private int hoverTooltipX, hoverTooltipY;

    // ─── API ───

    /** 設定要顯示的節點。傳 null 隱藏面板。 */
    public void setNode(@Nullable BRNode node) {
        if (this.node != node) {
            this.node = node;
            scrollOffset = 0;
        }
    }

    @Nullable
    public BRNode getNode() { return node; }

    public boolean isVisible() { return node != null && !node.getProperties().isEmpty(); }

    // ─── 渲染 ───

    /**
     * 渲染 Inspector 面板。
     *
     * @param gui        GuiGraphics
     * @param font       Minecraft 字型
     * @param screenW    畫面總寬度
     * @param screenH    畫面總高度
     * @param mouseX     滑鼠 X
     * @param mouseY     滑鼠 Y
     */
    public void render(GuiGraphics gui, Font font,
                       int screenW, int screenH,
                       int mouseX, int mouseY) {
        if (!isVisible()) return;

        List<BRNode.NodeProperty<?>> props = node.getProperties();

        panelX = screenW - PANEL_WIDTH;
        panelY = 0;
        panelH = screenH;

        int contentH = HEADER_H + props.size() * ROW_H + PADDING * 2;
        maxScroll = Math.max(0, contentH - panelH);

        // ─── 背景 ───
        gui.fill(panelX, panelY, screenW, panelY + panelH, BG_COLOR);
        // 左邊框
        gui.fill(panelX, panelY, panelX + 1, panelY + panelH, BORDER_COLOR);

        // ─── Header ───
        gui.fill(panelX, panelY, screenW, panelY + HEADER_H, HEADER_BG);
        int headerColor = node.color().argb();
        gui.fill(panelX, panelY, panelX + 3, panelY + HEADER_H, headerColor);

        String title = node.displayName();
        gui.drawString(font, title, panelX + 8, panelY + 5, 0xFFFFFFFF, false);
        gui.drawString(font, "§8" + node.typeId(), panelX + 8, panelY + 15, WIRED_COLOR, false);

        // ─── 屬性列表（帶 scissor 裁剪）───
        int listY = panelY + HEADER_H;
        int listH = panelH - HEADER_H;

        gui.enableScissor(panelX, listY, screenW, listY + listH);

        hoverTooltip = null;
        int y = listY + PADDING - scrollOffset;

        for (BRNode.NodeProperty<?> prop : props) {
            renderPropertyRow(gui, font, prop, panelX + PADDING, y,
                    PANEL_WIDTH - PADDING * 2, mouseX, mouseY);
            y += ROW_H;
        }

        gui.disableScissor();

        // ─── Tooltip ───
        if (hoverTooltip != null) {
            renderTooltip(gui, font, hoverTooltip, hoverTooltipX, hoverTooltipY, screenW, screenH);
        }
    }

    // ─── 單行屬性渲染 ───

    private void renderPropertyRow(GuiGraphics gui, Font font,
                                   BRNode.NodeProperty<?> prop,
                                   int x, int y, int w,
                                   int mouseX, int mouseY) {
        boolean connected = prop.isConnected();
        int labelColor = connected ? WIRED_COLOR : LABEL_COLOR;

        // 標籤
        String label = prop.label();
        gui.drawString(font, label, x, y + 2, labelColor, false);

        int ctrlX = x + w - 80;
        int ctrlY = y + (ROW_H - CTRL_H) / 2;
        int ctrlW = 78;

        if (!connected) {
            switch (prop.type()) {
                case FLOAT, INT -> renderSlider(gui, font, prop, ctrlX, ctrlY, ctrlW, CTRL_H);
                case BOOL       -> renderCheckbox(gui, font, prop, ctrlX, ctrlY);
                default         -> renderReadonly(gui, font, prop, ctrlX, ctrlY);
            }
        } else {
            // 已連線 → 顯示 "~wire~"
            gui.drawString(font, "§8~wire~", ctrlX, ctrlY, WIRED_COLOR, false);
        }

        // Hover → 顯示 tooltip
        if (prop.tooltip() != null
                && mouseX >= x && mouseX <= x + w
                && mouseY >= y && mouseY <= y + ROW_H) {
            hoverTooltip   = prop.tooltip();
            hoverTooltipX  = mouseX;
            hoverTooltipY  = mouseY;
        }
    }

    private void renderSlider(GuiGraphics gui, Font font,
                              BRNode.NodeProperty<?> prop,
                              int x, int y, int w, int h) {
        float min = prop.min() == Float.NEGATIVE_INFINITY ?   0 : prop.min();
        float max = prop.max() == Float.POSITIVE_INFINITY ? 100 : prop.max();
        if (max <= min) max = min + 1;

        float raw = 0;
        Object val = prop.get();
        if (val instanceof Number n) raw = n.floatValue();
        float pct = Math.max(0, Math.min(1, (raw - min) / (max - min)));

        // 槽背景
        gui.fill(x, y, x + w, y + h, SLIDER_BG);
        // 填充
        gui.fill(x, y, x + (int) (w * pct), y + h, SLIDER_FG);
        // 數值
        String text = prop.type() == PortType.INT
                ? String.valueOf(Math.round(raw))
                : String.format("%.3f", raw);
        int tw = font.width(text);
        gui.drawString(font, "§f" + text, x + (w - tw) / 2, y + 1, VALUE_COLOR, false);
    }

    private void renderCheckbox(GuiGraphics gui, Font font,
                                BRNode.NodeProperty<?> prop,
                                int x, int y) {
        boolean checked = Boolean.TRUE.equals(prop.get());
        int bg = checked ? CHECKBOX_TRUE : CHECKBOX_BG;
        gui.fill(x, y, x + CTRL_H, y + CTRL_H, bg);
        if (checked) {
            gui.drawString(font, "§a✔", x + 1, y, 0xFFFFFFFF, false);
        }
        gui.drawString(font, checked ? "§aON" : "§cOFF", x + CTRL_H + 4, y + 1, VALUE_COLOR, false);
    }

    private void renderReadonly(GuiGraphics gui, Font font,
                                BRNode.NodeProperty<?> prop,
                                int x, int y) {
        Object val = prop.get();
        String text = val != null ? val.toString() : "—";
        gui.drawString(font, "§7" + text, x, y + 1, WIRED_COLOR, false);
    }

    // ─── Tooltip ───

    private void renderTooltip(GuiGraphics gui, Font font,
                               String text, int mx, int my,
                               int screenW, int screenH) {
        int tw = font.width(text) + 8;
        int th = 14;
        int tx = Math.max(0, Math.min(mx + 10, screenW - tw));
        int ty = Math.max(0, my - th - 4);
        gui.fill(tx, ty, tx + tw, ty + th, TOOLTIP_BG);
        gui.drawString(font, text, tx + 4, ty + 3, TOOLTIP_COLOR, false);
    }

    // ─── 滑鼠互動 ───

    /**
     * 處理點擊事件。
     *
     * @return true 若事件被本面板消耗
     */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!isVisible() || mouseX < panelX) return false;
        if (button != 0) return true; // 在面板範圍內，消耗右鍵（不透傳）

        List<BRNode.NodeProperty<?>> props = node.getProperties();
        int y = panelY + HEADER_H + PADDING - scrollOffset;

        for (BRNode.NodeProperty<?> prop : props) {
            if (!prop.isConnected()) {
                int ctrlX = (int) (panelX + PADDING + (PANEL_WIDTH - PADDING * 2) - 80);
                int ctrlY = y + (ROW_H - CTRL_H) / 2;
                int ctrlW = 78;

                if (prop.type() == PortType.FLOAT || prop.type() == PortType.INT) {
                    if (mouseY >= ctrlY && mouseY <= ctrlY + CTRL_H
                            && mouseX >= ctrlX && mouseX <= ctrlX + ctrlW) {
                        draggingProp    = prop;
                        draggingSliderX = ctrlX;
                        draggingSliderW = ctrlW;
                        applySliderClick(prop, (float) mouseX, ctrlX, ctrlW);
                        return true;
                    }
                } else if (prop.type() == PortType.BOOL) {
                    if (mouseY >= ctrlY && mouseY <= ctrlY + CTRL_H
                            && mouseX >= ctrlX && mouseX <= ctrlX + 30) {
                        boolean cur = Boolean.TRUE.equals(prop.get());
                        @SuppressWarnings("unchecked")
                        BRNode.NodeProperty<Boolean> bProp = (BRNode.NodeProperty<Boolean>) prop;
                        bProp.set(!cur);
                        node.markDirty();
                        return true;
                    }
                }
            }
            y += ROW_H;
        }
        return true; // 在面板內，吞掉事件
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button) {
        if (draggingProp != null && button == 0) {
            applySliderClick(draggingProp, (float) mouseX, draggingSliderX, draggingSliderW);
            return true;
        }
        return false;
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggingProp != null && button == 0) {
            draggingProp = null;
            return true;
        }
        return false;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!isVisible() || mouseX < panelX) return false;
        scrollOffset = Math.max(0, Math.min((int) (scrollOffset - delta * SCROLL_SPEED), maxScroll));
        return true;
    }

    // ─── 輔助 ───

    @SuppressWarnings("unchecked")
    private void applySliderClick(BRNode.NodeProperty<?> prop, float mx, int sliderX, int sliderW) {
        float min = prop.min() == Float.NEGATIVE_INFINITY ?   0 : prop.min();
        float max = prop.max() == Float.POSITIVE_INFINITY ? 100 : prop.max();
        if (max <= min) max = min + 1;
        float pct = Math.max(0, Math.min(1, (mx - sliderX) / (float) sliderW));
        float val = min + pct * (max - min);
        if (prop.type() == PortType.INT) {
            ((BRNode.NodeProperty<Integer>) prop).set(Math.round(val));
        } else {
            ((BRNode.NodeProperty<Float>) prop).set(val);
        }
        node.markDirty();
    }
}
