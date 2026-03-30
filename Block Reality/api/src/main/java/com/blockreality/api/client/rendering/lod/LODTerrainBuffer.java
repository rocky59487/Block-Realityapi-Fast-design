package com.blockreality.api.client.rendering.lod;

import com.blockreality.api.config.BRConfig;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * LOD 地形 GPU Buffer 管理器（Phase 1-D）。
 *
 * 採用「大型 VBO 池 + 空閒清單」策略，避免頻繁的 glBufferData 呼叫：
 *   1. 預分配一個大型 GL_ARRAY_BUFFER（依 BRConfig.lodBufferCapacityK）
 *   2. 每個 chunk 在 VBO 中佔一個連續區域（BufferRegion）
 *   3. 區域釋放後加入空閒清單，下次分配優先複用
 *   4. 容量不足時自動擴展（×2）
 *
 * 同時管理對應的 Index Buffer（EBO），存放 quad index pattern：
 *   每個 quad（4 頂點）→ 2 個三角形（indices: 0,1,2, 0,2,3）
 *
 * 頂點格式：10 floats per vertex（與 VoxyLODMesher 相容）
 *   layout(location=0): xyz    (3 floats)
 *   layout(location=1): normal (3 floats)
 *   layout(location=2): color  (4 floats, rgba)
 *
 * 移植來源：Voxy GeometryManager 概念 + Block Reality 現有 VBO 實作模式
 *
 * @see VoxyLODMesher
 * @see LODChunkManager
 */
@OnlyIn(Dist.CLIENT)
public class LODTerrainBuffer {

    private static final Logger LOG = LoggerFactory.getLogger("BR-LODBuffer");

    private static final int FLOATS_PER_VERTEX  = VoxyLODMesher.FLOATS_PER_VERTEX; // 10
    private static final int VERTS_PER_FACE     = VoxyLODMesher.VERTS_PER_FACE;    // 4
    private static final int INDICES_PER_FACE   = 6; // 2 triangles per quad
    private static final int BYTES_PER_FLOAT    = Float.BYTES;

    /** 未初始化的 GL ID */
    private static final int INVALID_GL = 0;

    // ─── GL 物件 ───
    private int vaoId   = INVALID_GL;
    private int vboId   = INVALID_GL;
    private int eboId   = INVALID_GL;

    // ─── 容量管理 ───
    /** 目前 VBO 容量（float 數量） */
    private int vboCapacityFloats = 0;

    /** 目前已分配的頂點數 */
    private int allocatedFloats = 0;

    // ─── 分配記錄 ───
    /** chunkKey → BufferRegion（已分配的 VBO 區域） */
    private final Map<Long, BufferRegion> allocations = new HashMap<>();

    /**
     * VBO 中一個 chunk 的分配區域。
     */
    private static final class BufferRegion {
        final int offsetFloats;  // 在 VBO 中的 float 起始索引
        final int countFloats;   // float 數量
        final int faceCount;     // 面數（用於計算 drawCount）

        BufferRegion(int offsetFloats, int countFloats) {
            this.offsetFloats = offsetFloats;
            this.countFloats = countFloats;
            // 每個面 VERTS_PER_FACE 個頂點 × FLOATS_PER_VERTEX floats
            this.faceCount = countFloats / (FLOATS_PER_VERTEX * VERTS_PER_FACE);
        }

        int indexCount() { return faceCount * INDICES_PER_FACE; }
        int indexOffset() { return (offsetFloats / (FLOATS_PER_VERTEX * VERTS_PER_FACE))
                                   * INDICES_PER_FACE; }
    }

    // ─── 空閒清單（offset → size） ───
    private final TreeMap<Integer, Integer> freeList = new TreeMap<>();

    // ─── 統計 ───
    private int totalFaces    = 0;
    private int chunkCount    = 0;

    public LODTerrainBuffer() {}

    // ═══ 初始化 ═══

    /**
     * 建立 VAO / VBO / EBO（必須在 GL 執行緒呼叫）。
     */
    public void init() {
        if (vaoId != INVALID_GL) {
            LOG.warn("LODTerrainBuffer already initialized");
            return;
        }

        int initialCapacityK = BRConfig.INSTANCE.lodBufferCapacityK.get();
        vboCapacityFloats = initialCapacityK * 1024; // K × 1024 floats

        // 建立 VAO
        vaoId = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vaoId);

        // 建立 VBO（預分配空記憶體）
        vboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER,
            (long) vboCapacityFloats * BYTES_PER_FLOAT,
            GL15.GL_DYNAMIC_DRAW);

        // 建立 EBO（index buffer，存放 quad indices pattern）
        eboId = GL15.glGenBuffers();
        rebuildEBO(vboCapacityFloats / FLOATS_PER_VERTEX); // 最大頂點數

        // 設定頂點屬性
        int stride = FLOATS_PER_VERTEX * BYTES_PER_FLOAT;
        // location=0: position (xyz)
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, stride, 0);
        GL20.glEnableVertexAttribArray(0);
        // location=1: normal (xyz)
        GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, stride, 3 * BYTES_PER_FLOAT);
        GL20.glEnableVertexAttribArray(1);
        // location=2: color (rgba)
        GL20.glVertexAttribPointer(2, 4, GL11.GL_FLOAT, false, stride, 6 * BYTES_PER_FLOAT);
        GL20.glEnableVertexAttribArray(2);

        GL30.glBindVertexArray(0);

        LOG.info("LODTerrainBuffer init: {} MB VBO",
            (vboCapacityFloats * BYTES_PER_FLOAT) / (1024 * 1024));
    }

    // ═══ 上傳 / 釋放 ═══

    /**
     * 將 LOD mesh 資料上傳到 GPU buffer。
     *
     * 必須在 GL 執行緒（主執行緒）呼叫。
     *
     * @param chunkKey chunk 唯一鍵
     * @param vertices 頂點資料（float[]，格式: xyz, normal, rgba）
     * @return buffer offset（float 索引），失敗返回 -1
     */
    public long uploadMesh(long chunkKey, float[] vertices) {
        if (vboId == INVALID_GL) {
            LOG.error("uploadMesh called before init()");
            return -1;
        }
        if (vertices == null || vertices.length == 0) return -1;

        // 釋放舊資料（若存在）
        freeMesh(chunkKey);

        int needed = vertices.length;

        // 嘗試從空閒清單分配
        int offsetFloats = allocateFromFreeList(needed);

        if (offsetFloats < 0) {
            // 空閒清單不夠，從末尾分配
            if (allocatedFloats + needed > vboCapacityFloats) {
                if (!expandVBO(needed)) {
                    LOG.error("LODTerrainBuffer out of space for chunk key {}", chunkKey);
                    return -1;
                }
            }
            offsetFloats = allocatedFloats;
            allocatedFloats += needed;
        }

        // 上傳資料（使用 FloatBuffer 覆載，避免 heap-buffer memAddress 問題）
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER,
            (long) offsetFloats * BYTES_PER_FLOAT,
            FloatBuffer.wrap(vertices));
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

        BufferRegion region = new BufferRegion(offsetFloats, needed);
        allocations.put(chunkKey, region);

        totalFaces += region.faceCount;
        chunkCount++;

        return offsetFloats;
    }

    /**
     * 釋放指定 chunk 的 GPU buffer 區域。
     */
    public void freeMesh(long chunkKey) {
        BufferRegion region = allocations.remove(chunkKey);
        if (region == null) return;

        // 加入空閒清單
        freeList.merge(region.offsetFloats, region.countFloats, Integer::sum);
        coalesceFreeList();

        totalFaces -= region.faceCount;
        chunkCount--;
    }

    /**
     * 渲染所有已上傳的 LOD chunk。
     *
     * 使用 multi-draw 批次渲染（Phase 3 升級為 glMultiDrawElementsIndirect）。
     * 目前使用逐 chunk draw call。
     */
    public void render() {
        if (vaoId == INVALID_GL || allocations.isEmpty()) return;

        GL30.glBindVertexArray(vaoId);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, eboId);

        for (BufferRegion region : allocations.values()) {
            if (region.faceCount <= 0) continue;

            // glDrawElements: indexCount 個索引，從 indexOffset 開始
            GL11.glDrawElements(
                GL11.GL_TRIANGLES,
                region.indexCount(),
                GL11.GL_UNSIGNED_INT,
                (long) region.indexOffset() * Integer.BYTES
            );
        }

        GL30.glBindVertexArray(0);
    }

    // ═══ 內部工具 ═══

    /**
     * 重建 EBO（quad index pattern）。
     *
     * 每個 quad 4 個頂點（v0, v1, v2, v3），生成 2 個三角形：
     *   triangle 0: v0, v1, v2
     *   triangle 1: v0, v2, v3
     */
    private void rebuildEBO(int maxVertices) {
        int maxQuads = maxVertices / VERTS_PER_FACE;
        int[] indices = new int[maxQuads * INDICES_PER_FACE];

        for (int q = 0; q < maxQuads; q++) {
            int v = q * VERTS_PER_FACE;
            int i = q * INDICES_PER_FACE;
            indices[i + 0] = v;
            indices[i + 1] = v + 1;
            indices[i + 2] = v + 2;
            indices[i + 3] = v;
            indices[i + 4] = v + 2;
            indices[i + 5] = v + 3;
        }

        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, eboId);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indices, GL15.GL_STATIC_DRAW);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
    }

    /** 嘗試從空閒清單找到足夠大的區域，返回 offset 或 -1 */
    private int allocateFromFreeList(int needed) {
        for (var entry : freeList.entrySet()) {
            if (entry.getValue() >= needed) {
                int offset = entry.getKey();
                int remaining = entry.getValue() - needed;
                freeList.remove(offset);
                if (remaining > 0) {
                    freeList.put(offset + needed, remaining);
                }
                return offset;
            }
        }
        return -1;
    }

    /** 合併相鄰空閒區域 */
    private void coalesceFreeList() {
        if (freeList.size() < 2) return;
        var it = freeList.entrySet().iterator();
        var prev = it.next();
        while (it.hasNext()) {
            var curr = it.next();
            if (prev.getKey() + prev.getValue() == curr.getKey()) {
                prev.setValue(prev.getValue() + curr.getValue());
                it.remove();
            } else {
                prev = curr;
            }
        }
    }

    /** 擴展 VBO 容量（×2） */
    private boolean expandVBO(int additionalFloats) {
        int newCapacity = Math.max(vboCapacityFloats * 2,
                                   vboCapacityFloats + additionalFloats);
        int maxMB = BRConfig.INSTANCE.lodBufferCapacityK.get() * 8; // K × 8 = soft limit MB
        if ((long) newCapacity * BYTES_PER_FLOAT > (long) maxMB * 1024 * 1024) {
            LOG.warn("LODTerrainBuffer expansion would exceed soft limit ({} MB)", maxMB);
            return false;
        }

        LOG.info("LODTerrainBuffer expanding VBO: {} → {} MB",
            (vboCapacityFloats * BYTES_PER_FLOAT) / (1024 * 1024),
            (newCapacity * BYTES_PER_FLOAT) / (1024 * 1024));

        // 建立新 VBO
        // GL_COPY_READ/WRITE_BUFFER + glCopyBufferSubData 是 OpenGL 3.1 (GL31)
        int newVboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, newVboId);
        GL15.glBufferData(GL31.GL_COPY_WRITE_BUFFER,
            (long) newCapacity * BYTES_PER_FLOAT, GL15.GL_DYNAMIC_DRAW);

        // 複製現有資料
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, vboId);
        GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER,
            0, 0, (long) allocatedFloats * BYTES_PER_FLOAT);

        // 替換舊 VBO
        GL15.glDeleteBuffers(vboId);
        vboId = newVboId;
        vboCapacityFloats = newCapacity;

        // 重新綁定 VAO
        GL30.glBindVertexArray(vaoId);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId);
        int stride = FLOATS_PER_VERTEX * BYTES_PER_FLOAT;
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, stride, 0);
        GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, stride, 3 * BYTES_PER_FLOAT);
        GL20.glVertexAttribPointer(2, 4, GL11.GL_FLOAT, false, stride, 6 * BYTES_PER_FLOAT);
        GL30.glBindVertexArray(0);

        // 重建 EBO
        rebuildEBO(newCapacity / FLOATS_PER_VERTEX);

        return true;
    }

    /**
     * 釋放所有 GL 資源（必須在 GL 執行緒呼叫）。
     */
    public void cleanup() {
        if (vaoId != INVALID_GL) {
            GL30.glDeleteVertexArrays(vaoId);
            vaoId = INVALID_GL;
        }
        if (vboId != INVALID_GL) {
            GL15.glDeleteBuffers(vboId);
            vboId = INVALID_GL;
        }
        if (eboId != INVALID_GL) {
            GL15.glDeleteBuffers(eboId);
            eboId = INVALID_GL;
        }
        allocations.clear();
        freeList.clear();
        allocatedFloats = 0;
        totalFaces = 0;
        chunkCount = 0;
        LOG.info("LODTerrainBuffer cleanup complete");
    }

    // ═══ 統計 ═══

    public boolean isInitialized() { return vaoId != INVALID_GL; }
    public int getChunkCount()     { return chunkCount; }
    public int getTotalFaces()     { return totalFaces; }
    public int getVaoId()          { return vaoId; }
    public int getVboId()          { return vboId; }
    public float getUtilization()  {
        return vboCapacityFloats > 0 ? (float) allocatedFloats / vboCapacityFloats : 0f;
    }
}
