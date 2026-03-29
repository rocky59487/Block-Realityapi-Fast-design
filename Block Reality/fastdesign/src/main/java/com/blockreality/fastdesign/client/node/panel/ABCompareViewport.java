package com.blockreality.fastdesign.client.node.panel;

import com.blockreality.fastdesign.client.node.binding.MutableRenderConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * A/B 分割預覽 — 設計報告 §12.1 N5-3
 *
 * 分割螢幕，左半使用 configA、右半使用 configB 渲染，
 * 用戶可拖曳分割線即時比較兩組品質設定。
 */
@OnlyIn(Dist.CLIENT)
public class ABCompareViewport {

    private static final int DIVIDER_COLOR = 0xFFFFFFFF;
    private static final int DIVIDER_WIDTH = 2;
    private static final int HANDLE_SIZE = 16;

    private boolean active = false;
    private float splitPosition = 0.5f; // 0.0 ~ 1.0
    private boolean dragging = false;

    private MutableRenderConfig configA;
    private MutableRenderConfig configB;

    /**
     * 啟用 A/B 比較模式。
     */
    public void activate(MutableRenderConfig a, MutableRenderConfig b) {
        this.configA = a;
        this.configB = b;
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
    }

    public boolean isActive() { return active; }

    /**
     * 渲染分割線和標籤。在主渲染循環的最後呼叫。
     */
    public void renderOverlay(GuiGraphics gui, int screenWidth, int screenHeight) {
        if (!active) return;

        int divX = (int) (screenWidth * splitPosition);

        // 分割線
        gui.fill(divX - DIVIDER_WIDTH / 2, 0, divX + DIVIDER_WIDTH / 2, screenHeight, DIVIDER_COLOR);

        // 手柄
        int handleY = screenHeight / 2 - HANDLE_SIZE / 2;
        gui.fill(divX - HANDLE_SIZE / 2, handleY, divX + HANDLE_SIZE / 2,
                handleY + HANDLE_SIZE, 0xDDFFFFFF);
        gui.fill(divX - 1, handleY + 4, divX + 2, handleY + HANDLE_SIZE - 4, 0xFF333333);

        // 標籤
        gui.drawString(net.minecraft.client.Minecraft.getInstance().font,
                "A", 8, 8, 0xFFFFFFFF);
        gui.drawString(net.minecraft.client.Minecraft.getInstance().font,
                "B", screenWidth - 16, 8, 0xFFFFFFFF);
    }

    /**
     * 取得指定螢幕 X 座標應使用哪組配置。
     */
    public MutableRenderConfig getConfigForScreenX(float screenX, int screenWidth) {
        if (!active || configA == null || configB == null) return MutableRenderConfig.getInstance();
        return (screenX / screenWidth) < splitPosition ? configA : configB;
    }

    // ─── 互動 ───

    public boolean mouseClicked(double mouseX, double mouseY, int screenWidth, int screenHeight) {
        if (!active) return false;
        int divX = (int) (screenWidth * splitPosition);
        if (Math.abs(mouseX - divX) < HANDLE_SIZE) {
            dragging = true;
            return true;
        }
        return false;
    }

    public boolean mouseDragged(double mouseX, int screenWidth) {
        if (!dragging) return false;
        splitPosition = (float) Math.max(0.1, Math.min(0.9, mouseX / screenWidth));
        return true;
    }

    public void mouseReleased() {
        dragging = false;
    }

    public float splitPosition() { return splitPosition; }
    public void setSplitPosition(float pos) { this.splitPosition = Math.max(0.1f, Math.min(0.9f, pos)); }
}
