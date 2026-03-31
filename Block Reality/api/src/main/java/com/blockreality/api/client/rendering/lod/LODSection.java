package com.blockreality.api.client.rendering.lod;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * LOD 體素區段資料結構。
 *
 * <p>代表一個 16×16×16 的 Minecraft section 在特定 LOD 等級下的 GPU 可用幾何。
 * 同一個 section 可同時持有多個 LOD 等級的快取（0-3），
 * 依相機距離由 {@link BRVoxelLODManager} 選擇最合適的等級渲染。
 */
@OnlyIn(Dist.CLIENT)
public final class LODSection {

    // ─── 座標識別 ──────────────────────────────────────────────
    public final int sectionX;  // Minecraft section X（chunk x * 16 / 16 = chunk x）
    public final int sectionY;  // Minecraft section Y（從 -4 到 19 for 1.20.1）
    public final int sectionZ;

    /** 唯一鍵值：packed long = (sectionX & 0x3FFFFF) | ((long)(sectionY & 0xFF) << 22) | ((long)(sectionZ & 0x3FFFFF) << 30) */
    public final long key;

    // ─── GPU 資源（每 LOD 一份，VAO/VBO 由 LODTerrainBuffer 管理） ──
    public final int[] vaos        = new int[4]; // LOD 0-3
    public final int[] vbos        = new int[4];
    public final int[] ibos        = new int[4];
    public final int[] indexCounts = new int[4];

    // ─── 狀態 ──────────────────────────────────────────────────
    public int    activeLOD    = 0;      // 當前使用的 LOD 等級
    public boolean[] dirty     = {true, true, true, true}; // 哪些 LOD 需要重建
    public boolean   gpuReady  = false;  // 至少一個 LOD 已上傳 GPU
    public long      lastUsedTick = 0L;  // 最後一次被渲染的 tick（用於 eviction）

    // ─── AABB（世界空間，供視錐剔除） ─────────────────────────
    public float minX, minY, minZ, maxX, maxY, maxZ;

    // ─── BLAS handle（供 VkAccelStructBuilder 使用） ──────────
    public long blasHandle = 0L;   // VkAccelerationStructureKHR
    public long blasAlloc  = 0L;   // VmaAllocation
    public boolean blasDirty = true;

    public LODSection(int sx, int sy, int sz) {
        this.sectionX = sx;
        this.sectionY = sy;
        this.sectionZ = sz;
        this.key = packKey(sx, sy, sz);

        float wx = sx * 16.0f, wy = sy * 16.0f, wz = sz * 16.0f;
        this.minX = wx; this.minY = wy; this.minZ = wz;
        this.maxX = wx + 16; this.maxY = wy + 16; this.maxZ = wz + 16;
    }

    public static long packKey(int sx, int sy, int sz) {
        return ((long)(sx & 0x3FFFFF))
             | ((long)(sy & 0xFF)     << 22)
             | ((long)(sz & 0x3FFFFF) << 30);
    }

    /** 此 LOD 等級是否已有 GPU 資源就緒 */
    public boolean isLODReady(int lod) {
        return vaos[lod] != 0 && indexCounts[lod] > 0;
    }

    /** 找出最佳可用 LOD（最接近 target 的已就緒 LOD） */
    public int bestAvailableLOD(int targetLOD) {
        for (int l = targetLOD; l < 4; l++) if (isLODReady(l)) return l;
        for (int l = targetLOD - 1; l >= 0; l--) if (isLODReady(l)) return l;
        return -1; // 無可用資源
    }
}
