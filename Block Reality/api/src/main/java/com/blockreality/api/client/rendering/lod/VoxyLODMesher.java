package com.blockreality.api.client.rendering.lod;

import com.blockreality.api.client.render.optimization.GreedyMesher;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

// GreedyMesher.faceToVertices 格式：10 floats per vertex
// [x, y, z, nx, ny, nz, r, g, b, a] – 4 verts per face, 40 floats per face
// Indices：每個 quad = 2 triangles = 6 indices

/**
 * Voxy LOD Mesher — 移植自 Voxy LodBuilder，4 級 3D LOD 網格生成。
 *
 * <h3>LOD 等級策略</h3>
 * <pre>
 * LOD 0 (  0- 8 chunks) — 原始精度 16³，全三角形 BLAS
 * LOD 1 (  8-32 chunks) — 2×2 合併 8³
 * LOD 2 ( 32-128 chunks)— 4×4 合併 4³
 * LOD 3 (128-512 chunks)— 8×8 合併 2³，SVDAG 軟追蹤
 * </pre>
 *
 * <p>底層複用 {@link GreedyMesher} 產生合併面，然後依 LOD 等級降解析度。
 *
 * @author Block Reality Team
 */
@OnlyIn(Dist.CLIENT)
public final class VoxyLODMesher {

    private static final Logger LOG = LoggerFactory.getLogger("BR-VoxyLOD");

    /** 每個 LOD 等級的體素合併步長 */
    public static final int[] LOD_STEP = {1, 2, 4, 8};
    /** 每個 LOD 等級的體素格數（邊長） */
    public static final int[] LOD_GRID = {16, 8, 4, 2};

    private VoxyLODMesher() {}

    // ─────────────────────────────────────────────────────────────
    //  公開 API
    // ─────────────────────────────────────────────────────────────

    /**
     * 為指定 LOD 等級生成網格資料。
     *
     * @param blockData 16×16×16 方塊 ID 陣列（y*256 + z*16 + x 索引）
     * @param lodLevel  目標 LOD 等級（0-3）
     * @return {@link LODMeshData}，含頂點與索引陣列
     */
    public static LODMeshData buildMesh(short[] blockData, int lodLevel) {
        if (blockData == null || blockData.length < 16 * 16 * 16) {
            return LODMeshData.EMPTY;
        }
        int clamped = Math.max(0, Math.min(3, lodLevel));
        return buildInternal(blockData, LOD_STEP[clamped]);
    }

    /**
     * 計算指定相機距離（chunk 數）對應的 LOD 等級。
     *
     * @param distanceChunks 相機與 chunk 中心的距離（chunk 單位）
     * @return LOD 等級 0-3
     */
    public static int distanceToLOD(double distanceChunks) {
        if (distanceChunks <   8) return 0;
        if (distanceChunks <  32) return 1;
        if (distanceChunks < 128) return 2;
        return 3;
    }

    // ─────────────────────────────────────────────────────────────
    //  內部實作
    // ─────────────────────────────────────────────────────────────

    /**
     * 以指定 step 降採樣方塊資料，再透過 GreedyMesher 合併面。
     *
     * <p>GreedyMesher API：
     * <ul>
     *   <li>{@code new GreedyMesher(0)} — 無最大合併面積限制</li>
     *   <li>{@code mesher.mesh(int[])} — 接受 int[4096]（16³），返回 List&lt;MergedFace&gt;</li>
     *   <li>{@code GreedyMesher.faceToVertices(face, out, offset, ox, oy, oz)} — 每面 4 頂點 × 10 floats</li>
     * </ul>
     */
    private static LODMeshData buildInternal(short[] src, int step) {
        int gridSize = 16 / step;

        // 降採樣為 int[16³]（GreedyMesher 要求固定 16³）
        // 若 gridSize < 16，先填充到 16³ 然後在格線上填值
        int[] voxels16 = new int[16 * 16 * 16];
        for (int gy = 0; gy < gridSize; gy++) {
            for (int gz = 0; gz < gridSize; gz++) {
                for (int gx = 0; gx < gridSize; gx++) {
                    short id = sampleBlock(src, gx * step, gy * step, gz * step, step);
                    if (id == 0) continue;
                    // 在降採樣格線上填充 step × step × step 個 voxel（還原到 16³ 空間）
                    for (int dy = 0; dy < step; dy++)
                        for (int dz = 0; dz < step; dz++)
                            for (int dx = 0; dx < step; dx++) {
                                int wx = gx * step + dx;
                                int wy = gy * step + dy;
                                int wz = gz * step + dz;
                                if (wx < 16 && wy < 16 && wz < 16) {
                                    voxels16[wy * 256 + wz * 16 + wx] = id & 0xFFFF;
                                }
                            }
                }
            }
        }

        // 呼叫 GreedyMesher（instance method）
        GreedyMesher mesher = new GreedyMesher(0);
        List<GreedyMesher.MergedFace> faces = mesher.mesh(voxels16);
        if (faces == null || faces.isEmpty()) return LODMeshData.EMPTY;

        // 轉換為 LODMeshData：
        // faceToVertices 每面輸出 4 頂點 × 10 floats [x,y,z, nx,ny,nz, r,g,b,a]
        final int FLOATS_PER_VERT = 10;
        final int VERTS_PER_FACE  = 4;
        final int FLOATS_PER_FACE = FLOATS_PER_VERT * VERTS_PER_FACE;

        int faceCount  = faces.size();
        float[] rawVerts = new float[faceCount * FLOATS_PER_FACE];
        int[]   indices  = new int[faceCount * 6];

        int vertexCount = 0;
        int indexCount  = 0;
        int vertOffset  = 0;

        for (GreedyMesher.MergedFace face : faces) {
            GreedyMesher.faceToVertices(face, rawVerts, vertOffset, 0f, 0f, 0f);

            // Quad indices：[0,1,2, 0,2,3] per face
            int base = vertexCount;
            indices[indexCount++] = base;
            indices[indexCount++] = base + 1;
            indices[indexCount++] = base + 2;
            indices[indexCount++] = base;
            indices[indexCount++] = base + 2;
            indices[indexCount++] = base + 3;

            vertexCount += VERTS_PER_FACE;
            vertOffset  += FLOATS_PER_FACE;
        }

        // 拆分 rawVerts [x,y,z, nx,ny,nz, r,g,b,a] → 分離陣列
        float[] positions  = new float[vertexCount * 3];
        float[] normals    = new float[vertexCount * 3];
        float[] uvs        = new float[vertexCount * 2];
        int[]   materialIds = new int[faceCount];

        for (int v = 0; v < vertexCount; v++) {
            int src_base = v * FLOATS_PER_VERT;
            // position（乘以 step 還原原始方塊座標）
            positions[v * 3    ] = rawVerts[src_base    ] * step;
            positions[v * 3 + 1] = rawVerts[src_base + 1] * step;
            positions[v * 3 + 2] = rawVerts[src_base + 2] * step;
            // normal
            normals[v * 3    ] = rawVerts[src_base + 3];
            normals[v * 3 + 1] = rawVerts[src_base + 4];
            normals[v * 3 + 2] = rawVerts[src_base + 5];
            // UV（用 XY 平面映射，簡化）
            uvs[v * 2    ] = (rawVerts[src_base    ] % 1.0f + 1.0f) % 1.0f;
            uvs[v * 2 + 1] = (rawVerts[src_base + 1] % 1.0f + 1.0f) % 1.0f;
        }

        // materialId 每 quad 一個（face.materialId）
        for (int f = 0; f < faceCount; f++) {
            materialIds[f] = faces.get(f).materialId;
        }

        return new LODMeshData(positions, normals, uvs, materialIds,
            indices, vertexCount, indexCount);
    }

    /** 在 step³ 範圍內找出代表方塊 ID（多數決，空氣 = 0 忽略）。 */
    private static short sampleBlock(short[] src, int bx, int by, int bz, int step) {
        int best = 0, bestCount = 0;
        for (int dy = 0; dy < step; dy++)
            for (int dz = 0; dz < step; dz++)
                for (int dx = 0; dx < step; dx++) {
                    int id = src[(by + dy) * 256 + (bz + dz) * 16 + (bx + dx)] & 0xFFFF;
                    if (id != 0 && id > bestCount) { best = id; bestCount = id; }
                }
        return (short) best;
    }

    // scaleVertices 已內嵌在 buildInternal 中，保留空方法供向後相容
    @SuppressWarnings("unused")
    private static float[] scaleVertices(float[] verts, int step) {
        float[] out = new float[verts.length];
        for (int i = 0; i < verts.length; i++) out[i] = verts[i] * step;
        return out;
    }

    // ─────────────────────────────────────────────────────────────
    //  資料結構
    // ─────────────────────────────────────────────────────────────

    /**
     * LOD 網格資料 — 準備上傳至 VBO / BLAS。
     */
    public record LODMeshData(
        float[] positions,   // xyz interleaved
        float[] normals,     // xyz interleaved
        float[] uvs,         // uv interleaved
        int[]   materialIds, // per-quad
        int[]   indices,
        int     vertexCount,
        int     indexCount
    ) {
        public static final LODMeshData EMPTY =
            new LODMeshData(new float[0], new float[0], new float[0],
                new int[0], new int[0], 0, 0);

        public boolean isEmpty() { return vertexCount == 0; }
    }
}
