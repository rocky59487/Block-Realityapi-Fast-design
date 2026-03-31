package com.blockreality.api.client.rendering.lod;

import com.blockreality.api.client.render.optimization.FrustumCuller;
import com.blockreality.api.client.render.BRRenderConfig;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * LOD Render Dispatcher — 每幀調度 LOD 渲染，進行視錐剔除與距離排序。
 *
 * <p>職責：
 * <ul>
 *   <li>從 {@link LODChunkManager} 收集所有 section</li>
 *   <li>使用 {@link FrustumCuller} 剔除視錐外的 section</li>
 *   <li>依相機距離決定每個 section 的目標 LOD</li>
 *   <li>呼叫 OpenGL draw call 渲染可見 section</li>
 *   <li>限制每幀 dirty LOD 重建數量，避免卡頓</li>
 * </ul>
 *
 * @author Block Reality Team
 */
@OnlyIn(Dist.CLIENT)
public final class LODRenderDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger("BR-LODDispatch");

    /** 每幀最多觸發的 LOD 重建數（避免卡頓） */
    private static final int MAX_REBUILDS_PER_FRAME = 16;

    // ── 依賴 ──────────────────────────────────────────────────────────
    private final LODChunkManager chunkManager;
    private final FrustumCuller   frustumCuller;

    // ── 相機狀態 ──────────────────────────────────────────────────────
    private double camX, camY, camZ;

    // ── 統計 ─────────────────────────────────────────────────────────
    private int lastVisibleCount = 0;
    private int lastDrawCalls    = 0;
    private long frameCount      = 0L;

    // ─────────────────────────────────────────────────────────────────
    //  建構
    // ─────────────────────────────────────────────────────────────────

    public LODRenderDispatcher(LODChunkManager chunkManager) {
        this.chunkManager  = chunkManager;
        this.frustumCuller = new FrustumCuller(BRRenderConfig.FRUSTUM_PADDING);
    }

    // ─────────────────────────────────────────────────────────────────
    //  公開 API
    // ─────────────────────────────────────────────────────────────────

    /**
     * 更新相機位置與視錐（每幀在渲染前呼叫）。
     *
     * @param projMatrix 投影矩陣
     * @param viewMatrix 視圖矩陣
     * @param cx 相機世界 X
     * @param cy 相機世界 Y
     * @param cz 相機世界 Z
     * @param tick 目前 tick 數（用於 eviction touch）
     */
    public void beginFrame(Matrix4f projMatrix, Matrix4f viewMatrix,
                           double cx, double cy, double cz, long tick) {
        this.camX = cx;
        this.camY = cy;
        this.camZ = cz;
        this.frameCount++;

        // 更新視錐平面
        frustumCuller.update(projMatrix, viewMatrix);

        // 處理 GPU 上傳佇列（主執行緒安全）
        LODTerrainBuffer.processUploadQueue();

        // eviction（每 60 幀執行一次）
        if (frameCount % 60 == 0) {
            chunkManager.evictStale(tick);
        }
    }

    /**
     * 執行 LOD 地形渲染（opaque pass）。
     * 必須在 GL context 執行緒呼叫，且 shader 已 bind。
     *
     * @param lodShaderVAO shader 的 VAO 綁定點（外部已 useProgram）
     */
    public void renderOpaque(int lodShaderVAO) {
        List<RenderItem> visible = collectVisible();
        lastVisibleCount = visible.size();
        lastDrawCalls    = 0;

        // 由遠到近渲染（減少 overdraw；LOD 越高越先渲染可減少 LOD pop-in）
        visible.sort(Comparator.comparingDouble(RenderItem::distSq).reversed());

        for (RenderItem item : visible) {
            LODSection sec = item.section;
            int lod = item.lod;

            if (!sec.isLODReady(lod)) continue;

            // touch（更新 LRU）
            sec.lastUsedTick = frameCount;

            // 設定 model offset uniform（外部 shader 應已 bind）
            // 實際 uniform 傳遞由 BRVoxelLODManager 負責，這裡直接 draw
            GL30.glBindVertexArray(sec.vaos[lod]);
            GL11.glDrawElements(GL11.GL_TRIANGLES, sec.indexCounts[lod],
                GL11.GL_UNSIGNED_INT, 0L);
            lastDrawCalls++;
        }

        GL30.glBindVertexArray(0);
    }

    /**
     * 執行 LOD 地形深度 pass（CSM shadow map 使用）。
     */
    public void renderDepthOnly() {
        for (LODSection sec : chunkManager.getAllSections()) {
            // 陰影 pass 使用最高可用 LOD（效能優先）
            int lod = sec.bestAvailableLOD(2); // 偏好 LOD 2
            if (lod < 0) continue;

            GL30.glBindVertexArray(sec.vaos[lod]);
            GL11.glDrawElements(GL11.GL_TRIANGLES, sec.indexCounts[lod],
                GL11.GL_UNSIGNED_INT, 0L);
        }
        GL30.glBindVertexArray(0);
    }

    // ─────────────────────────────────────────────────────────────────
    //  視錐剔除 + LOD 選擇
    // ─────────────────────────────────────────────────────────────────

    private List<RenderItem> collectVisible() {
        List<RenderItem> result = new ArrayList<>(chunkManager.getSectionCount());

        for (LODSection sec : chunkManager.getAllSections()) {
            // 視錐剔除（AABB 測試）
            if (!frustumCuller.testAABB(sec.minX, sec.minY, sec.minZ,
                                        sec.maxX, sec.maxY, sec.maxZ)) {
                continue;
            }

            // 計算到相機的距離（chunk 單位）
            double centerX = (sec.minX + sec.maxX) * 0.5;
            double centerY = (sec.minY + sec.maxY) * 0.5;
            double centerZ = (sec.minZ + sec.maxZ) * 0.5;
            double dx = centerX - camX, dy = centerY - camY, dz = centerZ - camZ;
            double distSq = dx*dx + dy*dy + dz*dz;
            double distChunks = Math.sqrt(distSq) / 16.0;

            // 選擇目標 LOD
            int targetLOD = VoxyLODMesher.distanceToLOD(distChunks);
            int actualLOD = sec.bestAvailableLOD(targetLOD);
            if (actualLOD < 0) continue; // 無可用 GPU 資源

            result.add(new RenderItem(sec, actualLOD, distSq));
        }

        return result;
    }

    // ─────────────────────────────────────────────────────────────────
    //  統計
    // ─────────────────────────────────────────────────────────────────

    public int getLastVisibleCount() { return lastVisibleCount; }
    public int getLastDrawCalls()    { return lastDrawCalls; }

    // ─────────────────────────────────────────────────────────────────
    //  內部資料結構
    // ─────────────────────────────────────────────────────────────────

    private record RenderItem(LODSection section, int lod, double distSq) {}
}
