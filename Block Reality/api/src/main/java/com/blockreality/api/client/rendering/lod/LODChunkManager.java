package com.blockreality.api.client.rendering.lod;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LOD Chunk Manager — 追蹤已載入的 section，調度 LOD 網格重建，管理 eviction。
 *
 * <p>職責：
 * <ul>
 *   <li>維護 section key → LODSection 的快取表</li>
 *   <li>接收 chunk dirty 通知，觸發非同步 LOD 重建</li>
 *   <li>依 lastUsedTick 驅逐閒置 section（LRU eviction）</li>
 *   <li>管理背景 worker 執行緒（最多 2 條）</li>
 * </ul>
 *
 * @author Block Reality Team
 */
@OnlyIn(Dist.CLIENT)
public final class LODChunkManager {

    private static final Logger LOG = LoggerFactory.getLogger("BR-LODChunkMgr");

    /** 最大快取 section 數（超過後觸發 eviction） */
    private static final int MAX_SECTIONS = 8192;
    /** 閒置超過此 tick 數即驅逐（20 TPS × 60s = 1200） */
    private static final long EVICT_TICKS = 1200L;
    /** 背景重建執行緒數 */
    private static final int WORKER_THREADS = 2;

    // ── 快取 ────────────────────────────────────────────────────────
    /** key → LODSection，ConcurrentHashMap 供多執行緒安全讀 */
    private final ConcurrentHashMap<Long, LODSection> sections = new ConcurrentHashMap<>();

    // ── 重建佇列 ─────────────────────────────────────────────────────
    /** 待重建的 (sectionKey, lodLevel) 工作項目 */
    private final BlockingQueue<RebuildTask> rebuildQueue = new LinkedBlockingQueue<>(4096);
    /** 是否已在重建佇列中（避免重複排入） */
    private final Set<Long> queued = ConcurrentHashMap.newKeySet();

    // ── 工作執行緒 ────────────────────────────────────────────────────
    private final ExecutorService workers;
    private volatile boolean running = false;

    // ── 統計 ─────────────────────────────────────────────────────────
    private final AtomicInteger rebuildsCompleted = new AtomicInteger(0);
    private final AtomicInteger evictions = new AtomicInteger(0);

    // ── 資料提供者 ────────────────────────────────────────────────────
    /** 負責提供 section 方塊資料的回調（由 ChunkRenderBridge 設定） */
    private volatile BlockDataProvider dataProvider;

    public interface BlockDataProvider {
        /**
         * 取得指定 section 的方塊 ID 陣列（16×16×16，y*256+z*16+x 索引）。
         * @return short[] 或 null（section 未載入）
         */
        short[] getBlockData(int sectionX, int sectionY, int sectionZ);
    }

    // ─────────────────────────────────────────────────────────────────
    //  生命週期
    // ─────────────────────────────────────────────────────────────────

    public LODChunkManager() {
        this.workers = Executors.newFixedThreadPool(WORKER_THREADS, r -> {
            Thread t = new Thread(r, "BR-LODWorker");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
    }

    /** 啟動重建 worker。必須在 GL context 可用後呼叫。 */
    public void start() {
        if (running) return;
        running = true;
        for (int i = 0; i < WORKER_THREADS; i++) {
            workers.submit(this::workerLoop);
        }
        LOG.info("LODChunkManager started ({} workers)", WORKER_THREADS);
    }

    /** 停止 worker，釋放所有 GPU 資源。 */
    public void shutdown() {
        running = false;
        workers.shutdownNow();
        try {
            workers.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        sections.clear();
        rebuildQueue.clear();
        queued.clear();
        LOG.info("LODChunkManager shutdown — {} rebuilds, {} evictions",
            rebuildsCompleted.get(), evictions.get());
    }

    // ─────────────────────────────────────────────────────────────────
    //  公開 API
    // ─────────────────────────────────────────────────────────────────

    public void setDataProvider(BlockDataProvider provider) {
        this.dataProvider = provider;
    }

    /**
     * 取得或建立指定 section 的 LODSection 物件。
     * 若尚未存在，建立並排入 LOD 0 重建佇列。
     */
    public LODSection getOrCreate(int sx, int sy, int sz) {
        long key = LODSection.packKey(sx, sy, sz);
        return sections.computeIfAbsent(key, k -> {
            LODSection sec = new LODSection(sx, sy, sz);
            enqueueRebuild(sec, 0); // 預設從 LOD 0 開始
            return sec;
        });
    }

    /**
     * 標記 section 為 dirty，觸發所有 LOD 等級重建。
     * @param sx section X 座標
     * @param sy section Y 座標
     * @param sz section Z 座標
     */
    public void markDirty(int sx, int sy, int sz) {
        long key = LODSection.packKey(sx, sy, sz);
        LODSection sec = sections.get(key);
        if (sec == null) return; // 未載入，忽略
        Arrays.fill(sec.dirty, true);
        enqueueRebuild(sec, 0); // 從 LOD 0 重建（最高精度優先）
    }

    /**
     * 更新 section 的最後使用 tick，防止 eviction。
     */
    public void touch(long key, long currentTick) {
        LODSection sec = sections.get(key);
        if (sec != null) sec.lastUsedTick = currentTick;
    }

    /**
     * 執行 LRU eviction。應在主執行緒的每幀 tick 末尾呼叫。
     * @param currentTick 目前 tick 數
     */
    public void evictStale(long currentTick) {
        if (sections.size() <= MAX_SECTIONS / 2) return; // 不需要清理

        sections.entrySet().removeIf(entry -> {
            LODSection sec = entry.getValue();
            if (currentTick - sec.lastUsedTick > EVICT_TICKS) {
                evictions.incrementAndGet();
                return true;
            }
            return false;
        });

        // 強制驅逐（超過上限時按 lastUsedTick 排序）
        if (sections.size() > MAX_SECTIONS) {
            sections.entrySet().stream()
                .sorted(Comparator.comparingLong(e -> e.getValue().lastUsedTick))
                .limit(sections.size() - MAX_SECTIONS)
                .forEach(e -> {
                    sections.remove(e.getKey());
                    evictions.incrementAndGet();
                });
        }
    }

    /** @return 目前快取的 section 數量 */
    public int getSectionCount() { return sections.size(); }

    /** @return 快取 map（唯讀迭代，渲染用） */
    public Collection<LODSection> getAllSections() { return sections.values(); }

    // ─────────────────────────────────────────────────────────────────
    //  內部實作
    // ─────────────────────────────────────────────────────────────────

    private void enqueueRebuild(LODSection sec, int lodLevel) {
        long key = sec.key;
        if (!queued.add(key)) return; // 已在佇列中
        rebuildQueue.offer(new RebuildTask(sec, lodLevel));
    }

    /** Worker 主迴圈 */
    private void workerLoop() {
        while (running) {
            try {
                RebuildTask task = rebuildQueue.poll(100, TimeUnit.MILLISECONDS);
                if (task == null) continue;

                queued.remove(task.section.key);
                processRebuild(task);
                rebuildsCompleted.incrementAndGet();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOG.error("LOD rebuild worker error", e);
            }
        }
    }

    private void processRebuild(RebuildTask task) {
        LODSection sec = task.section;
        BlockDataProvider provider = this.dataProvider;
        if (provider == null) return;

        short[] blocks = provider.getBlockData(sec.sectionX, sec.sectionY, sec.sectionZ);
        if (blocks == null) return;

        // 逐 LOD 等級重建（僅重建標記 dirty 的等級）
        for (int lod = 0; lod < 4; lod++) {
            if (!sec.dirty[lod]) continue;

            VoxyLODMesher.LODMeshData mesh = VoxyLODMesher.buildMesh(blocks, lod);
            if (!mesh.isEmpty()) {
                // 將網格資料暫存到 LODSection（GPU 上傳由 LODTerrainBuffer 在主執行緒完成）
                storePendingMesh(sec, lod, mesh);
                sec.dirty[lod] = false;
                sec.blasDirty = true; // BLAS 也需更新
            }
        }
    }

    /**
     * 將網格資料與 LODSection 關聯，等待主執行緒 GPU 上傳。
     * 使用靜態快取避免 GC 壓力。
     */
    private void storePendingMesh(LODSection sec, int lod, VoxyLODMesher.LODMeshData mesh) {
        // 依賴 LODTerrainBuffer 的待上傳佇列機制
        LODTerrainBuffer.queueUpload(sec, lod, mesh);
    }

    // ─────────────────────────────────────────────────────────────────
    //  內部資料結構
    // ─────────────────────────────────────────────────────────────────

    private record RebuildTask(LODSection section, int preferredLOD) {}
}
