package com.blockreality.api.client.render.optimization;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

/**
 * Greedy Meshing 引擎 — Sodium/Embeddium 核心優化技術。
 *
 * 演算法：
 *   對每個軸向面（6 方向），掃描 2D 切片，將相鄰同材質面合併為最大矩形。
 *   參考 Mikola Lysenko (0fps) 的經典 Greedy Meshing 論文。
 *
 * Sodium 啟發的額外優化：
 *   - 每個 16³ section 獨立 mesh（平行化友好）
 *   - 面材質 ID 打包（同材質才合併）
 *   - 法線壓縮（6 方向 → 3-bit 編碼）
 *   - 結果直接寫入 VBO 格式的頂點陣列
 *
 * 效果：
 *   一面 16×16 的同材質牆 → 256 face → 1 face（256× 減少）
 */
@OnlyIn(Dist.CLIENT)
public final class GreedyMesher {

    private final int maxMergeArea;

    /** 合併後的面資料 */
    public static final class MergedFace {
        public final int axis;      // 0=X, 1=Y, 2=Z
        public final boolean positive; // 面朝正方向?
        public final int u0, v0;    // 切片座標起始
        public final int u1, v1;    // 切片座標結束（exclusive）
        public final int depth;     // 切片深度（軸向位置）
        public final int materialId; // 材質 ID

        public MergedFace(int axis, boolean positive, int u0, int v0,
                           int u1, int v1, int depth, int materialId) {
            this.axis = axis;
            this.positive = positive;
            this.u0 = u0; this.v0 = v0;
            this.u1 = u1; this.v1 = v1;
            this.depth = depth;
            this.materialId = materialId;
        }

        /** 合併面的面積（頂點數 = area * 4，但 quad → 2 triangle = 6 indices） */
        public int area() { return (u1 - u0) * (v1 - v0); }
    }

    public GreedyMesher(int maxMergeArea) {
        this.maxMergeArea = maxMergeArea;
    }

    /**
     * 對 16³ section 執行 Greedy Meshing。
     *
     * @param voxels   16×16×16 體素陣列，值為材質 ID（0 = 空氣/跳過）
     * @return 合併後的面列表
     */
    public List<MergedFace> mesh(int[] voxels) {
        // Input validation: voxels must be 16³ = 4096
        if (voxels == null || voxels.length != 4096) {
            throw new IllegalArgumentException(
                "GreedyMesher requires voxels array of length 4096 (16³), got " +
                (voxels == null ? "null" : voxels.length));
        }

        List<MergedFace> result = new ArrayList<>();

        // 6 方向掃描
        for (int axis = 0; axis < 3; axis++) {
            for (int positive = 0; positive <= 1; positive++) {
                meshAxis(voxels, axis, positive == 1, result);
            }
        }

        return result;
    }

    /**
     * 單軸 Greedy Meshing — 經典掃描線演算法。
     */
    private void meshAxis(int[] voxels, int axis, boolean positive,
                           List<MergedFace> out) {
        // 軸映射: axis=0(X) → 掃描 YZ 面, axis=1(Y) → 掃描 XZ 面, axis=2(Z) → 掃描 XY 面
        int uAxis, vAxis;
        if (axis == 0)      { uAxis = 1; vAxis = 2; } // Y, Z
        else if (axis == 1) { uAxis = 0; vAxis = 2; } // X, Z
        else                { uAxis = 0; vAxis = 1; } // X, Y

        // 逐深度切片掃描
        for (int depth = 0; depth < 16; depth++) {
            // 建立 16×16 面遮罩
            int[] mask = new int[16 * 16]; // materialId, 0 = no face
            boolean hasFace = false;

            for (int v = 0; v < 16; v++) {
                for (int u = 0; u < 16; u++) {
                    int[] pos = new int[3];
                    pos[axis] = depth;
                    pos[uAxis] = u;
                    pos[vAxis] = v;

                    int idx = pos[0] + pos[1] * 16 + pos[2] * 256;
                    int matId = (idx >= 0 && idx < voxels.length) ? voxels[idx] : 0;

                    if (matId == 0) continue; // 空氣

                    // 檢查鄰居是否遮擋此面
                    int neighborDepth = positive ? depth + 1 : depth - 1;
                    if (neighborDepth >= 0 && neighborDepth < 16) {
                        int[] nPos = new int[3];
                        nPos[axis] = neighborDepth;
                        nPos[uAxis] = u;
                        nPos[vAxis] = v;
                        int nIdx = nPos[0] + nPos[1] * 16 + nPos[2] * 256;
                        if (nIdx >= 0 && nIdx < voxels.length && voxels[nIdx] != 0) {
                            continue; // 被遮擋，不生成面
                        }
                    }

                    mask[u + v * 16] = matId;
                    hasFace = true;
                }
            }

            if (!hasFace) continue;

            // Greedy 合併：掃描 mask 找最大矩形
            boolean[] visited = new boolean[256];
            for (int v = 0; v < 16; v++) {
                for (int u = 0; u < 16; u++) {
                    int idx = u + v * 16;
                    if (visited[idx] || mask[idx] == 0) continue;

                    int matId = mask[idx];

                    // 找最大寬度
                    int width = 1;
                    while (u + width < 16
                        && mask[(u + width) + v * 16] == matId
                        && !visited[(u + width) + v * 16]
                        && width * 1 <= maxMergeArea) {
                        width++;
                    }

                    // 找最大高度
                    int height = 1;
                    outer:
                    while (v + height < 16 && width * (height + 1) <= maxMergeArea) {
                        for (int du = 0; du < width; du++) {
                            int checkIdx = (u + du) + (v + height) * 16;
                            if (mask[checkIdx] != matId || visited[checkIdx]) {
                                break outer;
                            }
                        }
                        height++;
                    }

                    // 標記已訪問
                    for (int dv = 0; dv < height; dv++) {
                        for (int du = 0; du < width; du++) {
                            visited[(u + du) + (v + dv) * 16] = true;
                        }
                    }

                    // 產出合併面
                    out.add(new MergedFace(
                        axis, positive, u, v, u + width, v + height, depth, matId
                    ));
                }
            }
        }
    }

    /**
     * 將 MergedFace 轉換為頂點數據。
     *
     * @param face 合併面
     * @param out  頂點輸出陣列（每頂點 10 float: xyz, normal_xyz, rgba）
     * @param offset 寫入偏移（float index）
     * @param originX section 原點 X（世界座標）
     * @param originY section 原點 Y
     * @param originZ section 原點 Z
     * @return 寫入的 float 數量
     */
    public static int faceToVertices(MergedFace face, float[] out, int offset,
                                      float originX, float originY, float originZ) {
        // Output bounds check: ensure we have space for 4 vertices × 10 floats
        final int FLOATS_PER_VERTEX = 10;
        final int VERTICES_PER_FACE = 4;
        final int FLOATS_NEEDED = FLOATS_PER_VERTEX * VERTICES_PER_FACE;

        if (offset < 0 || offset + FLOATS_NEEDED > out.length) {
            throw new IndexOutOfBoundsException(
                "Output buffer overflow: offset=" + offset + ", need=" + FLOATS_NEEDED +
                ", available=" + (out.length - offset));
        }

        // 法線
        float nx = 0, ny = 0, nz = 0;
        float sign = face.positive ? 1.0f : -1.0f;
        if (face.axis == 0) nx = sign;
        else if (face.axis == 1) ny = sign;
        else nz = sign;

        // 材質顏色查表（簡化 — 實際應從 MaterialRegistry 取得）
        float r, g, b, a = 1.0f;
        switch (face.materialId) {
            case 1 -> { r = 0.75f; g = 0.75f; b = 0.73f; } // 混凝土
            case 2 -> { r = 0.6f;  g = 0.65f; b = 0.7f;  } // 鋼材
            case 3 -> { r = 0.65f; g = 0.45f; b = 0.25f; } // 木材
            case 4 -> { r = 0.5f;  g = 0.52f; b = 0.55f; } // 鋼筋
            default -> { r = 0.8f; g = 0.8f;  b = 0.8f;  }
        }

        // 計算 4 個頂點座標（依軸向）
        float[][] verts = computeQuadVerts(face, originX, originY, originZ);

        // 輸出 4 頂點 × 10 float（position3 + normal3 + color4）
        int written = 0;
        for (int i = 0; i < 4; i++) {
            out[offset + written++] = verts[i][0];
            out[offset + written++] = verts[i][1];
            out[offset + written++] = verts[i][2];
            out[offset + written++] = nx;
            out[offset + written++] = ny;
            out[offset + written++] = nz;
            out[offset + written++] = r;
            out[offset + written++] = g;
            out[offset + written++] = b;
            out[offset + written++] = a;
        }
        return written;
    }

    private static float[][] computeQuadVerts(MergedFace face,
                                                float ox, float oy, float oz) {
        float d = face.positive ? face.depth + 1.0f : face.depth;
        float u0 = face.u0, v0 = face.v0;
        float u1 = face.u1, v1 = face.v1;

        // 依軸映射到世界座標
        return switch (face.axis) {
            case 0 -> new float[][] { // X 面 → YZ 平面
                { ox + d, oy + u0, oz + v0 },
                { ox + d, oy + u1, oz + v0 },
                { ox + d, oy + u1, oz + v1 },
                { ox + d, oy + u0, oz + v1 }
            };
            case 1 -> new float[][] { // Y 面 → XZ 平面
                { ox + u0, oy + d, oz + v0 },
                { ox + u1, oy + d, oz + v0 },
                { ox + u1, oy + d, oz + v1 },
                { ox + u0, oy + d, oz + v1 }
            };
            case 2 -> new float[][] { // Z 面 → XY 平面
                { ox + u0, oy + v0, oz + d },
                { ox + u1, oy + v0, oz + d },
                { ox + u1, oy + v1, oz + d },
                { ox + u0, oy + v1, oz + d }
            };
            default -> new float[4][3];
        };
    }
}
