package com.blockreality.fastdesign.client.node.canvas;

import com.blockreality.fastdesign.client.node.BRNode;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import com.blockreality.fastdesign.client.node.InputPort;
import com.blockreality.fastdesign.client.node.OutputPort;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * 節點 Tooltip + 錯誤標記渲染 — 設計報告 §12.1 N2-9
 *
 * 懸停時顯示：
 *   - 節點描述
 *   - 當前端口值
 *   - 上次評估時間
 *   - 錯誤/警告訊息
 */
@OnlyIn(Dist.CLIENT)
public class NodeTooltipRenderer {

    private static final int TOOLTIP_BG = 0xEE1A1A2E;
    private static final int TOOLTIP_BORDER = 0xFF4A4A6A;
    private static final int TEXT_COLOR = 0xFFDDDDDD;
    private static final int ERROR_COLOR = 0xFFFF4444;
    private static final int WARN_COLOR = 0xFFFFAA00;
    private static final int INFO_COLOR = 0xFF88BBFF;
    private static final int PADDING = 4;
    private static final int LINE_HEIGHT = 11;

    /**
     * 渲染節點的懸停 tooltip。
     */
    public void renderTooltip(GuiGraphics gui, BRNode node, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        List<TooltipLine> lines = buildTooltipLines(node);

        if (lines.isEmpty()) return;

        // 計算 tooltip 尺寸
        int maxWidth = 0;
        for (TooltipLine line : lines) {
            maxWidth = Math.max(maxWidth, font.width(line.text));
        }
        int w = maxWidth + PADDING * 2;
        int h = lines.size() * LINE_HEIGHT + PADDING * 2;

        // 定位（避免超出螢幕）
        int screenW = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int screenH = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        int x = mouseX + 12;
        int y = mouseY - 4;
        if (x + w > screenW) x = mouseX - w - 4;
        if (y + h > screenH) y = screenH - h;

        // 背景 + 邊框
        gui.fill(x - 1, y - 1, x + w + 1, y + h + 1, TOOLTIP_BORDER);
        gui.fill(x, y, x + w, y + h, TOOLTIP_BG);

        // 文字
        int textY = y + PADDING;
        for (TooltipLine line : lines) {
            gui.drawString(font, line.text, x + PADDING, textY, line.color);
            textY += LINE_HEIGHT;
        }
    }

    /**
     * 渲染錯誤節點的脈動紅色邊框。
     */
    public void renderErrorBorder(GuiGraphics gui, BRNode node, CanvasTransform transform) {
        float sx = transform.toScreenX(node.posX());
        float sy = transform.toScreenY(node.posY());
        float sw = transform.toScreenSize(node.width());
        float sh = transform.toScreenSize(node.height());

        // 脈動效果
        long time = System.currentTimeMillis();
        float pulse = (float) (Math.sin(time * 0.005) * 0.5 + 0.5);
        int alpha = (int) (0x44 + pulse * 0xBB);
        int color = (alpha << 24) | 0xFF4444;

        int x = (int) sx, y = (int) sy, w = (int) sw, h = (int) sh;
        int thickness = 2;

        gui.fill(x - thickness, y - thickness, x + w + thickness, y, color);
        gui.fill(x - thickness, y + h, x + w + thickness, y + h + thickness, color);
        gui.fill(x - thickness, y, x, y + h, color);
        gui.fill(x + w, y, x + w + thickness, y + h, color);
    }

    // ─── 內部 ───

    private List<TooltipLine> buildTooltipLines(BRNode node) {
        List<TooltipLine> lines = new ArrayList<>();

        // 標題
        lines.add(new TooltipLine(node.displayName(), TEXT_COLOR));
        if (node.displayNameCN() != null && !node.displayNameCN().isEmpty()) {
            lines.add(new TooltipLine(node.displayNameCN(), INFO_COLOR));
        }

        // 類別
        lines.add(new TooltipLine("Type: " + node.typeId(), INFO_COLOR));

        // Tooltip 文字
        String tooltip = node.getTooltip();
        if (tooltip != null && !tooltip.isEmpty()) {
            lines.add(new TooltipLine(tooltip, TEXT_COLOR));
        }

        // 狀態
        if (!node.isEnabled()) {
            lines.add(new TooltipLine("[Disabled]", WARN_COLOR));
        }

        // 評估時間
        if (node.lastEvalTimeNs() > 0) {
            String timeStr = String.format("Eval: %.2fms", node.lastEvalTimeNs() / 1_000_000.0);
            int timeColor = node.lastEvalTimeNs() > 1_000_000 ? WARN_COLOR : INFO_COLOR;
            lines.add(new TooltipLine(timeStr, timeColor));
        }

        // 端口數
        lines.add(new TooltipLine(
                String.format("Ports: %d in / %d out", node.inputs().size(), node.outputs().size()),
                INFO_COLOR));

        return lines;
    }

    private record TooltipLine(String text, int color) {}
}
