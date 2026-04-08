package com.blockreality.api.client.rendering.lod;

import com.blockreality.api.client.render.shader.BRShaderEngine;
import com.blockreality.api.client.render.shader.BRShaderProgram;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BR Voxel LOD Manager — 4 級 3D LOD 系統頂層協調器。
 *
 * <p>整合 {@link LODChunkManager}、{@link LODTerrainBuffer}、{@link LODRenderDispatcher}，
 * 以及 Vulkan RT BLAS 更新（透過 {@code BRVulkanBVH}）。
 *
 * <h3>呼叫順序（每幀）</h3>
 * <pre>
 * BRVoxelLODManager.beginFrame(proj, view, camX, camY, camZ, tick)
 * BRVoxelLODManager.renderOpaque()
 * BRVoxelLODManager.renderDepthPass()   ← CSM shadow
 * BRVoxelLODManager.updateBLAS()        ← Vulkan RT（TIER_3 only）
 * </pre>
 *
 * <h3>LOD 等級</h3>
 * <pre>
 * LOD 0 (  0- 8 chunks) 16³ 全精度
 * LOD 1 (  8-32 chunks)  8³
 * LOD 2 ( 32-128 chunks)  4³
 * LOD 3 (128-512 chunks)  2³ + SVDAG 軟追蹤
 * </pre>
 *
 * @author Block Reality Team
 */
@OnlyIn(Dist.CLIENT)
public final class BRVoxelLODManager {

    private static final Logger LOG = LoggerFactory.getLogger("BR-VoxelLOD");

    // ── 子系統 ────────────────────────────────────────────────────────
    private final LODChunkManager    chunkManager;
    private final LODRenderDispatcher dispatcher;

    // ── Vulkan RT BLAS 更新（可選） ───────────────────────────────────
    private BLASUpdater blasUpdater;

    // ── 相機快取 ──────────────────────────────────────────────────────
    private Matrix4f lastProjMatrix = new Matrix4f();
    private Matrix4f lastViewMatrix = new Matrix4f();
    private double camX, camY, camZ;
    private long currentTick = 0L;

    // ── SVDAG LOD3 ────────────────────────────────────────────────────
    private boolean svdagLOD3Enabled = false;
    private LODBlendManager blendManager;

    // ── 單例 ──────────────────────────────────────────────────────────
    private static BRVoxelLODManager INSTANCE;

    public static BRVoxelLODManager getInstance() {
        if (INSTANCE == null) INSTANCE = new BRVoxelLODManager();
        return INSTANCE;
    }

    // ─────────────────────────────────────────────────────────────────
    //  建構
    // ─────────────────────────────────────────────────────────────────

    private BRVoxelLODManager() {
        this.chunkManager = new LODChunkManager();
        this.dispatcher   = new LODRenderDispatcher(chunkManager);
        this.blendManager = LODBlendManager.getInstance();
    }

    // ─────────────────────────────────────────────────────────────────
    //  生命週期
    // ─────────────────────────────────────────────────────────────────

    /**
     * 初始化：啟動 worker 執行緒。
     * 必須在 GL context 建立後、第一幀渲染前呼叫。
     *
     * @param dataProvider 方塊資料提供者（由 ChunkRenderBridge 實作）
     */
    public void init(LODChunkManager.BlockDataProvider dataProvider) {
        chunkManager.setDataProvider(dataProvider);
        chunkManager.start();
        LOG.info("BRVoxelLODManager initialized");
    }

    /**
     * 設定 BLAS 更新器（Vulkan RT TIER_3 時使用）。
     */
    public void setBLASUpdater(BLASUpdater updater) {
        this.blasUpdater = updater;
    }

    /** 關閉，釋放所有資源。 */
    public void shutdown() {
        chunkManager.shutdown();
        if (svdagLOD3Enabled) {
            SVDAGLOD3Tracer.shutdown();
        }
        LOG.info("BRVoxelLODManager shutdown");
        INSTANCE = null;
    }

    /**
     * 初始化 SVDAG LOD3 遠場追蹤（相機距離 > 128 chunks 時使用）。
     * 應在主 init() 之後、第一幀前呼叫。
     *
     * @param width  螢幕寬度（像素）
     * @param height 螢幕高度（像素）
     */
    public void initSvdagLOD3(int width, int height) {
        SVDAGLOD3Tracer.init(width, height);
        if (SVDAGLOD3Tracer.isInitialized()) {
            svdagLOD3Enabled = true;
            LOG.info("SVDAG LOD3 tracer initialized");
        } else {
            svdagLOD3Enabled = false;
            LOG.warn("SVDAG LOD3 tracer initialization failed, LOD3 disabled");
        }
    }

    /**
     * 更新 SVDAG GPU 緩衝區（當 DAG 重建時呼叫）。
     */
    public void updateSvdagBuffer() {
        if (svdagLOD3Enabled) {
            SVDAGLOD3Tracer.updateDAGBuffer();
        }
    }

    /**
     * 每幀更新 SVDAG camera UBO。
     * 應在 beginFrame() 之後呼叫。
     */
    public void updateSvdagCamera() {
        if (!svdagLOD3Enabled) return;

        Matrix4f invViewProj = new Matrix4f(lastProjMatrix)
                .invert()
                .mul(new Matrix4f(lastViewMatrix).invert());

        float[] camPos = new float[]{(float) camX, (float) camY, (float) camZ};
        SVDAGLOD3Tracer.updateCameraUBO(invViewProj, camPos);
    }

    /**
     * 若相機在 LOD3 距離，調度 SVDAG LOD3 計算著色器。
     * 應在 RT 或其他渲染管線之前呼叫。
     *
     * @param commandBuf Vulkan 命令緩衝區（已在錄製狀態）
     */
    public void dispatchLOD3(long commandBuf) {
        if (!svdagLOD3Enabled) return;

        // 判斷相機距離（假設有多個 section，計算到最近 section 的距離）
        double nearestDist = Double.MAX_VALUE;
        for (LODSection sec : chunkManager.getAllSections()) {
            double centerX = (sec.minX + sec.maxX) * 0.5;
            double centerY = (sec.minY + sec.maxY) * 0.5;
            double centerZ = (sec.minZ + sec.maxZ) * 0.5;
            double dx = centerX - camX, dy = centerY - camY, dz = centerZ - camZ;
            double distChunks = Math.sqrt(dx*dx + dy*dy + dz*dz) / 16.0;
            if (distChunks < nearestDist) nearestDist = distChunks;
        }

        // LOD3 啟動距離：128 chunks
        if (nearestDist >= 128.0) {
            SVDAGLOD3Tracer.dispatchLOD3(commandBuf);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  每幀 API
    // ─────────────────────────────────────────────────────────────────

    /**
     * 每幀開始時呼叫：更新相機、視錐，處理 GPU 上傳佇列。
     */
    public void beginFrame(Matrix4f projMatrix, Matrix4f viewMatrix,
                           double cx, double cy, double cz, long tick) {
        this.lastProjMatrix.set(projMatrix);
        this.lastViewMatrix.set(viewMatrix);
        this.camX = cx;
        this.camY = cy;
        this.camZ = cz;
        this.currentTick = tick;

        dispatcher.beginFrame(projMatrix, viewMatrix, cx, cy, cz, tick);
    }

    /**
     * 渲染不透明 LOD 地形（opaque pass）。
     * 必須在 shader bind 後呼叫。
     */
    public void renderOpaque() {
        BRShaderProgram lodShader = BRShaderEngine.getLODShader();
        if (lodShader == null) {
            LOG.warn("LOD shader not available, skipping render");
            return;
        }

        lodShader.bind();
        lodShader.setUniformMatrix4f("u_Projection", lastProjMatrix);
        lodShader.setUniformMatrix4f("u_View", lastViewMatrix);

        dispatcher.renderOpaque(0); // VAO 由 dispatcher 內部管理

        lodShader.unbind();
    }

    /**
     * 深度 pass（CSM shadow map 使用）。
     * 外部已 bind shadow shader，此方法只 draw。
     */
    public void renderDepthPass() {
        dispatcher.renderDepthOnly();
    }

    /**
     * 更新 Vulkan RT BLAS（TIER_3 only）。
     * 在 renderOpaque() 後呼叫，處理標記 blasDirty 的 section。
     */
    public void updateBLAS() {
        if (blasUpdater == null) return;

        for (LODSection sec : chunkManager.getAllSections()) {
            if (sec.blasDirty && sec.gpuReady) {
                blasUpdater.rebuildBLAS(sec);
                sec.blasDirty = false;
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Chunk 事件 API
    // ─────────────────────────────────────────────────────────────────

    /**
     * 當 chunk section 載入時呼叫（ForgeRenderEventBridge → ChunkRenderBridge）。
     */
    public void onSectionLoad(int sectionX, int sectionY, int sectionZ) {
        chunkManager.getOrCreate(sectionX, sectionY, sectionZ);
    }

    /**
     * 當方塊更新時呼叫，標記對應 section 為 dirty。
     */
    public void onBlockChange(int worldX, int worldY, int worldZ) {
        int sx = Math.floorDiv(worldX, 16);
        int sy = Math.floorDiv(worldY, 16);
        int sz = Math.floorDiv(worldZ, 16);
        chunkManager.markDirty(sx, sy, sz);
    }

    /**
     * 當 chunk 卸載時呼叫（目前僅 log，eviction 由 LRU 自動處理）。
     */
    public void onChunkUnload(int chunkX, int chunkZ) {
        // 16 sections per chunk（Y -4 to 19 for 1.20.1 = 24 sections）
        for (int sy = -4; sy < 20; sy++) {
            chunkManager.markDirty(chunkX, sy, chunkZ);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  統計
    // ─────────────────────────────────────────────────────────────────

    public int getVisibleSections()  { return dispatcher.getLastVisibleCount(); }
    public int getDrawCalls()        { return dispatcher.getLastDrawCalls(); }
    public int getTotalSections()    { return chunkManager.getSectionCount(); }
    public long getVRAMUsage()       { return LODTerrainBuffer.getTotalVRAM(); }

    // ─────────────────────────────────────────────────────────────────
    //  SPI 接口（Vulkan RT）
    // ─────────────────────────────────────────────────────────────────

    /**
     * BLAS 更新器接口，由 VkAccelStructBuilder 實作。
     */
    public interface BLASUpdater {
        /**
         * 為指定 LODSection 重建 BLAS（Vulkan acceleration structure）。
         * 在主執行緒或 Vulkan submit 執行緒呼叫，實作方自行決定是否非同步。
         */
        void rebuildBLAS(LODSection section);
    }
}
