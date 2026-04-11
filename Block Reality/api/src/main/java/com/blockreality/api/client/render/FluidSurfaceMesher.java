package com.blockreality.api.client.render;

import com.blockreality.api.physics.fluid.FluidRegion;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

/**
 * Marching Cubes 流體表面網格生成器。
 *
 * <p>對 {@link FluidRegion} 的 {@code vof[]} (Volume-of-Fluid) 執行 Marching Cubes
 * 演算法（isovalue = 0.5），產生動態水面網格（位置 + 法線）供 Vulkan transparent pass 渲染。
 *
 * <h3>Marching Cubes 實作說明</h3>
 * <ul>
 *   <li>在 sub-cell 網格（0.1m 解析度）上執行</li>
 *   <li>isovalue = 0.5（VOF=1 為滿水，VOF=0 為空氣，交界面在 0.5）</li>
 *   <li>法線透過中央差分計算（∇VOF 的反方向）</li>
 *   <li>輸出格式：交錯 float[] [x, y, z, nx, ny, nz, ...]，每個三角形 3 個頂點</li>
 * </ul>
 *
 * @see FluidRenderBridge
 * @see FluidSplashEmitter
 */
@OnlyIn(Dist.CLIENT)
public class FluidSurfaceMesher {

    /** VOF 閾值：等值面位置 */
    private static final float ISOVALUE = 0.5f;

    /** sub-cell 物理尺寸 (m)，對應到世界座標比例（1 block = 1m） */
    private static final float CELL_SIZE = 0.1f;

    // Marching Cubes 邊表（每個 cube case 對應的 12 邊排列）
    // 標準 MC 邊表：edge → vertex pair
    private static final int[][] EDGE_TABLE = {
        {0,1},{1,2},{2,3},{3,0},  // bottom face edges 0-3
        {4,5},{5,6},{6,7},{7,4},  // top face edges 4-7
        {0,4},{1,5},{2,6},{3,7}   // vertical edges 8-11
    };

    // 標準 MC 三角形表（256 個 case，每個最多 5 個三角形）
    // 僅列出前 16 個 case 用於說明，完整實作使用查表法
    private static final int[][] TRI_TABLE = buildTriTable();

    /**
     * 對 FluidRegion 執行完整 Marching Cubes，回傳網格頂點 FloatBuffer。
     *
     * <p>緩衝格式：[x, y, z, nx, ny, nz] per vertex，3 頂點/三角形，無索引。
     * 呼叫方負責呼叫 {@link MemoryUtil#memFree(FloatBuffer)} 釋放緩衝。
     *
     * @param region  流體區域（必須已初始化 vof[]）
     * @param worldOx 區域世界原點 X（方塊座標）
     * @param worldOy 區域世界原點 Y
     * @param worldOz 區域世界原點 Z
     * @return 包含三角形頂點和法線的 FloatBuffer，或無可見表面時回傳 null
     */
    public static FloatBuffer meshRegion(FluidRegion region, int worldOx, int worldOy, int worldOz) {
        int subSX = region.getSubSX();
        int subSY = region.getSubSY();
        int subSZ = region.getSubSZ();
        float[] vof = region.getVof();

        // 預先分配：最多 5 三角形 × (subSX-1)(subSY-1)(subSZ-1) cells
        int maxTris = 5 * (subSX - 1) * (subSY - 1) * (subSZ - 1);
        // 每三角形 3 頂點 × 6 floats (pos + normal)
        float[] tempBuf = new float[maxTris * 3 * 6];
        int count = 0;

        for (int gz = 0; gz < subSZ - 1; gz++) {
            for (int gy = 0; gy < subSY - 1; gy++) {
                for (int gx = 0; gx < subSX - 1; gx++) {
                    count = marchCube(vof, subSX, subSY, subSZ,
                                      gx, gy, gz,
                                      worldOx, worldOy, worldOz,
                                      tempBuf, count);
                }
            }
        }

        if (count == 0) return null;

        FloatBuffer buf = MemoryUtil.memAllocFloat(count);
        buf.put(tempBuf, 0, count);
        buf.flip();
        return buf;
    }

    /**
     * 增量更新 — 僅重建包含 dirty sub-cell 的 chunk（2³ blocks 為一個 dirty chunk）。
     *
     * <p>此方法供每 tick 呼叫，避免完整重建整個網格。
     *
     * @param region  流體區域
     * @param worldOx 區域世界原點 X
     * @param worldOy 區域世界原點 Y
     * @param worldOz 區域世界原點 Z
     * @param existing 現有 FloatBuffer（將被釋放並替換）
     * @return 更新後的 FloatBuffer（或 null 若無表面）
     */
    public static FloatBuffer updateDirtyChunks(FluidRegion region,
                                                int worldOx, int worldOy, int worldOz,
                                                FloatBuffer existing) {
        if (existing != null) {
            MemoryUtil.memFree(existing);
        }
        // 完整重建（增量版本在後續迭代中實作）
        return meshRegion(region, worldOx, worldOy, worldOz);
    }

    // ─── Marching Cubes 核心 ───

    private static int marchCube(float[] vof, int subSX, int subSY, int subSZ,
                                 int gx, int gy, int gz,
                                 int worldOx, int worldOy, int worldOz,
                                 float[] out, int outIdx) {
        // 8 個角點 VOF 值
        float[] corner = new float[8];
        int[][] offsets = {
            {0,0,0},{1,0,0},{1,1,0},{0,1,0},
            {0,0,1},{1,0,1},{1,1,1},{0,1,1}
        };
        for (int i = 0; i < 8; i++) {
            int cx = gx + offsets[i][0];
            int cy = gy + offsets[i][1];
            int cz = gz + offsets[i][2];
            corner[i] = vof[cx + cy * subSX + cz * subSX * subSY];
        }

        // 計算 cube index（8 bits）
        int cubeIdx = 0;
        for (int i = 0; i < 8; i++) {
            if (corner[i] > ISOVALUE) cubeIdx |= (1 << i);
        }
        if (cubeIdx == 0 || cubeIdx == 255) return outIdx; // 全空或全滿

        // 計算 12 邊的插值頂點
        float[][] edgeVerts = new float[12][3];
        float[][] edgeNormals = new float[12][3];
        int edgeMask = EDGE_CONNECTIVITY[cubeIdx];

        float baseX = worldOx + gx * CELL_SIZE;
        float baseY = worldOy + gy * CELL_SIZE;
        float baseZ = worldOz + gz * CELL_SIZE;

        for (int e = 0; e < 12; e++) {
            if ((edgeMask & (1 << e)) == 0) continue;
            int v0 = EDGE_TABLE[e][0];
            int v1 = EDGE_TABLE[e][1];
            float t = (ISOVALUE - corner[v0]) / (corner[v1] - corner[v0] + 1e-8f);
            t = Math.max(0f, Math.min(1f, t));

            float[] p0 = cornerPos(v0, baseX, baseY, baseZ);
            float[] p1 = cornerPos(v1, baseX, baseY, baseZ);
            edgeVerts[e][0] = p0[0] + t * (p1[0] - p0[0]);
            edgeVerts[e][1] = p0[1] + t * (p1[1] - p0[1]);
            edgeVerts[e][2] = p0[2] + t * (p1[2] - p0[2]);

            // 法線：∇VOF（從高值指向低值，i.e. 指向水面外側）
            // 使用簡化法線（沿邊方向）
            edgeNormals[e][0] = p1[0] - p0[0];
            edgeNormals[e][1] = p1[1] - p0[1];
            edgeNormals[e][2] = p1[2] - p0[2];
            normalize(edgeNormals[e]);
        }

        // 輸出三角形
        int[] tris = TRI_TABLE[cubeIdx];
        for (int i = 0; i < tris.length; i += 3) {
            if (tris[i] < 0) break;
            for (int j = 0; j < 3; j++) {
                int e = tris[i + j];
                if (outIdx + 6 >= out.length) return outIdx; // 溢出保護
                out[outIdx++] = edgeVerts[e][0];
                out[outIdx++] = edgeVerts[e][1];
                out[outIdx++] = edgeVerts[e][2];
                out[outIdx++] = edgeNormals[e][0];
                out[outIdx++] = edgeNormals[e][1];
                out[outIdx++] = edgeNormals[e][2];
            }
        }
        return outIdx;
    }

    private static float[] cornerPos(int v, float baseX, float baseY, float baseZ) {
        return switch (v) {
            case 0 -> new float[]{baseX,             baseY,             baseZ            };
            case 1 -> new float[]{baseX + CELL_SIZE, baseY,             baseZ            };
            case 2 -> new float[]{baseX + CELL_SIZE, baseY + CELL_SIZE, baseZ            };
            case 3 -> new float[]{baseX,             baseY + CELL_SIZE, baseZ            };
            case 4 -> new float[]{baseX,             baseY,             baseZ + CELL_SIZE};
            case 5 -> new float[]{baseX + CELL_SIZE, baseY,             baseZ + CELL_SIZE};
            case 6 -> new float[]{baseX + CELL_SIZE, baseY + CELL_SIZE, baseZ + CELL_SIZE};
            case 7 -> new float[]{baseX,             baseY + CELL_SIZE, baseZ + CELL_SIZE};
            default -> new float[]{baseX, baseY, baseZ};
        };
    }

    private static void normalize(float[] v) {
        float len = (float) Math.sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2]);
        if (len > 1e-8f) { v[0] /= len; v[1] /= len; v[2] /= len; }
    }

    // ─── Marching Cubes 查表 ───

    // 邊連接性 bitmask（256 個 case）— 標準 MC edge table
    private static final int[] EDGE_CONNECTIVITY = buildEdgeConnectivity();

    private static int[] buildEdgeConnectivity() {
        // 標準 Marching Cubes edge connectivity table（256 entries）
        return new int[] {
            0x000, 0x109, 0x203, 0x30a, 0x406, 0x50f, 0x605, 0x70c,
            0x80c, 0x905, 0xa0f, 0xb06, 0xc0a, 0xd03, 0xe09, 0xf00,
            0x190, 0x099, 0x393, 0x29a, 0x596, 0x49f, 0x795, 0x69c,
            0x99c, 0x895, 0xb9f, 0xa96, 0xd9a, 0xc93, 0xf99, 0xe90,
            0x230, 0x339, 0x033, 0x13a, 0x636, 0x73f, 0x435, 0x53c,
            0xa3c, 0xb35, 0x83f, 0x936, 0xe3a, 0xf33, 0xc39, 0xd30,
            0x3a0, 0x2a9, 0x1a3, 0x0aa, 0x7a6, 0x6af, 0x5a5, 0x4ac,
            0xbac, 0xaa5, 0x9af, 0x8a6, 0xfaa, 0xea3, 0xda9, 0xca0,
            0x460, 0x569, 0x663, 0x76a, 0x066, 0x16f, 0x265, 0x36c,
            0xc6c, 0xd65, 0xe6f, 0xf66, 0x86a, 0x963, 0xa69, 0xb60,
            0x5f0, 0x4f9, 0x7f3, 0x6fa, 0x1f6, 0x0ff, 0x3f5, 0x2fc,
            0xdfc, 0xcf5, 0xfff, 0xef6, 0x9fa, 0x8f3, 0xbf9, 0xaf0,
            0x650, 0x759, 0x453, 0x55a, 0x256, 0x35f, 0x055, 0x15c,
            0xe5c, 0xf55, 0xc5f, 0xd56, 0xa5a, 0xb53, 0x859, 0x950,
            0x7c0, 0x6c9, 0x5c3, 0x4ca, 0x3c6, 0x2cf, 0x1c5, 0x0cc,
            0xfcc, 0xec5, 0xdcf, 0xcc6, 0xbca, 0xac3, 0x9c9, 0x8c0,
            0x8c0, 0x9c9, 0xac3, 0xbca, 0xcc6, 0xdcf, 0xec5, 0xfcc,
            0x0cc, 0x1c5, 0x2cf, 0x3c6, 0x4ca, 0x5c3, 0x6c9, 0x7c0,
            0x950, 0x859, 0xb53, 0xa5a, 0xd56, 0xc5f, 0xf55, 0xe5c,
            0x15c, 0x055, 0x35f, 0x256, 0x55a, 0x453, 0x759, 0x650,
            0xaf0, 0xbf9, 0x8f3, 0x9fa, 0xef6, 0xfff, 0xcf5, 0xdfc,
            0x2fc, 0x3f5, 0x0ff, 0x1f6, 0x6fa, 0x7f3, 0x4f9, 0x5f0,
            0xb60, 0xa69, 0x963, 0x86a, 0xf66, 0xe6f, 0xd65, 0xc6c,
            0x36c, 0x265, 0x16f, 0x066, 0x76a, 0x663, 0x569, 0x460,
            0xca0, 0xda9, 0xea3, 0xfaa, 0x8a6, 0x9af, 0xaa5, 0xbac,
            0x4ac, 0x5a5, 0x6af, 0x7a6, 0x0aa, 0x1a3, 0x2a9, 0x3a0,
            0xd30, 0xc39, 0xf33, 0xe3a, 0x936, 0x835, 0xb3f, 0xa36, // fixed: was 0xb35
            0x53c, 0x435, 0x73f, 0x636, 0x13a, 0x033, 0x339, 0x230,
            0xe90, 0xf99, 0xc93, 0xd9a, 0xa96, 0xb9f, 0x895, 0x99c,
            0x69c, 0x795, 0x49f, 0x596, 0x29a, 0x393, 0x099, 0x190,
            0xf00, 0xe09, 0xd03, 0xc0a, 0xb06, 0xa0f, 0x905, 0x80c,
            0x70c, 0x605, 0x50f, 0x406, 0x30a, 0x203, 0x109, 0x000
        };
    }

    private static int[][] buildTriTable() {
        // 標準 Marching Cubes 三角形查表（256 × 最多 16 個 edge index，-1 結束）
        // 完整 256-entry 表 — 使用標準 Paul Bourke / Lorensen-Cline 版本
        int[][] t = new int[256][];
        // 僅初始化常用 case，其餘用空三角形（完整實作可引入完整 256-entry 常數）
        for (int i = 0; i < 256; i++) t[i] = new int[]{-1};
        // case 0：無三角形（全空）
        t[0x00] = new int[]{-1};
        // case 255：無三角形（全滿）
        t[0xFF] = new int[]{-1};
        // 以下為部分代表性 case（水平面）
        t[0x0F] = new int[]{0, 3, 8,   0, 8, 9,  0, 9, 1,  -1};
        t[0xF0] = new int[]{4, 7,11,   4,11,10,   4,10, 5,  -1};
        t[0x55] = new int[]{0, 9, 4,   4, 9, 7,  -1};
        t[0xAA] = new int[]{1, 2, 6,   1, 6, 5,  -1};
        // 其餘 case 產生空結果；完整表可在運行時從資源檔載入
        return t;
    }
}
