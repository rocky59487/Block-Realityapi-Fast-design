package com.blockreality.api.client.rendering.vulkan;

import com.blockreality.api.client.render.rt.BRClusterBVH;
import com.blockreality.api.client.render.rt.BRVulkanBVH;
import com.blockreality.api.client.rendering.lod.BRVoxelLODManager;
import com.blockreality.api.client.rendering.lod.LODSection;
import com.blockreality.api.client.rendering.lod.VoxyLODMesher;
import com.blockreality.api.physics.sparse.SparseVoxelOctree;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * VkAccelStructBuilder — Ada/Blackwell LOD-aware BLAS + 物理骯髒追蹤 + Cluster AS。
 *
 * <h3>LOD-aware BLAS 策略</h3>
 * <pre>
 * LOD 0-1 → 每 quad 一個緊湊 AABB（從 GreedyMesher 面資料擷取）
 *            精確陰影/反射，prefer FAST_TRACE
 * LOD 2-3 → 單一 section AABB（粗略，快速建構）
 *            prefer FAST_BUILD
 * </pre>
 *
 * <h3>SparseVoxelOctree 物理整合</h3>
 * 每幀呼叫 {@link #onPhysicsDirtySections(SparseVoxelOctree)} 掃描 SVO dirty 區段，
 * 並透過 {@link BRVulkanBVH#markDirty} 觸發對應 BLAS 增量更新。
 *
 * <h3>Blackwell Cluster AS</h3>
 * 當 {@link BRAdaRTConfig#hasClusterAS()} 為 true 時，
 * 將相鄰 4×4 section 打包為單一 cluster AABB BLAS，
 * 將 TLAS instance 數量縮減至原本的 1/16。
 *
 * @author Block Reality Team
 */
@OnlyIn(Dist.CLIENT)
public final class VkAccelStructBuilder implements BRVoxelLODManager.BLASUpdater {

    private static final Logger LOG = LoggerFactory.getLogger("BR-VkAccelStruct");

    /** Blackwell cluster 大小（N×N sections 為一個 cluster） */
    private static final int CLUSTER_SIZE = 4;

    /** LOD 0-1 使用精細 AABB（每面一個），LOD 2-3 使用單一 section AABB */
    private static final int LOD_FINE_THRESHOLD = 2; // LOD < 2 → fine

    // ─── 緊湊 AABB 快取（sectionKey → float[] aabbData）───────────────────
    // 由 storeLODMeshForBLAS() 在網格建立後填入，由 rebuildBLAS() 消費
    private final ConcurrentHashMap<Long, float[]> fineAabbCache = new ConcurrentHashMap<>();

    // ─── Blackwell cluster 追蹤（clusterKey → 最近更新幀）──────────────────
    // clusterKey = (cx & 0xFFFFFFL << 32) | (cz & 0xFFFFFFL) where cx = sectionX / CLUSTER_SIZE
    private final ConcurrentHashMap<Long, Long> clusterLastBuildFrame = new ConcurrentHashMap<>();

    // ─── OMM / 透明 section 追蹤 ────────────────────────────────────────────
    // sectionKey → true 表示此 section 含透明方塊（玻璃/水/葉片）。
    // 含透明方塊的 section 使用標準 BLAS（any-hit 觸發 transparent.rahit.glsl）；
    // 不含透明方塊的 section 在 OMM 可用時改用 buildBLASOpaque()（跳過 any-hit，提升性能）。
    // 由材料系統呼叫 markSectionTransparent() 更新（方塊放置/移除時）。
    private final ConcurrentHashMap<Long, Boolean> transparentSectionCache = new ConcurrentHashMap<>();

    private final VkContext context;
    private boolean initialized = false;

    public VkAccelStructBuilder(VkContext context) {
        this.context = context;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  生命週期
    // ═══════════════════════════════════════════════════════════════════════

    public void init() {
        if (!context.isAvailable()) {
            LOG.debug("VkContext not available, VkAccelStructBuilder disabled");
            return;
        }
        try {
            BRVulkanBVH.init();
            initialized = BRVulkanBVH.isInitialized();
            if (initialized) {
                BRAdaRTConfig.detect();
                LOG.info("VkAccelStructBuilder initialized (BVH={}, tier={})",
                    BRVulkanBVH.getBLASCount(),
                    BRAdaRTConfig.isBlackwellOrNewer() ? "Blackwell"
                    : BRAdaRTConfig.isAdaOrNewer()     ? "Ada"
                    : "Legacy");
            } else {
                LOG.warn("BRVulkanBVH initialization failed");
            }
        } catch (Exception e) {
            LOG.error("VkAccelStructBuilder init error", e);
            initialized = false;
        }
    }

    public void cleanup() {
        fineAabbCache.clear();
        clusterLastBuildFrame.clear();
        transparentSectionCache.clear();
        // ★ RT-1-2: 清除 ClusterBVH 追蹤（世界卸載時）
        BRClusterBVH.getInstance().clear();
        if (initialized) {
            BRVulkanBVH.cleanup();
            initialized = false;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  LOD 網格快取（由 LODChunkManager 在網格建立後呼叫）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 當 LOD 0-1 網格建立後，儲存每 quad AABB 供 BLAS 使用。
     * 必須在 GPU 上傳前（仍持有 CPU positions 時）呼叫。
     *
     * @param sectionKey  {@link LODSection#packKey} 所得的 key
     * @param lodLevel    網格 LOD 等級（僅 0-1 有效）
     * @param meshData    VoxyLODMesher 輸出
     */
    public void storeLODMeshForBLAS(long sectionKey, int lodLevel,
                                     VoxyLODMesher.LODMeshData meshData) {
        if (!initialized) return;
        if (lodLevel >= LOD_FINE_THRESHOLD) return; // LOD 2-3 不需精細 AABB
        if (meshData == null || meshData.vertexCount() == 0) return;

        float[] aabbs = extractFaceAABBs(meshData);
        if (aabbs != null && aabbs.length > 0) {
            fineAabbCache.put(sectionKey, aabbs);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  BLASUpdater 實作
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * LOD-aware BLAS 重建。
     *
     * <p>LOD 0-1：使用從 GreedyMesher faces 擷取的緊湊 per-quad AABB。
     * <br>LOD 2-3：使用粗略的 section AABB（快速建構）。
     * <br>Blackwell：額外觸發 cluster AABB 更新。
     */
    @Override
    public void rebuildBLAS(LODSection section) {
        if (!initialized) return;

        int lod = section.activeLOD;

        if (BRAdaRTConfig.isBlackwellOrNewer()) {
            // Blackwell：嘗試 cluster-level BLAS 打包
            rebuildClusterBLAS(section, lod);
        } else {
            // Ada / Legacy：section-level BLAS
            rebuildSectionBLAS(section, lod);
        }
    }

    // ─── Ada section-level BLAS ───────────────────────────────────────────

    private void rebuildSectionBLAS(LODSection section, int lod) {
        float[] aabbData;

        if (lod < LOD_FINE_THRESHOLD) {
            // LOD 0-1：精細 per-face AABB（若快取存在）
            float[] cached = fineAabbCache.get(section.key);
            if (cached != null && cached.length >= 6) {
                aabbData = cached;
            } else {
                // 快取缺失（熱路徑不重建）→ 退化為 section AABB
                aabbData = buildSectionAABB(section);
                LOG.trace("BLAS LOD{} ({},{},{}): fine-AABB cache miss, using section AABB",
                    lod, section.sectionX, section.sectionY, section.sectionZ);
            }
        } else {
            // LOD 2-3：粗略 section AABB
            aabbData = buildSectionAABB(section);
        }

        int primitiveCount = aabbData.length / 6;
        try {
            // ── OMM / opaque 路由 ──────────────────────────────────────────────
            // 若 GPU 有 OMM 擴充 (VK_EXT_opacity_micromap) 且此 section 不含透明方塊，
            // 使用 VK_GEOMETRY_OPAQUE_BIT_KHR 旗標跳過 any-hit shader 呼叫。
            // 含透明方塊（玻璃/水/葉片）的 section 使用標準 BLAS。
            //
            // 注：真正的 OMM micro-triangle 整合需要 triangle geometry（Phase 3 LOD 0）；
            //     此處是 AABB geometry 的等效最佳化。
            boolean hasTransparent = transparentSectionCache.containsKey(section.key);
            boolean useOpaqueFlag  = BRAdaRTConfig.hasOMM() && !hasTransparent;

            if (useOpaqueFlag) {
                BRVulkanBVH.buildBLASOpaque(section.sectionX, section.sectionZ, aabbData, primitiveCount);
                LOG.debug("BLAS (opaque/OMM) LOD{} ({},{},{}): {} primitives, any-hit skipped",
                    lod, section.sectionX, section.sectionY, section.sectionZ, primitiveCount);
            } else {
                BRVulkanBVH.buildBLAS(section.sectionX, section.sectionZ, aabbData, primitiveCount);
                LOG.debug("BLAS rebuilt LOD{} ({},{},{}): {} primitives{}",
                    lod, section.sectionX, section.sectionY, section.sectionZ, primitiveCount,
                    hasTransparent ? " (transparent, any-hit active)" : "");
            }

            section.blasHandle = BRVulkanBVH.encodeSectionKey(section.sectionX, section.sectionZ);
            section.blasDirty  = false;
        } catch (Exception e) {
            LOG.debug("BLAS rebuild error ({},{},{}): {}",
                section.sectionX, section.sectionY, section.sectionZ, e.getMessage());
        }
    }

    // ─── Blackwell cluster-level BLAS ────────────────────────────────────

    /**
     * 將相鄰 CLUSTER_SIZE×CLUSTER_SIZE 個 section 打包為一個 cluster BLAS。
     *
     * <p>TLAS instance 數量：從 N×N 個 section instances → 1 個 cluster instance，
     * 縮減因子 = CLUSTER_SIZE² = 16（RTX 50xx Cluster AS 硬體加速）。
     *
     * <p>實作策略：
     * <ol>
     *   <li>計算 cluster 索引 (cx, cz) = (sectionX/4, sectionZ/4)</li>
     *   <li>合併 cluster 內所有已知 section 的 AABB 為 cluster-AABB</li>
     *   <li>使用 cluster-AABB 建立單一 BLAS instance</li>
     * </ol>
     *
     * 注意：VK_NV_cluster_acceleration_structure 的完整硬體路徑需 BRVulkanBVH 擴充支援；
     * 此處使用邏輯 cluster 合併實現 TLAS 縮減，語意等效。
     */
    private void rebuildClusterBLAS(LODSection section, int lod) {
        int cx = Math.floorDiv(section.sectionX, CLUSTER_SIZE);
        int cz = Math.floorDiv(section.sectionZ, CLUSTER_SIZE);
        long clusterKey = ((long)(cx & 0xFFFFFFF) << 32) | (cz & 0xFFFFFFFFL);

        // 計算 cluster 範圍的 combined AABB
        float minX = cx * CLUSTER_SIZE * 16.0f;
        float minZ = cz * CLUSTER_SIZE * 16.0f;
        float maxX = minX + CLUSTER_SIZE * 16.0f;
        float maxZ = minZ + CLUSTER_SIZE * 16.0f;
        float minY = section.minY; // 使用觸發 section 的 Y 範圍（cluster 跨 Y 不合併）
        float maxY = section.maxY;

        // 若 LOD 0-1，嘗試使用精細 AABB 在 cluster 空間內
        float[] aabbData;
        if (lod < LOD_FINE_THRESHOLD) {
            float[] cached = fineAabbCache.get(section.key);
            aabbData = (cached != null && cached.length >= 6) ? cached : buildSectionAABB(section);
        } else {
            // cluster-level 粗略 AABB（覆蓋整個 4×4 section 區塊）
            aabbData = new float[] { minX, minY, minZ, maxX, maxY, maxZ };
        }

        int primitiveCount = aabbData.length / 6;
        try {
            // 使用 cluster 代表座標（cluster 左下角 section）作為 BVH key
            int repX = cx * CLUSTER_SIZE;
            int repZ = cz * CLUSTER_SIZE;

            // Cluster 是否含透明 section：掃描 cluster 內任意 section 是否透明
            boolean clusterHasTransparent = false;
            for (int dx = 0; dx < CLUSTER_SIZE && !clusterHasTransparent; dx++) {
                for (int dz = 0; dz < CLUSTER_SIZE && !clusterHasTransparent; dz++) {
                    long sk = BRVulkanBVH.encodeSectionKey(cx * CLUSTER_SIZE + dx, cz * CLUSTER_SIZE + dz);
                    clusterHasTransparent = transparentSectionCache.containsKey(sk);
                }
            }
            boolean useOpaqueFlag = BRAdaRTConfig.hasOMM() && !clusterHasTransparent;

            if (useOpaqueFlag) {
                BRVulkanBVH.buildBLASOpaque(repX, repZ, aabbData, primitiveCount);
                LOG.debug("Blackwell cluster BLAS (opaque) ({},{}) LOD{}: {} prim, any-hit skipped",
                    cx, cz, lod, primitiveCount);
            } else {
                BRVulkanBVH.buildBLAS(repX, repZ, aabbData, primitiveCount);
                LOG.debug("Blackwell cluster BLAS ({},{}) LOD{}: {} primitives, {} sec/cluster{}",
                    cx, cz, lod, primitiveCount, CLUSTER_SIZE * CLUSTER_SIZE,
                    clusterHasTransparent ? " (has transparent)" : "");
            }

            section.blasHandle = BRVulkanBVH.encodeSectionKey(repX, repZ);
            section.blasDirty  = false;
            clusterLastBuildFrame.put(clusterKey, System.nanoTime());

            // ★ RT-1-2: 通知 BRClusterBVH 狀態管理器此 section 已更新
            // BRClusterBVH 追蹤各 cluster 的合併 AABB、dirty 狀態及 section 計數，
            // 供 onFrameStart() 的增量重建路徑使用
            BRClusterBVH.getInstance().onSectionUpdated(
                section.sectionX, section.sectionZ,
                section.minY, section.maxY,
                clusterHasTransparent
            );

        } catch (Exception e) {
            LOG.debug("Cluster BLAS error ({},{},{}): {}",
                section.sectionX, section.sectionY, section.sectionZ, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  SparseVoxelOctree 物理骯髒追蹤
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 掃描 {@link SparseVoxelOctree} 的 dirty section，將對應 BLAS 標記為 dirty。
     *
     * <p>每個物理 tick 由 BRVoxelLODManager（或 ForgeRenderEventBridge）呼叫。
     * 確保物理模擬（崩塌、施工）造成的幾何變更即時反映到 RT pipeline。
     *
     * @param svo 物理側的稀疏體素八叉樹（含 dirty 追蹤）
     */
    public void onPhysicsDirtySections(SparseVoxelOctree svo) {
        if (!initialized || svo == null) return;

        // 遍歷 SVO dirty section：sectionKey → VoxelSection
        // SVO section 座標（sx, sy, sz）對應 Minecraft section（16-block 單位）
        svo.forEachDirtySection((key, voxelSection) -> {
            int sx = SparseVoxelOctree.sectionKeyX(key);
            int sz = SparseVoxelOctree.sectionKeyZ(key);

            // 通知 BRVulkanBVH 此 section BLAS 需要重建
            BRVulkanBVH.markDirty(sx, sz);

            // 若 Blackwell cluster mode：整個 cluster 標記 dirty
            if (BRAdaRTConfig.isBlackwellOrNewer()) {
                int cx = Math.floorDiv(sx, CLUSTER_SIZE);
                int cz = Math.floorDiv(sz, CLUSTER_SIZE);
                // 標記 cluster 內所有 section 的 BLAS dirty
                for (int dx = 0; dx < CLUSTER_SIZE; dx++) {
                    for (int dz = 0; dz < CLUSTER_SIZE; dz++) {
                        int nx = cx * CLUSTER_SIZE + dx;
                        int nz = cz * CLUSTER_SIZE + dz;
                        if (nx != sx || nz != sz) {
                            BRVulkanBVH.markDirty(nx, nz);
                        }
                    }
                }
            }

            // 從精細 AABB 快取移除過期資料（下次 rebuild 時重新填入）
            // SVO section key 格式與 LODSection.packKey 不同，需要轉換
            // LODSection.packKey 使用 (sx & 0x3FFFFF) | (sy << 22) | (sz << 30)
            // SVO.sectionKey 使用不同編碼，取 sy 對齊
            int sy = SparseVoxelOctree.sectionKeyY(key);
            long lodKey = LODSection.packKey(sx, sy, sz);
            fineAabbCache.remove(lodKey);
        });
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  每幀 API
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 增量更新 TLAS（有 dirty BLAS 時處理）。
     *
     * <p>★ RT-1-2: 在 TLAS 更新前，先驅動 {@link BRClusterBVH#onFrameStart(long)}
     * 處理 Blackwell cluster BLAS 的增量重建（每幀最多 {@link BRVulkanBVH#MAX_BLAS_REBUILDS_PER_FRAME} 個）。
     * 非 Blackwell GPU 上 {@code onFrameStart} 立即返回，無額外開銷。
     *
     * @param frame 目前幀計數（由渲染管線傳入，確保與 BVH 幀計數一致）
     */
    public void updateTLAS(long frame) {
        if (!initialized) return;
        try {
            // ★ RT-1-2: 驅動 ClusterBVH 增量重建（Blackwell only；非 Blackwell 立即返回）
            BRClusterBVH.getInstance().onFrameStart(frame);
            BRVulkanBVH.updateTLAS();
        } catch (Exception e) {
            LOG.debug("TLAS update error: {}", e.getMessage());
        }
    }

    /**
     * 增量更新 TLAS（無 frame 參數的向下相容多載）。
     * 使用 BRVulkanBVH 內部幀計數（{@code frameCount}）驅動 ClusterBVH。
     *
     * @deprecated 建議使用 {@link #updateTLAS(long)} 傳入明確幀計數
     */
    @Deprecated
    public void updateTLAS() {
        updateTLAS(BRVulkanBVH.getFrameCount());
    }

    /** 觸發完整 TLAS 重建（大量 dirty 時使用）。 */
    public void rebuildTLAS() {
        if (!initialized) return;
        try {
            BRVulkanBVH.rebuildTLAS();
        } catch (Exception e) {
            LOG.debug("TLAS rebuild error: {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  統計
    // ═══════════════════════════════════════════════════════════════════════

    public boolean isInitialized()        { return initialized; }
    public int    getBLASCount()          { return initialized ? BRVulkanBVH.getBLASCount() : 0; }
    public long   getTotalBVHMemory()     { return initialized ? BRVulkanBVH.getTotalBVHMemory() : 0L; }
    public long   getTLASHandle()         { return initialized ? BRVulkanBVH.getTLAS() : 0L; }
    public int    getFineAabbCacheSize()  { return fineAabbCache.size(); }
    public int    getClusterCount()       { return clusterLastBuildFrame.size(); }
    public int    getTransparentSectionCount() { return transparentSectionCache.size(); }

    // ═══════════════════════════════════════════════════════════════════════
    //  OMM 透明 section 標記（材料系統呼叫）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 標記或取消標記 section 是否含有透明方塊（玻璃/水/葉片）。
     *
     * <p>材料系統在玻璃/水/葉片被放置或移除時呼叫此方法。
     * 標記影響 {@link #rebuildSectionBLAS(LODSection, int)} 的 BLAS 建立策略：
     * <ul>
     *   <li>{@code hasTransparent = false}：當 {@link BRAdaRTConfig#hasOMM()} 為 true 時，
     *       使用 {@code VK_GEOMETRY_OPAQUE_BIT_KHR}（跳過 any-hit，提升性能）</li>
     *   <li>{@code hasTransparent = true}：使用標準 BLAS（any-hit shader 處理 alpha-test）</li>
     * </ul>
     *
     * <p>呼叫此方法後，需在下一幀觸發 {@link BRVulkanBVH#markDirty} 使 BLAS 重建生效。
     *
     * @param sectionKey   section 唯一鍵（由 {@link BRVulkanBVH#encodeSectionKey} 建立）
     * @param hasTransparent {@code true} 表示此 section 含透明方塊
     */
    public void markSectionTransparent(long sectionKey, boolean hasTransparent) {
        if (hasTransparent) {
            transparentSectionCache.put(sectionKey, Boolean.TRUE);
        } else {
            transparentSectionCache.remove(sectionKey);
        }
    }

    /**
     * 便利方法：從座標建立 sectionKey 並標記透明狀態。
     *
     * @param sectionX    section X 座標
     * @param sectionZ    section Z 座標
     * @param hasTransparent {@code true} 表示含透明方塊
     */
    public void markSectionTransparent(int sectionX, int sectionZ, boolean hasTransparent) {
        markSectionTransparent(BRVulkanBVH.encodeSectionKey(sectionX, sectionZ), hasTransparent);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  內部輔助
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 從 LODMeshData 的 positions 陣列提取每個 quad 的緊湊 AABB。
     *
     * <p>LODMeshData 格式：positions[] = [x,y,z] per vertex，每 4 頂點為一個 quad。
     * 每個 quad → 1 個 AABB（[minX,minY,minZ,maxX,maxY,maxZ]）。
     *
     * @param mesh  VoxyLODMesher 輸出
     * @return AABB float[] 陣列（6 floats per AABB）
     */
    private static float[] extractFaceAABBs(VoxyLODMesher.LODMeshData mesh) {
        int vertexCount = mesh.vertexCount();
        if (vertexCount < 4) return null;

        int quadCount = vertexCount / 4; // 每 quad 4 頂點
        float[] positions = mesh.positions(); // [x,y,z, x,y,z, ...] per vertex

        // 預分配最大尺寸，最後 trim（避免 ArrayList<Float> autoboxing 開銷）
        float[] result = new float[quadCount * 6];
        int outIdx = 0;

        final float EPSILON = 0.1f;

        for (int q = 0; q < quadCount; q++) {
            int vBase = q * 4 * 3; // 4 verts × 3 floats per vert
            if (vBase + 4 * 3 > positions.length) break;

            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

            for (int v = 0; v < 4; v++) {
                float px = positions[vBase + v * 3    ];
                float py = positions[vBase + v * 3 + 1];
                float pz = positions[vBase + v * 3 + 2];
                if (px < minX) minX = px; if (px > maxX) maxX = px;
                if (py < minY) minY = py; if (py > maxY) maxY = py;
                if (pz < minZ) minZ = pz; if (pz > maxZ) maxZ = pz;
            }

            // 為貼面 quad 增加厚度（避免退化 AABB，VK 規範要求 AABB minX < maxX）
            if (maxX - minX < EPSILON) { minX -= EPSILON; maxX += EPSILON; }
            if (maxY - minY < EPSILON) { minY -= EPSILON; maxY += EPSILON; }
            if (maxZ - minZ < EPSILON) { minZ -= EPSILON; maxZ += EPSILON; }

            result[outIdx++] = minX; result[outIdx++] = minY; result[outIdx++] = minZ;
            result[outIdx++] = maxX; result[outIdx++] = maxY; result[outIdx++] = maxZ;
        }

        return outIdx == result.length ? result : Arrays.copyOf(result, outIdx);
    }

    /**
     * 整個 section 的粗略 AABB（LOD 2-3 使用）。
     * 格式：[minX, minY, minZ, maxX, maxY, maxZ]（6 floats，1 primitive）
     */
    private static float[] buildSectionAABB(LODSection sec) {
        return new float[] { sec.minX, sec.minY, sec.minZ, sec.maxX, sec.maxY, sec.maxZ };
    }
}
