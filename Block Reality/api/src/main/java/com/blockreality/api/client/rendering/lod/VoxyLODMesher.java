package com.blockreality.api.client.rendering.lod;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Voxy LOD Mesher — 從 MCRcortex/voxy LodBuilder 移植（Phase 1-A）。
 *
 * 核心演算法：
 *   將遠距 chunk 的方塊資料降頻取樣，生成低多邊形 LOD mesh。
 *   每個 LOD 等級以固定步長（mergeFactor）對方塊進行合併：
 *     LOD-1: 2×2×2 → 1 voxel（2 blocks/voxel）
 *     LOD-2: 4×4×4 → 1 voxel（4 blocks/voxel）
 *     LOD-3: 8×8×8 → 1 voxel（8 blocks/voxel）
 *
 * 頂點格式（10 floats/vertex, 4 vertices/face）：
 *   [x, y, z, nx, ny, nz, r, g, b, a]
 *   與 GreedyMesher.faceToVertices 相容。
 *
 * 移植來源：github.com/MCRcortex/voxy → me.cortex.voxy.client.core.rendering.LodBuilder
 *
 * @see LODChunkManager
 * @see LODTerrainBuffer
 */
@OnlyIn(Dist.CLIENT)
public class VoxyLODMesher {

    private static final Logger LOG = LoggerFactory.getLogger("BR-LODMesher");

    /** LOD 合併倍率（LOD-0=原始, LOD-1=2, LOD-2=4, LOD-3=8） */
    public static final int[] LOD_MERGE_FACTORS = {1, 2, 4, 8};

    /** 每個頂點 float 數量（xyz + normal_xyz + rgba） */
    public static final int FLOATS_PER_VERTEX = 10;

    /** 每個面的頂點數（4 頂點組成一個 quad，使用 index buffer） */
    public static final int VERTS_PER_FACE = 4;

    /** 面法向量（6 方向） axis=0:X, 1:Y, 2:Z, positive/negative */
    private static final float[][] NORMALS = {
        { 1, 0, 0}, {-1, 0, 0},  // +X, -X
        { 0, 1, 0}, { 0,-1, 0},  // +Y, -Y
        { 0, 0, 1}, { 0, 0,-1},  // +Z, -Z
    };

    /** 各方向鄰居偏移 */
    private static final int[][] NEIGHBOR_OFFSETS = {
        {1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}
    };

    /**
     * 將指定 chunk 建構為 LOD mesh。
     *
     * 呼叫方必須在渲染執行緒（或安全取得 LevelChunk 的執行緒）呼叫此方法。
     *
     * @param chunk    Minecraft chunk 資料
     * @param lodLevel LOD 等級 1–3（0 為原始 chunk，不由本類別處理）
     * @param worldX   chunk 世界 X 起始座標（chunkX * 16）
     * @param worldZ   chunk 世界 Z 起始座標（chunkZ * 16）
     * @return 頂點資料（float[]），null 若 chunk 全空
     */
    public float[] buildLODMesh(LevelChunk chunk, int lodLevel, int worldX, int worldZ) {
        if (lodLevel < 1 || lodLevel > 3) {
            throw new IllegalArgumentException("LOD level must be 1–3, got: " + lodLevel);
        }

        int mf = LOD_MERGE_FACTORS[lodLevel];   // 合併倍率
        int gridSize = 16 / mf;                  // 每 section 在 LOD 下的格數

        // Minecraft 1.20.1 section 範圍: Y section 0–23（Y=-64 to Y=319）
        int sectionCount = chunk.getSectionsCount();
        int minSection = chunk.getMinSection();

        List<float[]> faceList = new ArrayList<>();

        for (int si = 0; si < sectionCount; si++) {
            LevelChunkSection section = chunk.getSection(si);
            if (section.hasOnlyAir()) continue;

            int sectionWorldY = (minSection + si) * 16;

            // 建立 LOD voxel grid（每格對應 mf×mf×mf 原始方塊）
            // grid[gx][gy][gz] = materialId（0 = 空氣）
            int[][][] grid = buildLODGrid(section, mf, gridSize);

            // 從 LOD grid 生成面
            collectFaces(grid, gridSize, worldX, sectionWorldY, worldZ, mf, faceList);
        }

        if (faceList.isEmpty()) return null;

        // 合併所有面資料到單一 float[]
        int totalFloats = 0;
        for (float[] face : faceList) totalFloats += face.length;

        float[] result = new float[totalFloats];
        int pos = 0;
        for (float[] face : faceList) {
            System.arraycopy(face, 0, result, pos, face.length);
            pos += face.length;
        }

        return result;
    }

    /**
     * 便利方法：從 Minecraft 客戶端世界載入 chunk 並建構 LOD mesh。
     *
     * @param chunkX   chunk X 座標
     * @param chunkZ   chunk Z 座標
     * @param lodLevel LOD 等級
     * @return 頂點資料，或 null
     */
    public float[] buildLODMesh(int chunkX, int chunkZ, int lodLevel) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;

        LevelChunk chunk = mc.level.getChunk(chunkX, chunkZ);
        return buildLODMesh(chunk, lodLevel, chunkX * 16, chunkZ * 16);
    }

    // ═══ 私有方法 ═══

    /**
     * 將一個 16×16×16 LevelChunkSection 轉換為 LOD voxel grid。
     *
     * @param section  Minecraft chunk section
     * @param mf       merge factor（每 grid 格對應的原始方塊數）
     * @param gridSize LOD grid 邊長（16 / mf）
     * @return int[gridX][gridY][gridZ] = materialId（0 = 空氣）
     */
    private int[][][] buildLODGrid(LevelChunkSection section, int mf, int gridSize) {
        int[][][] grid = new int[gridSize][gridSize][gridSize];

        for (int gx = 0; gx < gridSize; gx++) {
            for (int gy = 0; gy < gridSize; gy++) {
                for (int gz = 0; gz < gridSize; gz++) {
                    int matId = sampleMergeCell(section, gx * mf, gy * mf, gz * mf, mf);
                    grid[gx][gy][gz] = matId;
                }
            }
        }
        return grid;
    }

    /**
     * 取樣一個合併格（mf×mf×mf 原始方塊）的代表材質 ID。
     *
     * 策略：取最上層的非空氣方塊（與 Voxy 的 "topmost visible block" 相同）。
     * 若整個格全為空氣，返回 0。
     */
    private int sampleMergeCell(LevelChunkSection section, int bx, int by, int bz, int mf) {
        // 從上往下掃，找最高的固體方塊
        for (int dy = mf - 1; dy >= 0; dy--) {
            for (int dx = 0; dx < mf; dx++) {
                for (int dz = 0; dz < mf; dz++) {
                    int sx = bx + dx, sy = by + dy, sz = bz + dz;
                    if (sx >= 16 || sy >= 16 || sz >= 16) continue;

                    BlockState state = section.getBlockState(sx, sy, sz);
                    if (!state.isAir()) {
                        return blockStateToMaterialId(state);
                    }
                }
            }
        }
        return 0; // 全空氣
    }

    /**
     * 從 LOD voxel grid 生成面資料。
     *
     * 每個固體格，對 6 個鄰居方向：若鄰居為空氣/超出邊界，生成一個面。
     */
    private void collectFaces(int[][][] grid, int gridSize,
                               int worldX, int worldY, int worldZ, int mf,
                               List<float[]> out) {
        float blockSize = mf; // 每個 LOD 格在世界座標中的大小

        for (int gx = 0; gx < gridSize; gx++) {
            for (int gy = 0; gy < gridSize; gy++) {
                for (int gz = 0; gz < gridSize; gz++) {
                    int matId = grid[gx][gy][gz];
                    if (matId == 0) continue;

                    float[] color = materialColor(matId);

                    // 格的世界座標起始點
                    float wx = worldX + gx * blockSize;
                    float wy = worldY + gy * blockSize;
                    float wz = worldZ + gz * blockSize;

                    for (int dir = 0; dir < 6; dir++) {
                        int nx = gx + NEIGHBOR_OFFSETS[dir][0];
                        int ny = gy + NEIGHBOR_OFFSETS[dir][1];
                        int nz = gz + NEIGHBOR_OFFSETS[dir][2];

                        // 鄰居超出邊界（LOD chunk 接縫）或鄰居是空氣
                        boolean exposed = (nx < 0 || nx >= gridSize ||
                                           ny < 0 || ny >= gridSize ||
                                           nz < 0 || nz >= gridSize) ||
                                          grid[nx][ny][nz] == 0;

                        if (exposed) {
                            float[] face = buildFaceQuad(wx, wy, wz, blockSize,
                                                          dir, color);
                            out.add(face);
                        }
                    }
                }
            }
        }
    }

    /**
     * 生成單一四邊形面的頂點資料（4 頂點 × 10 floats = 40 floats）。
     *
     * 4 頂點順序（逆時針 = OpenGL 前面）:
     *   v0 v1
     *   v3 v2
     *
     * @param wx, wy, wz  格的世界座標起始點
     * @param size        格的大小（world units）
     * @param dir         方向（0=+X, 1=-X, 2=+Y, 3=-Y, 4=+Z, 5=-Z）
     * @param color       RGBA 顏色（4 floats, 0.0–1.0）
     * @return 40 floats 的頂點資料
     */
    private float[] buildFaceQuad(float wx, float wy, float wz, float size,
                                   int dir, float[] color) {
        float[] verts = new float[FLOATS_PER_VERTEX * VERTS_PER_FACE]; // 40 floats

        float[] n = NORMALS[dir];
        float r = color[0], g = color[1], b = color[2], a = color[3];

        // 四個頂點座標（根據面方向）
        float[] v0, v1, v2, v3;

        switch (dir) {
            case 0 -> { // +X 面（x = wx + size）
                float x = wx + size;
                v0 = new float[]{x, wy,        wz       };
                v1 = new float[]{x, wy,        wz + size};
                v2 = new float[]{x, wy + size, wz + size};
                v3 = new float[]{x, wy + size, wz       };
            }
            case 1 -> { // -X 面（x = wx）
                v0 = new float[]{wx, wy,        wz + size};
                v1 = new float[]{wx, wy,        wz       };
                v2 = new float[]{wx, wy + size, wz       };
                v3 = new float[]{wx, wy + size, wz + size};
            }
            case 2 -> { // +Y 面（y = wy + size，頂面）
                float y = wy + size;
                v0 = new float[]{wx,        y, wz + size};
                v1 = new float[]{wx + size, y, wz + size};
                v2 = new float[]{wx + size, y, wz       };
                v3 = new float[]{wx,        y, wz       };
            }
            case 3 -> { // -Y 面（y = wy，底面）
                v0 = new float[]{wx,        wy, wz       };
                v1 = new float[]{wx + size, wy, wz       };
                v2 = new float[]{wx + size, wy, wz + size};
                v3 = new float[]{wx,        wy, wz + size};
            }
            case 4 -> { // +Z 面（z = wz + size）
                float z = wz + size;
                v0 = new float[]{wx + size, wy,        z};
                v1 = new float[]{wx,        wy,        z};
                v2 = new float[]{wx,        wy + size, z};
                v3 = new float[]{wx + size, wy + size, z};
            }
            default -> { // -Z 面（z = wz）
                v0 = new float[]{wx,        wy,        wz};
                v1 = new float[]{wx + size, wy,        wz};
                v2 = new float[]{wx + size, wy + size, wz};
                v3 = new float[]{wx,        wy + size, wz};
            }
        }

        writeVertex(verts,  0, v0, n, r, g, b, a);
        writeVertex(verts, 10, v1, n, r, g, b, a);
        writeVertex(verts, 20, v2, n, r, g, b, a);
        writeVertex(verts, 30, v3, n, r, g, b, a);

        return verts;
    }

    private static void writeVertex(float[] buf, int offset, float[] pos,
                                     float[] normal, float r, float g, float b, float a) {
        buf[offset + 0] = pos[0];
        buf[offset + 1] = pos[1];
        buf[offset + 2] = pos[2];
        buf[offset + 3] = normal[0];
        buf[offset + 4] = normal[1];
        buf[offset + 5] = normal[2];
        buf[offset + 6] = r;
        buf[offset + 7] = g;
        buf[offset + 8] = b;
        buf[offset + 9] = a;
    }

    /**
     * 將 BlockState 轉換為材質 ID。
     *
     * 0 = 空氣，非 0 = 對應材質。
     * 使用方塊 Registry ID 的低 12 位作為材質 ID（支援 4096 種材質）。
     */
    private static int blockStateToMaterialId(BlockState state) {
        if (state.isAir()) return 0;
        // 使用 Block registry ID 作為穩定材質鍵
        int registryId = BuiltInRegistries.BLOCK.getId(state.getBlock());
        return (registryId & 0xFFF) + 1; // 確保非零
    }

    /**
     * 材質 ID → RGBA 顏色（用於 LOD 遠距渲染）。
     *
     * 使用基於 ID 的確定性色彩映射，讓各方塊類型有視覺區分。
     * Phase 3 時將改為採樣真實方塊貼圖。
     */
    private static float[] materialColor(int matId) {
        // 確定性 HSV → RGB 映射（不同 matId 有不同色相）
        float hue = (matId * 137.508f) % 360.0f; // 黃金角度分佈
        float sat = 0.4f + (matId % 3) * 0.1f;
        float val = 0.7f + (matId % 5) * 0.05f;
        return hsvToRgb(hue, sat, val);
    }

    private static float[] hsvToRgb(float h, float s, float v) {
        float c = v * s;
        float x = c * (1 - Math.abs((h / 60.0f) % 2 - 1));
        float m = v - c;
        float r, g, b;
        int sector = (int)(h / 60) % 6;
        switch (sector) {
            case 0 -> { r = c; g = x; b = 0; }
            case 1 -> { r = x; g = c; b = 0; }
            case 2 -> { r = 0; g = c; b = x; }
            case 3 -> { r = 0; g = x; b = c; }
            case 4 -> { r = x; g = 0; b = c; }
            default-> { r = c; g = 0; b = x; }
        }
        return new float[]{r + m, g + m, b + m, 1.0f};
    }

    /**
     * 批量建構 LOD mesh（多 chunk，依距離分配 LOD 等級）。
     *
     * @param requests 建構請求列表
     * @return 結果列表（key = chunkKey，value = 頂點資料）
     */
    public List<LODMeshResult> buildBatch(List<LODMeshRequest> requests) {
        List<LODMeshResult> results = new ArrayList<>();
        for (LODMeshRequest req : requests) {
            float[] verts = buildLODMesh(req.chunkX, req.chunkZ, req.lodLevel);
            results.add(new LODMeshResult(req.chunkKey, verts));
        }
        return results;
    }

    // ═══ 資料記錄 ═══

    public record LODMeshRequest(long chunkKey, int chunkX, int chunkZ, int lodLevel) {}
    public record LODMeshResult(long chunkKey, float[] vertices) {
        public boolean isEmpty() { return vertices == null || vertices.length == 0; }
    }
}
