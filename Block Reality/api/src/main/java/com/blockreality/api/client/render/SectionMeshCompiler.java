package com.blockreality.api.client.render;

import com.blockreality.api.physics.RBlockState;
import com.blockreality.api.physics.sparse.VoxelSection;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Section 網格編譯器 — 將 VoxelSection 編譯為 GPU 頂點資料。
 *
 * 包含 Greedy Meshing 演算法，合併相鄰同材質面為大矩形。
 *
 * Greedy Meshing 效果：
 *   原始：每方塊 6 面 × 4 頂點 = 24 頂點/block
 *         4096 blocks → 98,304 頂點（最差）
 *   優化：典型建築牆面 100 blocks → 1 矩形 4 頂點
 *         頂點減少 60-95%（取決於幾何複雜度）
 *
 * 頂點格式：
 *   Position: 3 × float (12 bytes)
 *   Color:    4 × ubyte (4 bytes)
 *   Total:    16 bytes/vertex
 *
 * 參考：
 *   - Mikola Lysenko "Meshing in a Minecraft Game" (0fps.net)
 *   - Sodium SectionMeshBuilder
 *
 * @since v3.0 Phase 3
 */
public class SectionMeshCompiler {

    /** 頂點 stride (bytes) */
    private static final int STRIDE = 16;

    /** 內縮量（防 Z-fighting） */
    private static final float INSET = 0.001f;

    /** 6 個面的法線方向 */
    private static final int[][] FACE_NORMALS = {
        {0, -1, 0}, // Bottom (Y-)
        {0,  1, 0}, // Top (Y+)
        {0, 0, -1}, // North (Z-)
        {0, 0,  1}, // South (Z+)
        {-1, 0, 0}, // West (X-)
        { 1, 0, 0}, // East (X+)
    };

    /**
     * 編譯 VoxelSection 為 GPU 頂點資料。
     *
     * @param section  源 Section
     * @param meshType 網格類型（決定色彩映射）
     * @return 堆外 ByteBuffer（呼叫者負責 MemoryUtil.memFree()）
     */
    public static ByteBuffer compile(VoxelSection section, PersistentRenderPipeline.MeshType meshType) {
        if (section.isEmpty()) {
            ByteBuffer empty = MemoryUtil.memAlloc(0);
            return empty;
        }

        // 預估最大頂點數（Greedy Meshing 前的上限）
        int maxVertices = section.getNonAirCount() * 24; // 6 faces × 4 verts
        ByteBuffer buffer = MemoryUtil.memAlloc(maxVertices * STRIDE);

        int wx = section.getWorldX();
        int wy = section.getWorldY();
        int wz = section.getWorldZ();

        // ═══ Greedy Meshing per axis/direction ═══
        // 對每個軸的每個切片做 2D 貪心合併

        for (int face = 0; face < 6; face++) {
            greedyMeshFace(section, face, wx, wy, wz, meshType, buffer);
        }

        buffer.flip();
        return buffer;
    }

    /**
     * 對單一面方向做 Greedy Meshing。
     *
     * 演算法：
     *   1. 對每個切片（perpendicular to face normal）建立 2D mask
     *   2. mask[u][v] = true 表示此位置需要繪製面（鄰居為空氣）
     *   3. 從左上角開始，貪心展開最大矩形
     *   4. 輸出合併後的 quad，清除已處理的 mask
     */
    private static void greedyMeshFace(VoxelSection section, int face,
                                         int wx, int wy, int wz,
                                         PersistentRenderPipeline.MeshType meshType,
                                         ByteBuffer buffer) {
        int[] normal = FACE_NORMALS[face];
        int axis = normal[0] != 0 ? 0 : (normal[1] != 0 ? 1 : 2);
        boolean positive = normal[axis] > 0;

        // 對 perpendicular axis 的每個 slice
        for (int d = 0; d < 16; d++) {
            // 建立 16×16 的 mask
            boolean[][] mask = new boolean[16][16];
            RBlockState[][] states = new RBlockState[16][16];

            for (int u = 0; u < 16; u++) {
                for (int v = 0; v < 16; v++) {
                    int lx, ly, lz;
                    switch (axis) {
                        case 0 -> { lx = d; ly = u; lz = v; }
                        case 1 -> { lx = u; ly = d; lz = v; }
                        default -> { lx = u; ly = v; lz = d; }
                    }

                    RBlockState state = section.getBlock(lx, ly, lz);
                    if (state == null || state == RBlockState.AIR) continue;

                    // 檢查此面是否暴露（鄰居為空氣或越界）
                    int nlx = lx + normal[0];
                    int nly = ly + normal[1];
                    int nlz = lz + normal[2];

                    boolean exposed;
                    if (nlx < 0 || nlx >= 16 || nly < 0 || nly >= 16 || nlz < 0 || nlz >= 16) {
                        exposed = true; // Section 邊界 → 假設暴露
                    } else {
                        RBlockState neighbor = section.getBlock(nlx, nly, nlz);
                        exposed = (neighbor == null || neighbor == RBlockState.AIR);
                    }

                    if (exposed) {
                        mask[u][v] = true;
                        states[u][v] = state;
                    }
                }
            }

            // Greedy merge
            for (int u = 0; u < 16; u++) {
                for (int v = 0; v < 16; v++) {
                    if (!mask[u][v]) continue;

                    RBlockState state = states[u][v];

                    // 向右展開 (v 方向)
                    int width = 1;
                    while (v + width < 16 && mask[u][v + width] && canMerge(states[u][v + width], state, meshType)) {
                        width++;
                    }

                    // 向下展開 (u 方向)
                    int height = 1;
                    outer:
                    while (u + height < 16) {
                        for (int w = 0; w < width; w++) {
                            if (!mask[u + height][v + w] || !canMerge(states[u + height][v + w], state, meshType)) {
                                break outer;
                            }
                        }
                        height++;
                    }

                    // 輸出合併的 quad
                    emitQuad(buffer, wx, wy, wz, axis, d, positive, u, v, width, height, state, meshType);

                    // 清除已處理的 mask
                    for (int du = 0; du < height; du++) {
                        for (int dv = 0; dv < width; dv++) {
                            mask[u + du][v + dv] = false;
                        }
                    }
                }
            }
        }
    }

    /**
     * 判斷兩個方塊是否可以合併為同一個 quad。
     */
    private static boolean canMerge(RBlockState a, RBlockState b,
                                      PersistentRenderPipeline.MeshType meshType) {
        if (a == null || b == null) return false;
        return switch (meshType) {
            case HOLOGRAM -> true; // 全息投影：所有方塊同色，可合併
            case STRESS_HEATMAP -> {
                // 應力相近的方塊可合併（色彩差異不大）
                float stressDiff = Math.abs(a.compressiveStrength() - b.compressiveStrength());
                yield stressDiff < 0.1f;
            }
            case ANCHOR_PATH -> a.isAnchor() == b.isAnchor();
        };
    }

    /**
     * 輸出一個合併的 quad 到 ByteBuffer。
     */
    private static void emitQuad(ByteBuffer buffer,
                                   int wx, int wy, int wz,
                                   int axis, int d, boolean positive,
                                   int u, int v, int width, int height,
                                   RBlockState state,
                                   PersistentRenderPipeline.MeshType meshType) {
        // 計算 quad 的四個角落世界座標
        float x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3;

        float dd = positive ? d + 1 - INSET : d + INSET;

        switch (axis) {
            case 0 -> { // X 軸
                x0 = wx + dd; y0 = wy + u; z0 = wz + v;
                x1 = wx + dd; y1 = wy + u; z1 = wz + v + width;
                x2 = wx + dd; y2 = wy + u + height; z2 = wz + v + width;
                x3 = wx + dd; y3 = wy + u + height; z3 = wz + v;
            }
            case 1 -> { // Y 軸
                x0 = wx + u; y0 = wy + dd; z0 = wz + v;
                x1 = wx + u + height; y1 = wy + dd; z1 = wz + v;
                x2 = wx + u + height; y2 = wy + dd; z2 = wz + v + width;
                x3 = wx + u; y3 = wy + dd; z3 = wz + v + width;
            }
            default -> { // Z 軸
                x0 = wx + u; y0 = wy + v; z0 = wz + dd;
                x1 = wx + u + height; y1 = wy + v; z1 = wz + dd;
                x2 = wx + u + height; y2 = wy + v + width; z2 = wz + dd;
                x3 = wx + u; y3 = wy + v + width; z3 = wz + dd;
            }
        }

        // 計算色彩
        int r, g, b, a;
        switch (meshType) {
            case HOLOGRAM -> { r = 80; g = 160; b = 255; a = 100; }
            case STRESS_HEATMAP -> {
                int[] rgba = stressToColor(state.compressiveStrength());
                r = rgba[0]; g = rgba[1]; b = rgba[2]; a = rgba[3];
            }
            case ANCHOR_PATH -> {
                if (state.isAnchor()) {
                    r = 0; g = 255; b = 100; a = 150;
                } else {
                    r = 255; g = 255; b = 0; a = 80;
                }
            }
            default -> { r = 255; g = 255; b = 255; a = 255; }
        }

        // 確保 winding order 正確（面向法線方向）
        if (positive) {
            putVertex(buffer, x0, y0, z0, r, g, b, a);
            putVertex(buffer, x1, y1, z1, r, g, b, a);
            putVertex(buffer, x2, y2, z2, r, g, b, a);
            putVertex(buffer, x3, y3, z3, r, g, b, a);
        } else {
            putVertex(buffer, x3, y3, z3, r, g, b, a);
            putVertex(buffer, x2, y2, z2, r, g, b, a);
            putVertex(buffer, x1, y1, z1, r, g, b, a);
            putVertex(buffer, x0, y0, z0, r, g, b, a);
        }
    }

    /**
     * 寫入單個頂點。
     */
    private static void putVertex(ByteBuffer buffer, float x, float y, float z,
                                    int r, int g, int b, int a) {
        buffer.putFloat(x);
        buffer.putFloat(y);
        buffer.putFloat(z);
        buffer.put((byte) r);
        buffer.put((byte) g);
        buffer.put((byte) b);
        buffer.put((byte) a);
    }

    /**
     * 應力值 → RGBA 色彩（與 StressHeatmapRenderer 一致）。
     */
    private static int[] stressToColor(float stress) {
        stress = Math.max(0.0f, Math.min(stress, 1.5f));

        int r, g, b, a;
        if (stress <= 0.3f) {
            float t = stress / 0.3f;
            r = lerp(0, 255, t);
            g = lerp(80, 200, t);
            b = lerp(255, 0, t);
            a = lerp(80, 100, t);
        } else if (stress <= 0.7f) {
            float t = (stress - 0.3f) / 0.4f;
            r = 255;
            g = lerp(200, 30, t);
            b = 0;
            a = lerp(100, 130, t);
        } else {
            r = 255; g = 30; b = 0; a = 130;
        }
        return new int[]{r, g, b, a};
    }

    private static int lerp(int a, int b, float t) {
        return (int) (a + (b - a) * t);
    }
}
