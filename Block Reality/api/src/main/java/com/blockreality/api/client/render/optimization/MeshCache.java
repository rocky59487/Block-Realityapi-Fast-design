package com.blockreality.api.client.render.optimization;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mesh Cache — Sodium 風格 Section VBO 快取。
 *
 * 核心概念：
 *   - 每個 16³ section 的 Greedy Meshed 結果快取為一個 VBO
 *   - 髒標記機制：方塊變更時標記 section 為髒，下次渲染前重建
 *   - LRU 淘汰：超過 MAX_SECTIONS 時移除最久未使用的
 *
 * Sodium ChunkMeshData 啟發：
 *   - 分離 mesh 編譯（可異步）和 GPU 上傳（必須主執行緒）
 *   - 快取 key = sectionPos（packed long: x|y|z）
 */
@OnlyIn(Dist.CLIENT)
@javax.annotation.concurrent.ThreadSafe // ConcurrentHashMap section mesh cache
public final class MeshCache {

    /** 快取的 Section Mesh 數據 */
    public static final class SectionMesh {
        public final long sectionKey;
        public int vao;
        public int vbo;
        public int vertexCount;
        public long lastUsedFrame;
        public boolean dirty;

        public SectionMesh(long sectionKey) {
            this.sectionKey = sectionKey;
            this.dirty = true;
            this.lastUsedFrame = 0;
        }

        public void deleteGL() {
            if (vao != 0) { GL30.glDeleteVertexArrays(vao); vao = 0; }
            if (vbo != 0) { GL15.glDeleteBuffers(vbo); vbo = 0; }
            vertexCount = 0;
        }
    }

    private final int maxSections;
    private final Map<Long, SectionMesh> cache = new ConcurrentHashMap<>();
    private final Set<Long> dirtySet = ConcurrentHashMap.newKeySet();

    public MeshCache(int maxSections) {
        this.maxSections = maxSections;
    }

    // ─── 查詢 ───────────────────────────────────────────

    /**
     * 取得 section mesh（如果已快取且非髒）。
     */
    public SectionMesh get(int sx, int sy, int sz) {
        long key = packKey(sx, sy, sz);
        SectionMesh mesh = cache.get(key);
        if (mesh != null && !mesh.dirty) {
            return mesh;
        }
        return null; // 需要重建
    }

    /**
     * 取得或建立 section mesh slot。
     */
    public SectionMesh getOrCreate(int sx, int sy, int sz) {
        long key = packKey(sx, sy, sz);
        return cache.computeIfAbsent(key, SectionMesh::new);
    }

    // ─── 髒標記 ─────────────────────────────────────────

    /**
     * 標記 section 為髒。
     */
    public void markDirty(int sx, int sy, int sz) {
        long key = packKey(sx, sy, sz);
        SectionMesh mesh = cache.get(key);
        if (mesh != null) {
            mesh.dirty = true;
        }
        dirtySet.add(key);
    }

    /**
     * 取得所有髒 section 的 key 並清除標記。
     */
    public Set<Long> consumeDirtySet() {
        Set<Long> copy = Set.copyOf(dirtySet);
        dirtySet.clear();
        return copy;
    }

    /**
     * 將重建完成的 mesh 存入快取。
     */
    public void put(int sx, int sy, int sz, SectionMesh mesh) {
        long key = packKey(sx, sy, sz);
        mesh.dirty = false;

        SectionMesh old = cache.put(key, mesh);
        if (old != null && old != mesh) {
            old.deleteGL();
        }

        // LRU 淘汰
        evictIfNeeded();
    }

    // ─── 管理 ───────────────────────────────────────────

    /**
     * 無效化所有快取。
     */
    public void invalidateAll() {
        for (SectionMesh mesh : cache.values()) {
            mesh.deleteGL();
        }
        cache.clear();
        dirtySet.clear();
    }

    /**
     * 移除指定 section 的快取。
     */
    public void remove(int sx, int sy, int sz) {
        long key = packKey(sx, sy, sz);
        SectionMesh mesh = cache.remove(key);
        if (mesh != null) mesh.deleteGL();
        dirtySet.remove(key);
    }

    public int size() { return cache.size(); }

    /**
     * 取得所有快取中的 section mesh（用於渲染遍歷）。
     * 傳回 unmodifiable view — 呼叫者不應修改集合。
     */
    public java.util.Collection<SectionMesh> getAllSections() {
        return java.util.Collections.unmodifiableCollection(cache.values());
    }

    // ─── 內部 ───────────────────────────────────────────

    /** LRU 淘汰 — 移除最舊的 section mesh */
    private void evictIfNeeded() {
        while (cache.size() > maxSections) {
            long oldest = -1;
            long oldestFrame = Long.MAX_VALUE;

            for (Map.Entry<Long, SectionMesh> entry : cache.entrySet()) {
                if (entry.getValue().lastUsedFrame < oldestFrame) {
                    oldestFrame = entry.getValue().lastUsedFrame;
                    oldest = entry.getKey();
                }
            }

            if (oldest != -1) {
                SectionMesh evicted = cache.remove(oldest);
                if (evicted != null) evicted.deleteGL();
            } else {
                break;
            }
        }
    }

    /** 將 section 座標打包為 long key */
    private static long packKey(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF)) | ((long) (y & 0xFFF) << 21) | ((long) (z & 0x1FFFFF) << 33);
    }
}
