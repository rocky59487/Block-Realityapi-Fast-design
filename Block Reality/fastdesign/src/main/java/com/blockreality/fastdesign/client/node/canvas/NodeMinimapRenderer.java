package com.blockreality.fastdesign.client.node.canvas;

import com.blockreality.fastdesign.client.node.BRNode;
import com.blockreality.fastdesign.client.node.NodeGraph;
import net.minecraft.client.gui.GuiGraphics;

public class NodeMinimapRenderer {

    private static final int WIDTH = 180;
    private static final int HEIGHT = 120;

    private boolean isDraggingViewport = false;

    public void render(GuiGraphics gui, NodeGraph graph, CanvasTransform transform, int screenW, int screenH, int mouseX, int mouseY) {
        int sx = screenW - WIDTH - 8;
        int sy = screenH - HEIGHT - 8;

        // Background
        gui.fill(sx, sy, sx + WIDTH, sy + HEIGHT, 0xCC1A1A1A);
        gui.fill(sx, sy, sx + WIDTH, sy + 1, 0xFF353538);
        gui.fill(sx, sy + HEIGHT - 1, sx + WIDTH, sy + HEIGHT, 0xFF353538);
        gui.fill(sx, sy, sx + 1, sy + HEIGHT, 0xFF353538);
        gui.fill(sx + WIDTH - 1, sy, sx + WIDTH, sy + HEIGHT, 0xFF353538);

        if (graph.nodeCount() == 0) return;

        // Calculate graph bounds
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (BRNode node : graph.allNodes()) {
            minX = Math.min(minX, node.posX());
            minY = Math.min(minY, node.posY());
            maxX = Math.max(maxX, node.posX() + node.width());
            maxY = Math.max(maxY, node.posY() + node.height());
        }

        // Add padding
        float pad = 200;
        minX -= pad; minY -= pad;
        maxX += pad; maxY += pad;
        float gw = maxX - minX;
        float gh = maxY - minY;

        float scaleX = WIDTH / gw;
        float scaleY = HEIGHT / gh;
        float scale = Math.min(scaleX, scaleY);
        if (scale > 1.0f) scale = 1.0f; // Don't overzoom small graphs

        float offsetX = sx + (WIDTH - gw * scale) / 2.0f;
        float offsetY = sy + (HEIGHT - gh * scale) / 2.0f;

        // Render nodes as dots
        for (BRNode node : graph.allNodes()) {
            int nx = (int) (offsetX + (node.posX() - minX) * scale);
            int ny = (int) (offsetY + (node.posY() - minY) * scale);
            int nw = Math.max(2, (int) (node.width() * scale));
            int nh = Math.max(2, (int) (node.height() * scale));

            gui.fill(nx, ny, nx + nw, ny + nh, node.color().argb());
        }

        // Render Viewport
        float vx1 = transform.toCanvasX(0);
        float vy1 = transform.toCanvasY(0);
        float vx2 = transform.toCanvasX(screenW);
        float vy2 = transform.toCanvasY(screenH);

        int vsx = (int) (offsetX + (vx1 - minX) * scale);
        int vsy = (int) (offsetY + (vy1 - minY) * scale);
        int vw = (int) ((vx2 - vx1) * scale);
        int vh = (int) ((vy2 - vy1) * scale);

        // Clamp viewport rendering to minimap bounds
        vsx = Math.max(sx, Math.min(sx + WIDTH, vsx));
        vsy = Math.max(sy, Math.min(sy + HEIGHT, vsy));
        vw = Math.min(sx + WIDTH - vsx, vw);
        vh = Math.min(sy + HEIGHT - vsy, vh);

        if (vw > 0 && vh > 0) {
            gui.fill(vsx, vsy, vsx + vw, vsy + 1, 0xFFFFFFFF);
            gui.fill(vsx, vsy + vh - 1, vsx + vw, vsy + vh, 0xFFFFFFFF);
            gui.fill(vsx, vsy, vsx + 1, vsy + vh, 0xFFFFFFFF);
            gui.fill(vsx + vw - 1, vsy, vsx + vw, vsy + vh, 0xFFFFFFFF);
            gui.fill(vsx, vsy, vsx + vw, vsy + vh, 0x33FFFFFF);
        }

        // Viewport Dragging Logic (Needs external call from mouseDragged, but we can do a basic bounds check here)
        if (isDraggingViewport) {
            float targetCanvasX = (mouseX - offsetX) / scale + minX;
            float targetCanvasY = (mouseY - offsetY) / scale + minY;

            float screenCanvasW = transform.toCanvasX(screenW) - transform.toCanvasX(0);
            float screenCanvasH = transform.toCanvasY(screenH) - transform.toCanvasY(0);

            // Pan so center of viewport is at mouse
            transform.panTo(targetCanvasX - screenCanvasW / 2.0f, targetCanvasY - screenCanvasH / 2.0f);
        }
    }

    public boolean mouseClicked(double mouseX, double mouseY, int screenW, int screenH) {
        int sx = screenW - WIDTH - 8;
        int sy = screenH - HEIGHT - 8;
        if (mouseX >= sx && mouseX <= sx + WIDTH && mouseY >= sy && mouseY <= sy + HEIGHT) {
            isDraggingViewport = true;
            return true;
        }
        return false;
    }

    public boolean mouseReleased(double mouseX, double mouseY) {
        if (isDraggingViewport) {
            isDraggingViewport = false;
            return true;
        }
        return false;
    }
}
