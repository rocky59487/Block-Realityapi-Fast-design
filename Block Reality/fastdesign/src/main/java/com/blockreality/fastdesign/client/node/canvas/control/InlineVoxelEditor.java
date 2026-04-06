package com.blockreality.fastdesign.client.node.canvas.control;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;

/**
 * 內嵌體素編輯器 — 設計報告 B3-2 CustomShape
 *
 * 用於在節點內部編輯 10×10×10 的體素網格。
 * 顯示等角投影的 3D 切片視圖。
 */
@OnlyIn(Dist.CLIENT)
public class InlineVoxelEditor {

    private static final int EDITOR_SIZE = 80;
    private static final int GRID_SIZE = 10;
    private static final int CELL_SIZE = EDITOR_SIZE / GRID_SIZE;
    private static final int BG_COLOR = 0xFF0A0A14;
    private static final int FILLED_COLOR = 0xFF44AA44;
    private static final int EMPTY_COLOR = 0xFF1A1A2A;
    private static final int GRID_COLOR = 0xFF2A2A3A;

    private int currentLayer = 0; // Y 切片（0~9）

    /**
     * 渲染一個 Y 切片的 2D 網格。
     */
    public int render(GuiGraphics gui, boolean[] voxels, int x, int y) {
        // 背景
        gui.fill(x, y, x + EDITOR_SIZE, y + EDITOR_SIZE, BG_COLOR);

        // 格子
        for (int gz = 0; gz < GRID_SIZE; gz++) {
            for (int gx = 0; gx < GRID_SIZE; gx++) {
                int cx = x + gx * CELL_SIZE;
                int cy = y + gz * CELL_SIZE;
                int idx = gx + GRID_SIZE * (currentLayer + GRID_SIZE * gz);

                boolean filled = voxels != null && idx < voxels.length && voxels[idx];
                gui.fill(cx, cy, cx + CELL_SIZE - 1, cy + CELL_SIZE - 1,
                        filled ? FILLED_COLOR : EMPTY_COLOR);
            }
        }

        // 網格線
        for (int i = 0; i <= GRID_SIZE; i++) {
            gui.fill(x + i * CELL_SIZE, y, x + i * CELL_SIZE + 1, y + EDITOR_SIZE, GRID_COLOR);
            gui.fill(x, y + i * CELL_SIZE, x + EDITOR_SIZE, y + i * CELL_SIZE + 1, GRID_COLOR);
        }

        // 層指示
        String layerStr = "Y=" + currentLayer;
        gui.drawString(net.minecraft.client.Minecraft.getInstance().font,
                layerStr, x + 2, y + EDITOR_SIZE + 2, 0xFFAAAAAA);

        return EDITOR_SIZE + 14;
    }

    /**
     * 點擊切換體素。
     */
    public boolean mouseClicked(boolean[] voxels, int x, int y,
                                 double mouseX, double mouseY) {
        if (voxels == null) return false;
        if (mouseX < x || mouseX >= x + EDITOR_SIZE || mouseY < y || mouseY >= y + EDITOR_SIZE) {
            return false;
        }

        int gx = (int) ((mouseX - x) / CELL_SIZE);
        int gz = (int) ((mouseY - y) / CELL_SIZE);
        if (gx >= 0 && gx < GRID_SIZE && gz >= 0 && gz < GRID_SIZE) {
            int idx = gx + GRID_SIZE * (currentLayer + GRID_SIZE * gz);
            if (idx >= 0 && idx < voxels.length) {
                voxels[idx] = !voxels[idx];
                return true;
            }
        }
        return false;
    }

    public void setLayer(int layer) {
        this.currentLayer = Math.max(0, Math.min(GRID_SIZE - 1, layer));
    }

    public void nextLayer() { setLayer(currentLayer + 1); }
    public void prevLayer() { setLayer(currentLayer - 1); }
    public int currentLayer() { return currentLayer; }
}
