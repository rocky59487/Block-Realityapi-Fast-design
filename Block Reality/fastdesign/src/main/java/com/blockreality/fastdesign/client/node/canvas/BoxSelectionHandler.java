package com.blockreality.fastdesign.client.node.canvas;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;

/**
 * 框選處理器 — 設計報告 §10.3, §12.1 N2-6
 *
 * 左鍵拖曳空白區域繪製選取矩形。
 */
@OnlyIn(Dist.CLIENT)
public class BoxSelectionHandler {

    private static final int SELECT_RECT_FILL = 0x2200CCCC;
    private static final int SELECT_RECT_BORDER = 0xFF00CCCC;

    private boolean selecting;
    private float startX, startY;
    private float endX, endY;

    public boolean isSelecting() { return selecting; }

    public void startSelection(float screenX, float screenY) {
        selecting = true;
        startX = screenX;
        startY = screenY;
        endX = screenX;
        endY = screenY;
    }

    public void updateSelection(float screenX, float screenY) {
        endX = screenX;
        endY = screenY;
    }

    public void finishSelection() {
        selecting = false;
    }

    /**
     * 取得畫布座標的選取矩形 [x, y, w, h]。
     */
    public float[] getCanvasRect(CanvasTransform transform) {
        float cx1 = transform.toCanvasX(Math.min(startX, endX));
        float cy1 = transform.toCanvasY(Math.min(startY, endY));
        float cx2 = transform.toCanvasX(Math.max(startX, endX));
        float cy2 = transform.toCanvasY(Math.max(startY, endY));
        return new float[]{cx1, cy1, cx2 - cx1, cy2 - cy1};
    }

    /**
     * 渲染選取矩形。
     */
    public void renderSelectionRect(GuiGraphics gui) {
        if (!selecting) return;

        int x1 = (int) Math.min(startX, endX);
        int y1 = (int) Math.min(startY, endY);
        int x2 = (int) Math.max(startX, endX);
        int y2 = (int) Math.max(startY, endY);

        // 半透明填充
        gui.fill(x1, y1, x2, y2, SELECT_RECT_FILL);

        // 邊框
        gui.fill(x1, y1, x2, y1 + 1, SELECT_RECT_BORDER);
        gui.fill(x1, y2 - 1, x2, y2, SELECT_RECT_BORDER);
        gui.fill(x1, y1, x1 + 1, y2, SELECT_RECT_BORDER);
        gui.fill(x2 - 1, y1, x2, y2, SELECT_RECT_BORDER);
    }
}
