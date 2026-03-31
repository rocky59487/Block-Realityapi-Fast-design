package com.blockreality.api.client.rendering.lod;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * LOD Terrain Buffer — 管理 LOD section 的 GPU VAO/VBO/IBO 資源。
 *
 * <p>所有 OpenGL 呼叫必須在主執行緒（GL context 執行緒）執行。
 * Worker 執行緒透過 {@link #queueUpload} 將網格資料放入佇列，
 * 主執行緒在每幀開始時呼叫 {@link #processUploadQueue} 完成 GPU 上傳。
 *
 * <p>頂點格式（interleaved）：
 * <pre>
 * [ x, y, z,  nx, ny, nz,  u, v,  matId ] × vertexCount
 * stride = 9 floats = 36 bytes
 * </pre>
 *
 * @author Block Reality Team
 */
@OnlyIn(Dist.CLIENT)
public final class LODTerrainBuffer {

    private static final Logger LOG = LoggerFactory.getLogger("BR-LODBuffer");

    /** Interleaved VBO stride：xyz(3) + normal(3) + uv(2) + matId(1) = 9 floats */
    public static final int STRIDE_FLOATS = 9;
    public static final int STRIDE_BYTES  = STRIDE_FLOATS * Float.BYTES;

    /** 每幀最多上傳此數量的 pending mesh（防止主執行緒卡頓） */
    private static final int MAX_UPLOADS_PER_FRAME = 8;

    // ── 全域待上傳佇列（Worker → 主執行緒） ─────────────────────────
    private static final Queue<UploadRequest> uploadQueue = new ConcurrentLinkedQueue<>();

    // ── 統計 ─────────────────────────────────────────────────────────
    private static int totalVAOs = 0;
    private static long totalVRAM = 0L; // bytes

    // 靜態工具類，不可實例化
    private LODTerrainBuffer() {}

    // ─────────────────────────────────────────────────────────────────
    //  Worker 側 API
    // ─────────────────────────────────────────────────────────────────

    /**
     * Worker 執行緒呼叫：將網格資料放入待上傳佇列。
     * 執行緒安全。
     */
    public static void queueUpload(LODSection section, int lod, VoxyLODMesher.LODMeshData mesh) {
        uploadQueue.offer(new UploadRequest(section, lod, mesh));
    }

    // ─────────────────────────────────────────────────────────────────
    //  主執行緒 API（必須在 GL context 執行緒呼叫）
    // ─────────────────────────────────────────────────────────────────

    /**
     * 主執行緒每幀呼叫：處理待上傳佇列，執行 GPU 上傳。
     */
    public static void processUploadQueue() {
        int processed = 0;
        UploadRequest req;
        while (processed < MAX_UPLOADS_PER_FRAME && (req = uploadQueue.poll()) != null) {
            uploadMesh(req.section, req.lod, req.mesh);
            processed++;
        }
    }

    /**
     * 刪除 LODSection 的指定 LOD 等級 GPU 資源。
     * 必須在主執行緒呼叫。
     */
    public static void freeSection(LODSection sec, int lod) {
        if (sec.vaos[lod] != 0) {
            GL30.glDeleteVertexArrays(sec.vaos[lod]);
            sec.vaos[lod] = 0;
            totalVAOs--;
        }
        if (sec.vbos[lod] != 0) {
            GL15.glDeleteBuffers(sec.vbos[lod]);
            sec.vbos[lod] = 0;
        }
        if (sec.ibos[lod] != 0) {
            GL15.glDeleteBuffers(sec.ibos[lod]);
            sec.ibos[lod] = 0;
        }
        sec.indexCounts[lod] = 0;
    }

    /**
     * 刪除 LODSection 的所有 LOD 等級 GPU 資源。
     */
    public static void freeSectionAll(LODSection sec) {
        for (int lod = 0; lod < 4; lod++) freeSection(sec, lod);
        sec.gpuReady = false;
    }

    /** @return 目前已分配的 VAO 總數 */
    public static int getTotalVAOs() { return totalVAOs; }

    /** @return 估計已使用的 VRAM（bytes） */
    public static long getTotalVRAM() { return totalVRAM; }

    // ─────────────────────────────────────────────────────────────────
    //  內部實作
    // ─────────────────────────────────────────────────────────────────

    /**
     * 實際執行 GPU 上傳：建立 VAO/VBO/IBO，上傳 interleaved 頂點資料。
     */
    private static void uploadMesh(LODSection sec, int lod, VoxyLODMesher.LODMeshData mesh) {
        if (mesh.isEmpty()) return;

        // 釋放舊資源
        freeSection(sec, lod);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 建立 VAO
            int vao = GL30.glGenVertexArrays();
            GL30.glBindVertexArray(vao);

            // 建立 VBO（interleaved：xyz + nxnynz + uv + matId）
            int vbo = GL15.glGenBuffers();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);

            float[] interleaved = buildInterleavedVBO(mesh);
            FloatBuffer fb = stack.mallocFloat(interleaved.length);
            fb.put(interleaved).flip();
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, fb, GL15.GL_STATIC_DRAW);

            // 設定頂點屬性指標
            // attrib 0: position (xyz)
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, STRIDE_BYTES, 0L);
            GL20.glEnableVertexAttribArray(0);
            // attrib 1: normal (xyz)
            GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, STRIDE_BYTES, 3L * Float.BYTES);
            GL20.glEnableVertexAttribArray(1);
            // attrib 2: uv
            GL20.glVertexAttribPointer(2, 2, GL11.GL_FLOAT, false, STRIDE_BYTES, 6L * Float.BYTES);
            GL20.glEnableVertexAttribArray(2);
            // attrib 3: material ID (float-encoded int)
            GL20.glVertexAttribPointer(3, 1, GL11.GL_FLOAT, false, STRIDE_BYTES, 8L * Float.BYTES);
            GL20.glEnableVertexAttribArray(3);

            // 建立 IBO
            int ibo = GL15.glGenBuffers();
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ibo);
            IntBuffer ib = stack.mallocInt(mesh.indexCount());
            ib.put(mesh.indices(), 0, mesh.indexCount()).flip();
            GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, ib, GL15.GL_STATIC_DRAW);

            GL30.glBindVertexArray(0);

            // 更新 LODSection 狀態
            sec.vaos[lod]        = vao;
            sec.vbos[lod]        = vbo;
            sec.ibos[lod]        = ibo;
            sec.indexCounts[lod] = mesh.indexCount();
            sec.gpuReady         = true;

            // 統計
            totalVAOs++;
            long vramEstimate = (long) interleaved.length * Float.BYTES + (long) mesh.indexCount() * Integer.BYTES;
            totalVRAM += vramEstimate;

        } catch (Exception e) {
            LOG.error("Failed to upload LOD mesh for section ({},{},{}) LOD {}",
                sec.sectionX, sec.sectionY, sec.sectionZ, lod, e);
        }
    }

    /**
     * 將 LODMeshData 的分離陣列打包成 interleaved VBO 格式。
     * 格式：[x,y,z, nx,ny,nz, u,v, matId] per vertex
     */
    private static float[] buildInterleavedVBO(VoxyLODMesher.LODMeshData mesh) {
        int vc = mesh.vertexCount();
        float[] out = new float[vc * STRIDE_FLOATS];
        float[] pos  = mesh.positions();  // xyz × vc
        float[] norm = mesh.normals();    // xyz × vc
        float[] uvs  = mesh.uvs();        // uv  × vc
        int[]   mats = mesh.materialIds();// per-quad（每 4 頂點一個 matId）

        for (int v = 0; v < vc; v++) {
            int base = v * STRIDE_FLOATS;
            out[base    ] = pos[v * 3    ];
            out[base + 1] = pos[v * 3 + 1];
            out[base + 2] = pos[v * 3 + 2];
            out[base + 3] = norm[v * 3    ];
            out[base + 4] = norm[v * 3 + 1];
            out[base + 5] = norm[v * 3 + 2];
            out[base + 6] = uvs[v * 2    ];
            out[base + 7] = uvs[v * 2 + 1];
            // matId 每 quad 4 頂點共用
            int quadIdx = v / 4;
            out[base + 8] = (mats != null && quadIdx < mats.length) ? mats[quadIdx] : 0f;
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────────
    //  內部資料結構
    // ─────────────────────────────────────────────────────────────────

    private record UploadRequest(LODSection section, int lod, VoxyLODMesher.LODMeshData mesh) {}
}
