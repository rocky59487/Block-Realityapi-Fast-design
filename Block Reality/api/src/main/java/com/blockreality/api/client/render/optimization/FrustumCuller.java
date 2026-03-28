package com.blockreality.api.client.render.optimization;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;

/**
 * 視錐剔除器 — Sodium 風格 Frustum Culling。
 *
 * 技術：
 *   - 使用 JOML FrustumIntersection（提取 6 平面 + 快速 AABB 測試）
 *   - 每幀更新一次 frustum matrix（投影 × 視角）
 *   - 額外膨脹邊距（BRRenderConfig.FRUSTUM_PADDING）防止邊界 pop-in
 *
 * Sodium 優化啟發：
 *   - 先粗粒度（16³ section）剔除，再細粒度（island AABB）剔除
 *   - 結構外圍方塊邊界盒預計算，避免每幀遍歷
 */
@OnlyIn(Dist.CLIENT)
public final class FrustumCuller {

    private final FrustumIntersection frustum = new FrustumIntersection();
    private final float padding;

    private int visibleCount;
    private int culledCount;

    public FrustumCuller(float padding) {
        this.padding = padding;
    }

    /**
     * 每幀更新 — 從投影矩陣 × 視角矩陣提取 6 剔除平面。
     */
    public void update(Matrix4f projMatrix, Matrix4f viewMatrix) {
        Matrix4f pvMatrix = new Matrix4f(projMatrix).mul(viewMatrix);
        frustum.set(pvMatrix);
        visibleCount = 0;
        culledCount = 0;
    }

    /**
     * 測試 AABB 是否在視錐內（含膨脹邊距）。
     *
     * @param minX AABB 最小 X
     * @param minY AABB 最小 Y
     * @param minZ AABB 最小 Z
     * @param maxX AABB 最大 X
     * @param maxY AABB 最大 Y
     * @param maxZ AABB 最大 Z
     * @return true 表示可見（不應剔除）
     */
    public boolean testAABB(float minX, float minY, float minZ,
                             float maxX, float maxY, float maxZ) {
        boolean visible = frustum.testAab(
            minX - padding, minY - padding, minZ - padding,
            maxX + padding, maxY + padding, maxZ + padding
        );
        if (visible) visibleCount++;
        else culledCount++;
        return visible;
    }

    /**
     * 測試球體是否在視錐內。
     */
    public boolean testSphere(float x, float y, float z, float radius) {
        boolean visible = frustum.testSphere(x, y, z, radius + padding);
        if (visible) visibleCount++;
        else culledCount++;
        return visible;
    }

    /**
     * 測試點是否在視錐內（用於粒子等零體積物件）。
     */
    public boolean testPoint(float x, float y, float z) {
        return frustum.testPoint(x, y, z);
    }

    public int getVisibleCount() { return visibleCount; }
    public int getCulledCount() { return culledCount; }

    /** 重置統計計數器 */
    public void resetStats() {
        visibleCount = 0;
        culledCount = 0;
    }
}
