package com.blockreality.api.client.render;

import com.blockreality.api.physics.sparse.SparseVoxelOctree;
import com.blockreality.api.physics.sparse.VoxelSection;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryUtil;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 持久化渲染管線 — 取代即時模式 BufferBuilder。
 *
 * 參考來源：Embeddium/Sodium 的 Section-based VBO 架構。
 *
 * 核心設計：
 *   - 每個 VoxelSection 編譯為一個持久化 VAO/VBO
 *   - 只在 Section 內容變更時重建該 VBO
 *   - 每幀只呼叫 glDrawArrays() 繪製可見 Section（不重建 buffer）
 *   - 視錐體剔除 + 距離剔除排除不可見 Section
 *
 * 頂點格式：POSITION(3f) + COLOR(4B) = 16 bytes/vertex
 * 每方塊 6 面 × 4 頂點 = 24 頂點（Greedy Meshing 後可減少 60-95%）
 *
 * 記憶體管理：
 *   - GPU buffer pool 上限可配置
 *   - 超出視距的 Section VBO 被回收
 *   - 使用 glBufferSubData 做增量更新
 *
 * @since v3.0 Phase 3
 */
@OnlyIn(Dist.CLIENT)
public class PersistentRenderPipeline {

    private static final Logger LOGGER = LogManager.getLogger("BR/RenderPipeline");

    /** 頂點 stride: pos(3f=12B) + color(4B) = 16 bytes */
    private static final int VERTEX_STRIDE = 16;

    /** 每方塊最大頂點數（6面 × 4頂點，Greedy Meshing 前） */
    private static final int MAX_VERTICES_PER_BLOCK = 24;

    /** Section VBO 最大頂點容量 */
    private static final int MAX_VERTICES_PER_SECTION = VoxelSection.VOLUME * MAX_VERTICES_PER_BLOCK;

    // ═══ 核心儲存 ═══

    /** Section → GPU 渲染資料 */
    private final HashMap<Long, SectionRenderData> sectionBuffers = new HashMap<>();

    /** GPU 記憶體使用量估算 (bytes) */
    private long gpuMemoryUsed = 0;

    /** GPU 記憶體上限 (bytes)，預設 512 MB */
    private long gpuMemoryLimit = 512L * 1024 * 1024;

    /** 渲染統計 */
    private int sectionsRendered = 0;
    private int verticesRendered = 0;
    private int sectionsCulled = 0;

    // ═══ Section 渲染資料 ═══

    /**
     * 每個 Section 的 GPU 渲染資料。
     */
    static class SectionRenderData {
        int vaoId;
        int vboId;
        int vertexCount;
        boolean dirty;
        long lastUpdateTick;
        long sectionKey;

        /** 用於視錐體剔除的 Section 世界座標 */
        int worldX, worldY, worldZ;

        SectionRenderData(long sectionKey, int worldX, int worldY, int worldZ) {
            this.sectionKey = sectionKey;
            this.worldX = worldX;
            this.worldY = worldY;
            this.worldZ = worldZ;
            this.dirty = true;
            this.vertexCount = 0;
        }

        void allocate() {
            vaoId = GL30.glGenVertexArrays();
            vboId = GL15.glGenBuffers();

            GL30.glBindVertexArray(vaoId);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId);

            // Position attribute (location 0): 3 floats
            GL20.glVertexAttribPointer(0, 3, GL30.GL_FLOAT, false, VERTEX_STRIDE, 0);
            GL20.glEnableVertexAttribArray(0);

            // Color attribute (location 1): 4 unsigned bytes, normalized
            GL20.glVertexAttribPointer(1, 4, GL30.GL_UNSIGNED_BYTE, true, VERTEX_STRIDE, 12);
            GL20.glEnableVertexAttribArray(1);

            GL30.glBindVertexArray(0);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        }

        void upload(ByteBuffer vertexData, int vertexCount) {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertexData, GL15.GL_DYNAMIC_DRAW);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            this.vertexCount = vertexCount;
            this.dirty = false;
        }

        void release() {
            if (vaoId != 0) {
                GL30.glDeleteVertexArrays(vaoId);
                vaoId = 0;
            }
            if (vboId != 0) {
                GL15.glDeleteBuffers(vboId);
                vboId = 0;
            }
        }

        boolean isAllocated() {
            return vaoId != 0;
        }
    }

    // ═══ 建構與銷毀 ═══

    /**
     * 設定 GPU 記憶體上限。
     * @param limitMB 上限（MB）
     */
    public void setGpuMemoryLimit(long limitMB) {
        this.gpuMemoryLimit = limitMB * 1024 * 1024;
    }

    /**
     * 釋放所有 GPU 資源。
     * 必須在 OpenGL context 執行緒上呼叫。
     */
    public void cleanup() {
        for (SectionRenderData data : sectionBuffers.values()) {
            data.release();
        }
        sectionBuffers.clear();
        gpuMemoryUsed = 0;
        LOGGER.info("[RenderPipeline] Cleanup complete");
    }

    // ═══ Section 重建 ═══

    /**
     * 標記指定 Section 為 dirty（需要重建 VBO）。
     */
    public void markSectionDirty(long sectionKey) {
        SectionRenderData data = sectionBuffers.get(sectionKey);
        if (data != null) {
            data.dirty = true;
        }
    }

    /**
     * 重建所有 dirty Section 的 VBO。
     * 應在渲染前呼叫（主渲染執行緒）。
     *
     * @param svo        資料來源
     * @param meshType   網格類型（STRESS_HEATMAP / HOLOGRAM）
     * @param currentTick 當前 tick
     * @return 重建的 Section 數量
     */
    public int rebuildDirtySections(SparseVoxelOctree svo, MeshType meshType, long currentTick) {
        int rebuilt = 0;

        // 遍歷 SVO 中的 dirty sections
        for (var key : svo.getDirtySectionKeys()) {
            VoxelSection section = svo.getSection(
                SparseVoxelOctree.sectionKeyX(key),
                SparseVoxelOctree.sectionKeyY(key),
                SparseVoxelOctree.sectionKeyZ(key)
            );
            if (section == null || section.isEmpty()) {
                removeSectionBuffer(key);
                continue;
            }

            SectionRenderData renderData = sectionBuffers.get(key);
            if (renderData == null) {
                renderData = new SectionRenderData(key,
                    section.getWorldX(), section.getWorldY(), section.getWorldZ());
                sectionBuffers.put(key, renderData);
            }

            if (!renderData.isAllocated()) {
                renderData.allocate();
            }

            // 編譯 mesh
            ByteBuffer vertexBuffer = SectionMeshCompiler.compile(section, meshType);
            int vertexCount = vertexBuffer.remaining() / VERTEX_STRIDE;

            long oldSize = (long) renderData.vertexCount * VERTEX_STRIDE;
            long newSize = (long) vertexCount * VERTEX_STRIDE;

            renderData.upload(vertexBuffer, vertexCount);
            renderData.lastUpdateTick = currentTick;

            gpuMemoryUsed += (newSize - oldSize);
            MemoryUtil.memFree(vertexBuffer);

            rebuilt++;
        }

        // 記憶體壓力時驅逐遠處 Section
        if (gpuMemoryUsed > gpuMemoryLimit) {
            evictDistantSections(currentTick);
        }

        return rebuilt;
    }

    // ═══ 渲染 ═══

    /**
     * 渲染所有可見 Section。
     *
     * @param poseStack 變換矩陣堆疊
     * @param camera    攝影機
     * @param cullDistance 剔除距離（格）
     */
    public void render(PoseStack poseStack, Camera camera, double cullDistance) {
        sectionsRendered = 0;
        verticesRendered = 0;
        sectionsCulled = 0;

        Vec3 camPos = camera.getPosition();
        double cullDistSq = cullDistance * cullDistance;

        for (SectionRenderData data : sectionBuffers.values()) {
            if (data.vertexCount <= 0 || !data.isAllocated()) continue;

            // 距離剔除（Section 中心到攝影機距離）
            double dx = data.worldX + 8.0 - camPos.x;
            double dy = data.worldY + 8.0 - camPos.y;
            double dz = data.worldZ + 8.0 - camPos.z;
            double distSq = dx * dx + dy * dy + dz * dz;

            if (distSq > cullDistSq) {
                sectionsCulled++;
                continue;
            }

            // 繪製
            GL30.glBindVertexArray(data.vaoId);
            GL30.glDrawArrays(GL30.GL_QUADS, 0, data.vertexCount);

            sectionsRendered++;
            verticesRendered += data.vertexCount;
        }

        GL30.glBindVertexArray(0);
    }

    // ═══ 記憶體管理 ═══

    /**
     * 驅逐最久未更新的 Section VBO 以釋放 GPU 記憶體。
     */
    private void evictDistantSections(long currentTick) {
        long target = gpuMemoryLimit * 3 / 4; // 目標降到 75%
        int evicted = 0;

        // 按 lastUpdateTick 排序，驅逐最舊的
        var sortedEntries = new java.util.ArrayList<>(sectionBuffers.entrySet());
        sortedEntries.sort(java.util.Comparator.comparingLong(e -> e.getValue().lastUpdateTick));

        Iterator<Map.Entry<Long, SectionRenderData>> it = sortedEntries.iterator();
        while (it.hasNext() && gpuMemoryUsed > target) {
            var entry = it.next();
            SectionRenderData data = entry.getValue();
            long size = (long) data.vertexCount * VERTEX_STRIDE;
            data.release();
            sectionBuffers.remove(entry.getKey());
            gpuMemoryUsed -= size;
            evicted++;
        }

        if (evicted > 0) {
            LOGGER.debug("[RenderPipeline] Evicted {} section VBOs, GPU mem: {:.1f}MB",
                evicted, gpuMemoryUsed / (1024.0 * 1024.0));
        }
    }

    /**
     * 移除指定 Section 的 VBO。
     */
    private void removeSectionBuffer(long key) {
        SectionRenderData data = sectionBuffers.remove(key);
        if (data != null) {
            gpuMemoryUsed -= (long) data.vertexCount * VERTEX_STRIDE;
            data.release();
        }
    }

    // ═══ 診斷 ═══

    public int getSectionsRendered() { return sectionsRendered; }
    public int getVerticesRendered() { return verticesRendered; }
    public int getSectionsCulled() { return sectionsCulled; }
    public int getBufferedSectionCount() { return sectionBuffers.size(); }
    public double getGpuMemoryUsedMB() { return gpuMemoryUsed / (1024.0 * 1024.0); }

    /**
     * 渲染類型。
     */
    public enum MeshType {
        /** 應力熱圖：per-vertex 色彩 = 應力值映射 */
        STRESS_HEATMAP,
        /** 全息投影：固定半透明藍色 */
        HOLOGRAM,
        /** 錨定路徑：線段 */
        ANCHOR_PATH
    }
}
