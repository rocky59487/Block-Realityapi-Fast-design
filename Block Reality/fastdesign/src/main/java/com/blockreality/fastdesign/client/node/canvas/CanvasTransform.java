package com.blockreality.fastdesign.client.node.canvas;

/**
 * 畫布平移/縮放狀態 — 設計報告 §10.3
 *
 * 管理畫布座標到螢幕座標的轉換：
 *   screenX = (canvasX - panX) * zoom
 *   screenY = (canvasY - panY) * zoom
 *
 * 支援：
 *   - 無限平移（中鍵拖曳）
 *   - 縮放 0.1x ~ 10x（滾輪）
 *   - 螢幕座標 ↔ 畫布座標互轉
 */
public class CanvasTransform {

    private static final float MIN_ZOOM = 0.1f;
    private static final float MAX_ZOOM = 10.0f;
    private static final float ZOOM_STEP = 0.1f;

    private float panX = 0;
    private float panY = 0;
    private float zoom = 1.0f;

    // ─── 座標轉換 ───

    /** 畫布座標 → 螢幕座標 X */
    public float toScreenX(float canvasX) {
        return (canvasX - panX) * zoom;
    }

    /** 畫布座標 → 螢幕座標 Y */
    public float toScreenY(float canvasY) {
        return (canvasY - panY) * zoom;
    }

    /** 螢幕座標 → 畫布座標 X */
    public float toCanvasX(float screenX) {
        return screenX / zoom + panX;
    }

    /** 螢幕座標 → 畫布座標 Y */
    public float toCanvasY(float screenY) {
        return screenY / zoom + panY;
    }

    /** 畫布尺寸 → 螢幕尺寸 */
    public float toScreenSize(float canvasSize) {
        return canvasSize * zoom;
    }

    /** 螢幕尺寸 → 畫布尺寸 */
    public float toCanvasSize(float screenSize) {
        return screenSize / zoom;
    }

    // ─── 平移 ───

    /** 以螢幕像素為單位平移 */
    public void panByScreen(float dx, float dy) {
        panX -= dx / zoom;
        panY -= dy / zoom;
    }

    /** 直接設定畫布原點 */
    public void setPan(float x, float y) {
        this.panX = x;
        this.panY = y;
    }

    // ─── 縮放 ───

    /**
     * 以指定螢幕座標為中心縮放。
     * @param screenX 滾輪位置 X
     * @param screenY 滾輪位置 Y
     * @param delta   正=放大，負=縮小
     */
    public void zoomAt(float screenX, float screenY, float delta) {
        float canvasX = toCanvasX(screenX);
        float canvasY = toCanvasY(screenY);

        float oldZoom = zoom;
        zoom = clamp(zoom + delta * ZOOM_STEP * zoom, MIN_ZOOM, MAX_ZOOM);

        // 保持滑鼠下方的畫布點不動
        if (zoom != oldZoom) {
            panX = canvasX - screenX / zoom;
            panY = canvasY - screenY / zoom;
        }
    }

    /** 直接設定縮放 */
    public void setZoom(float z) {
        this.zoom = clamp(z, MIN_ZOOM, MAX_ZOOM);
    }

    // ─── 適配 ───

    /**
     * 縮放並平移以使指定畫布範圍適配螢幕（按 F 鍵）。
     */
    public void fitRect(float canvasMinX, float canvasMinY,
                        float canvasW, float canvasH,
                        float screenW, float screenH) {
        if (canvasW <= 0 || canvasH <= 0) return;

        float padding = 40.0f; // 螢幕邊距
        float availW = screenW - padding * 2;
        float availH = screenH - padding * 2;

        zoom = clamp(Math.min(availW / canvasW, availH / canvasH), MIN_ZOOM, MAX_ZOOM);
        panX = canvasMinX - padding / zoom;
        panY = canvasMinY - padding / zoom;
    }

    // ─── 存取 ───

    public float panX() { return panX; }
    public float panY() { return panY; }
    public float zoom() { return zoom; }

    /** 重置為預設視角 */
    public void reset() {
        panX = 0;
        panY = 0;
        zoom = 1.0f;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
