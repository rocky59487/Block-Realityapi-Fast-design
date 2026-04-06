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

    // 目標值 (用於平滑過渡)
    private float targetPanX = 0;
    private float targetPanY = 0;
    private float targetZoom = 1.0f;

    // 是否正在進行平滑過渡
    private boolean isLerping = false;

    // ─── 平滑更新 ───
    public void tickLerp(float partialTicks) {
        if (!isLerping) return;

        float smoothing = 0.3f; // 插值速度

        panX += (targetPanX - panX) * smoothing;
        panY += (targetPanY - panY) * smoothing;
        zoom += (targetZoom - zoom) * smoothing;

        // 停止條件
        if (Math.abs(targetPanX - panX) < 0.1f && Math.abs(targetPanY - panY) < 0.1f && Math.abs(targetZoom - zoom) < 0.001f) {
            panX = targetPanX;
            panY = targetPanY;
            zoom = targetZoom;
            isLerping = false;
        }
    }

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
        // 直接更新實際值與目標值，拖曳時不延遲
        panX -= dx / zoom;
        panY -= dy / zoom;
        targetPanX = panX;
        targetPanY = panY;
    }

    /** 直接設定畫布原點 */
    public void panTo(float x, float y) {
        this.targetPanX = x;
        this.targetPanY = y;
        this.isLerping = true;
    }

    /** 直接設定畫布原點 */
    public void setPan(float x, float y) {
        this.panX = x;
        this.panY = y;
        this.targetPanX = x;
        this.targetPanY = y;
    }

    // ─── 縮放 ───

    /**
     * 以指定螢幕座標為中心縮放。
     * @param screenX 滾輪位置 X
     * @param screenY 滾輪位置 Y
     * @param delta   正=放大，負=縮小
     */
    public void zoomAt(float screenX, float screenY, float delta) {
        // 使用目標轉換反推滑鼠下方的虛擬座標 (防止連續捲動時基準點漂移)
        float targetCanvasX = screenX / targetZoom + targetPanX;
        float targetCanvasY = screenY / targetZoom + targetPanY;

        // 更新目標 zoom
        targetZoom = clamp(targetZoom + delta * ZOOM_STEP * targetZoom, MIN_ZOOM, MAX_ZOOM);

        // 計算對應的目標 pan，使得滑鼠位置在縮放後不變
        targetPanX = targetCanvasX - screenX / targetZoom;
        targetPanY = targetCanvasY - screenY / targetZoom;

        isLerping = true;
    }

    /** 直接設定縮放 */
    public void setZoom(float z) {
        this.zoom = clamp(z, MIN_ZOOM, MAX_ZOOM);
        this.targetZoom = this.zoom;
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

        targetZoom = clamp(Math.min(availW / canvasW, availH / canvasH), MIN_ZOOM, MAX_ZOOM);
        targetPanX = canvasMinX - padding / targetZoom;
        targetPanY = canvasMinY - padding / targetZoom;
        isLerping = true;
    }

    // ─── 存取 ───

    public float panX() { return panX; }
    public float panY() { return panY; }
    public float zoom() { return zoom; }

    /** 重置為預設視角 */
    public void reset() {
        targetPanX = 0;
        targetPanY = 0;
        targetZoom = 1.0f;
        isLerping = true;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
