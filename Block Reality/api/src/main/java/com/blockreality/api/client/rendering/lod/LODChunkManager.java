package com.blockreality.api.client.rendering.lod;

import com.blockreality.api.config.BRConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LOD Chunk 生命週期管理器（Phase 1-B）。
 *
 * 負責：
 *   1. 接收 Forge ChunkEvent.Load / Unload 事件
 *   2. 計算每個 chunk 的 LOD 等級（依距離）
 *   3. 排程 LOD mesh 建構（限制每 tick 最大數量）
 *   4. 追蹤 chunk 狀態機（PENDING → BUILDING → READY → DIRTY）
 *   5. 維護已就緒 mesh 的鍵值映射（供 LODTerrainBuffer / BLAS 查詢）
 *
 * 移植來源：Voxy ChunkLoadManager 概念
 *
 * @see VoxyLODMesher
 * @see LODTerrainBuffer
 * @see com.blockreality.api.client.rendering.bridge.ChunkRenderBridge
 */
@OnlyIn(Dist.CLIENT)
public class LODChunkManager {

    private static final Logger LOG = LoggerFactory.getLogger("BR-LODChunkMgr");

    /** chunk 狀態機 */
    public enum LODChunkState {
        PENDING,    // 已登記，等待 mesh 建構
        BUILDING,   // 非同步建構中
        READY,      // mesh 已上傳至 GPU，可渲染
        DIRTY,      // 方塊變更，需要重建
        UNLOADED    // 已卸載，資源應回收
    }

    /** chunk 的完整 LOD 資訊 */
    private static final class ChunkEntry {
        final long key;
        final int chunkX, chunkZ;
        volatile LODChunkState state;
        volatile int lodLevel;
        volatile long gpuBufferOffset = -1; // LODTerrainBuffer 中的偏移
        volatile long blasHandle = 0;       // Phase 2: Vulkan BLAS handle
        volatile float[] cachedVertices;    // Phase 2: 保留 mesh 頂點資料供 BLAS 建構
        Future<?> buildFuture;

        ChunkEntry(long key, int chunkX, int chunkZ, int lodLevel) {
            this.key = key;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.lodLevel = lodLevel;
            this.state = LODChunkState.PENDING;
        }
    }

    // ─── 狀態 ───

    /** chunk key → 狀態資訊 */
    private final ConcurrentHashMap<Long, ChunkEntry> chunks = new ConcurrentHashMap<>();

    /** 待建構佇列（PENDING → BUILDING） */
    private final Deque<Long> buildQueue = new ArrayDeque<>();

    /** 已完成等待主執行緒上傳的 mesh */
    private final ConcurrentHashMap<Long, VoxyLODMesher.LODMeshResult> pendingUploads =
        new ConcurrentHashMap<>();

    /** 非同步建構執行緒池（2 執行緒，避免佔用主執行緒） */
    private final ExecutorService buildExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "BR-LODMesher");
        t.setDaemon(true);
        return t;
    });

    private final VoxyLODMesher mesher = new VoxyLODMesher();
    private final LODTerrainBuffer terrainBuffer;

    // ─── 統計 ───
    private final AtomicInteger readyCount = new AtomicInteger(0);
    private final AtomicInteger buildingCount = new AtomicInteger(0);

    public LODChunkManager(LODTerrainBuffer terrainBuffer) {
        this.terrainBuffer = terrainBuffer;
    }

    // ═══ 事件接收（由 ChunkRenderBridge 呼叫） ═══

    /**
     * chunk 載入事件（來自 Forge ChunkEvent.Load）。
     *
     * 計算 LOD 等級並排入建構佇列（若不在玩家附近）。
     */
    public void onChunkLoaded(int chunkX, int chunkZ) {
        int lodLevel = computeLodLevel(chunkX, chunkZ);
        if (lodLevel == 0) return; // LOD-0 是原始 chunk，由 Minecraft 自己渲染

        long key = chunkKey(chunkX, chunkZ);
        ChunkEntry existing = chunks.get(key);

        if (existing != null) {
            // 已有記錄，檢查是否需要重建（LOD 等級改變）
            if (existing.lodLevel != lodLevel) {
                existing.lodLevel = lodLevel;
                if (existing.state == LODChunkState.READY) {
                    existing.state = LODChunkState.DIRTY;
                    enqueueForBuild(key);
                }
            }
            return;
        }

        ChunkEntry entry = new ChunkEntry(key, chunkX, chunkZ, lodLevel);
        chunks.put(key, entry);
        enqueueForBuild(key);
    }

    /**
     * chunk 卸載事件（來自 Forge ChunkEvent.Unload）。
     */
    public void onChunkUnloaded(int chunkX, int chunkZ) {
        long key = chunkKey(chunkX, chunkZ);
        ChunkEntry entry = chunks.remove(key);
        if (entry == null) return;

        entry.state = LODChunkState.UNLOADED;

        // 取消正在進行的建構
        if (entry.buildFuture != null && !entry.buildFuture.isDone()) {
            entry.buildFuture.cancel(false);
            buildingCount.decrementAndGet();
        }

        // 釋放 GPU 資源
        if (entry.gpuBufferOffset >= 0) {
            terrainBuffer.freeMesh(key);
            readyCount.decrementAndGet();
        }

        pendingUploads.remove(key);
    }

    /**
     * 方塊變更通知（由物理/建設系統呼叫）。
     *
     * 將相關 chunk 標記為 DIRTY，排程重建。
     */
    public void onBlockChanged(int blockX, int blockZ) {
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        long key = chunkKey(chunkX, chunkZ);

        ChunkEntry entry = chunks.get(key);
        if (entry != null && entry.state == LODChunkState.READY) {
            entry.state = LODChunkState.DIRTY;
            enqueueForBuild(key);
        }
    }

    // ═══ tick 處理 ═══

    /**
     * 每 tick 呼叫。
     *
     * 1. 從 buildQueue 取出最多 lodBuildPerTick 個 chunk 啟動非同步建構
     * 2. 將已完成的 mesh 上傳到 GPU（必須在主執行緒）
     */
    public void tick() {
        // 上傳已完成的 mesh（主執行緒）
        int uploadLimit = 8; // 每 tick 最多上傳 8 個，避免卡頓
        int uploaded = 0;
        for (var it = pendingUploads.entrySet().iterator();
             it.hasNext() && uploaded < uploadLimit; ) {
            var entry = it.next();
            long key = entry.getKey();
            var result = entry.getValue();
            it.remove();

            ChunkEntry chunkEntry = chunks.get(key);
            if (chunkEntry == null || chunkEntry.state == LODChunkState.UNLOADED) {
                continue;
            }

            if (!result.isEmpty()) {
                long offset = terrainBuffer.uploadMesh(key, result.vertices());
                chunkEntry.gpuBufferOffset = offset;
                chunkEntry.cachedVertices  = result.vertices(); // Phase 2: BLAS 用
                chunkEntry.state = LODChunkState.READY;
                readyCount.incrementAndGet();
            } else {
                // 全空 chunk，標記為 READY 但不上傳
                chunkEntry.state = LODChunkState.READY;
            }
            buildingCount.decrementAndGet();
            uploaded++;
        }

        // 啟動新的非同步建構任務
        int buildPerTick = BRConfig.INSTANCE.lodBuildPerTick.get();
        int started = 0;
        while (!buildQueue.isEmpty() && started < buildPerTick) {
            Long key = buildQueue.poll();
            if (key == null) break;

            ChunkEntry entry = chunks.get(key);
            if (entry == null || entry.state == LODChunkState.UNLOADED ||
                entry.state == LODChunkState.BUILDING || entry.state == LODChunkState.READY) {
                continue;
            }

            entry.state = LODChunkState.BUILDING;
            buildingCount.incrementAndGet();

            // 捕捉 final 引用供 lambda 使用
            final ChunkEntry finalEntry = entry;
            final long finalKey = key;

            entry.buildFuture = buildExecutor.submit(() -> {
                try {
                    // 非同步建構 mesh
                    float[] verts = mesher.buildLODMesh(
                        finalEntry.chunkX, finalEntry.chunkZ, finalEntry.lodLevel);
                    pendingUploads.put(finalKey,
                        new VoxyLODMesher.LODMeshResult(finalKey, verts));
                } catch (Exception e) {
                    LOG.error("LOD mesh build failed for chunk ({},{}): {}",
                        finalEntry.chunkX, finalEntry.chunkZ, e.getMessage());
                    buildingCount.decrementAndGet();
                    finalEntry.state = LODChunkState.PENDING; // 允許重試
                }
            });

            started++;
        }
    }

    // ═══ 玩家距離 & LOD 計算 ═══

    /**
     * 計算 chunk 應使用的 LOD 等級（依玩家距離）。
     *
     * @return 0 = 原始 chunk, 1–3 = LOD 等級
     */
    private int computeLodLevel(int chunkX, int chunkZ) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 3;

        int playerChunkX = (int) mc.player.getX() >> 4;
        int playerChunkZ = (int) mc.player.getZ() >> 4;

        int dx = chunkX - playerChunkX;
        int dz = chunkZ - playerChunkZ;
        double dist = Math.sqrt(dx * dx + dz * dz);

        int l1 = BRConfig.INSTANCE.lodLevel1Threshold.get();
        int l2 = BRConfig.INSTANCE.lodLevel2Threshold.get();
        int l3 = BRConfig.INSTANCE.lodLevel3Threshold.get();

        if (dist <= l1)  return 0; // 原始 chunk（Minecraft 自行渲染）
        if (dist <= l2)  return 1;
        if (dist <= l3)  return 2;
        return 3;
    }

    private void enqueueForBuild(long key) {
        synchronized (buildQueue) {
            if (!buildQueue.contains(key)) {
                buildQueue.addLast(key);
            }
        }
    }

    // ═══ 查詢介面 ═══

    /**
     * 取得指定 chunk 的 GPU buffer 偏移（-1 = 未就緒）。
     */
    public long getGpuBufferOffset(long chunkKey) {
        ChunkEntry e = chunks.get(chunkKey);
        return (e != null && e.state == LODChunkState.READY) ? e.gpuBufferOffset : -1;
    }

    /**
     * 取得所有 READY 狀態的 chunk key 列表（用於渲染）。
     */
    public long[] getReadyChunkKeys() {
        return chunks.entrySet().stream()
            .filter(e -> e.getValue().state == LODChunkState.READY)
            .mapToLong(Map.Entry::getKey)
            .toArray();
    }

    /**
     * 取得指定 chunk 的 mesh 資料（供 BLAS 建構使用）。
     *
     * 僅在 chunk 為 READY 且有有效頂點資料時返回非 null。
     *
     * @param chunkKey chunk 唯一鍵（ChunkPos.toLong 格式）
     * @return MeshData 或 null
     */
    public MeshData getMeshData(long chunkKey) {
        ChunkEntry e = chunks.get(chunkKey);
        if (e == null || e.state != LODChunkState.READY || e.cachedVertices == null) {
            return null;
        }
        // 頂點數 = floats / FLOATS_PER_VERTEX（10）
        int vertexCount = e.cachedVertices.length / VoxyLODMesher.FLOATS_PER_VERTEX;
        // 索引由 LODTerrainBuffer 的 EBO quad-pattern 隱含決定；
        // 對 BLAS 建構提供顯式索引（每 quad → 2 三角形）
        int quadCount  = vertexCount / VoxyLODMesher.VERTS_PER_FACE;
        int[] indices  = buildQuadIndices(quadCount);
        return new MeshData(e.cachedVertices, vertexCount, indices);
    }

    /** 為 quadCount 個 quad 生成三角形索引（與 LODTerrainBuffer.rebuildEBO 相同模式） */
    private static int[] buildQuadIndices(int quadCount) {
        int[] idx = new int[quadCount * 6];
        for (int q = 0; q < quadCount; q++) {
            int v = q * 4;
            int i = q * 6;
            idx[i]   = v;     idx[i+1] = v+1; idx[i+2] = v+2;
            idx[i+3] = v;     idx[i+4] = v+2; idx[i+5] = v+3;
        }
        return idx;
    }

    /** LOD mesh 資料（頂點 + 索引），用於 Vulkan BLAS 建構 */
    public record MeshData(float[] vertices, int vertexCount, int[] indices) {}

    /**
     * 清除所有 LOD 資料（維度切換 / 斷線時呼叫）。
     */
    public void clear() {
        buildExecutor.shutdownNow();
        chunks.clear();
        buildQueue.clear();
        pendingUploads.clear();
        readyCount.set(0);
        buildingCount.set(0);
        LOG.info("LODChunkManager cleared");
    }

    // ═══ 統計 ═══

    public int getReadyCount()    { return readyCount.get(); }
    public int getBuildingCount() { return buildingCount.get(); }
    public int getTotalTracked()  { return chunks.size(); }

    // ─── 工具 ───

    static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
