package com.blockreality.api.client.render.optimization;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Bobby-style disk-based LOD chunk cache for offline LOD computation and reload.
 *
 * <p>Stores compressed LOD mesh data to disk so that previously computed LOD chunks
 * can be reloaded instantly on subsequent sessions. Each section is stored as an
 * individual file under {@code .minecraft/blockreality/lod-cache/}.</p>
 *
 * <p>File format ({@code .brlod}): magic "BRLOD\0", version (int), vertexCount (int),
 * indexCount (int), timestamp (long), followed by GZIP-compressed mesh data.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BRDiskLODCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(BRDiskLODCache.class);

    private static final byte[] MAGIC = {'B', 'R', 'L', 'O', 'D', 0};
    private static final int FORMAT_VERSION = 1;
    private static final String FILE_EXTENSION = ".brlod";

    private BRDiskLODCache() {}

    // ---- Inner class ----

    /**
     * Represents a single cached LOD entry, either in memory or on disk.
     */
    public static final class CacheEntry {
        final long sectionKey;
        final int lodLevel;
        final byte[] meshData;       // compressed vertex data
        final long timestamp;        // last modification
        final int vertexCount;
        final int indexCount;

        public CacheEntry(long sectionKey, int lodLevel, byte[] meshData,
                          long timestamp, int vertexCount, int indexCount) {
            this.sectionKey = sectionKey;
            this.lodLevel = lodLevel;
            this.meshData = meshData;
            this.timestamp = timestamp;
            this.vertexCount = vertexCount;
            this.indexCount = indexCount;
        }
    }

    // ---- Fields ----

    private static Path cacheDir;
    private static final ConcurrentHashMap<Long, CacheEntry> memoryIndex = new ConcurrentHashMap<>();
    private static int maxDiskSizeMB = 512;
    private static boolean initialized = false;

    private static long cacheHits = 0;
    private static long cacheMisses = 0;
    private static long totalDiskUsage = 0;

    private static ExecutorService writeExecutor;

    // ---- Lifecycle ----

    /**
     * Initialize the cache system. Creates the cache directory if it does not exist
     * and loads the on-disk index into memory.
     *
     * @param gameDir the .minecraft (or instance) directory
     */
    public static void init(Path gameDir) {
        if (initialized) {
            LOGGER.warn("BRDiskLODCache already initialized, cleaning up first");
            cleanup();
        }

        cacheDir = gameDir.resolve("blockreality").resolve("lod-cache");
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            LOGGER.error("Failed to create LOD cache directory: {}", cacheDir, e);
            return;
        }

        memoryIndex.clear();
        cacheHits = 0;
        cacheMisses = 0;
        totalDiskUsage = 0;

        writeExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "BRDiskLODCache-Writer");
            t.setDaemon(true);
            return t;
        });

        // Scan existing cache files to build the in-memory index
        loadIndex();

        initialized = true;
        LOGGER.info("BRDiskLODCache initialized at {}, {} entries, {:.1f} MB on disk",
                cacheDir, memoryIndex.size(), getDiskUsageMB());
    }

    /**
     * Flush pending writes and release resources.
     */
    public static void cleanup() {
        if (writeExecutor != null) {
            writeExecutor.shutdown();
            try {
                if (!writeExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    writeExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                writeExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            writeExecutor = null;
        }

        memoryIndex.clear();
        cacheHits = 0;
        cacheMisses = 0;
        totalDiskUsage = 0;
        initialized = false;
        LOGGER.info("BRDiskLODCache cleaned up");
    }

    /**
     * @return true if the cache has been initialized
     */
    public static boolean isInitialized() {
        return initialized;
    }

    // ---- Key encoding ----

    /**
     * Pack section coordinates into a single long key.
     * Uses upper 32 bits for X and lower 32 bits for Z.
     *
     * @param sectionX section X coordinate
     * @param sectionZ section Z coordinate
     * @return encoded key
     */
    public static long encodeSectionKey(int sectionX, int sectionZ) {
        return ((long) sectionX << 32) | (sectionZ & 0xFFFFFFFFL);
    }

    /**
     * Extract the section X coordinate from an encoded key.
     *
     * @param key encoded section key
     * @return section X coordinate
     */
    public static int decodeSectionX(long key) {
        return (int) (key >> 32);
    }

    /**
     * Extract the section Z coordinate from an encoded key.
     *
     * @param key encoded section key
     * @return section Z coordinate
     */
    public static int decodeSectionZ(long key) {
        return (int) key;
    }

    /**
     * Compute a combined cache key from section coordinates and LOD level.
     */
    private static long combinedKey(int sectionX, int sectionZ, int lodLevel) {
        // Mix lod into the key to allow multiple LOD levels per section
        long base = encodeSectionKey(sectionX, sectionZ);
        return base ^ ((long) lodLevel * 0x9E3779B97F4A7C15L);
    }

    // ---- Cache operations ----

    /**
     * Check whether a cache entry exists for the given section and LOD level.
     *
     * @param sectionX section X coordinate
     * @param sectionZ section Z coordinate
     * @param lodLevel LOD level
     * @return true if cached
     */
    public static boolean has(int sectionX, int sectionZ, int lodLevel) {
        if (!initialized) return false;
        long key = combinedKey(sectionX, sectionZ, lodLevel);
        if (memoryIndex.containsKey(key)) {
            return true;
        }
        // Check disk
        Path file = getCacheFilePath(sectionX, sectionZ, lodLevel);
        return Files.exists(file);
    }

    /**
     * Retrieve a cache entry. Returns from the in-memory index if available,
     * otherwise reads from disk synchronously.
     *
     * @param sectionX section X coordinate
     * @param sectionZ section Z coordinate
     * @param lodLevel LOD level
     * @return the cache entry, or null if not found
     */
    public static CacheEntry get(int sectionX, int sectionZ, int lodLevel) {
        if (!initialized) return null;

        long key = combinedKey(sectionX, sectionZ, lodLevel);
        CacheEntry entry = memoryIndex.get(key);
        if (entry != null) {
            cacheHits++;
            return entry;
        }

        // Try reading from disk
        entry = readFromDisk(sectionX, sectionZ, lodLevel);
        if (entry != null) {
            memoryIndex.put(key, entry);
            cacheHits++;
            return entry;
        }

        cacheMisses++;
        return null;
    }

    /**
     * Store a new cache entry. The write to disk is performed asynchronously
     * on a dedicated writer thread to avoid blocking the render thread.
     *
     * @param sectionX    section X coordinate
     * @param sectionZ    section Z coordinate
     * @param lodLevel    LOD level
     * @param meshData    compressed vertex/index data
     * @param vertexCount number of vertices
     * @param indexCount  number of indices
     */
    public static void put(int sectionX, int sectionZ, int lodLevel,
                           byte[] meshData, int vertexCount, int indexCount) {
        if (!initialized) {
            LOGGER.warn("BRDiskLODCache not initialized, ignoring put");
            return;
        }

        long sectionKey = encodeSectionKey(sectionX, sectionZ);
        long timestamp = System.currentTimeMillis();
        CacheEntry entry = new CacheEntry(sectionKey, lodLevel, meshData,
                timestamp, vertexCount, indexCount);

        long key = combinedKey(sectionX, sectionZ, lodLevel);
        memoryIndex.put(key, entry);

        // Async disk write
        if (writeExecutor != null && !writeExecutor.isShutdown()) {
            writeExecutor.submit(() -> {
                writeToDisk(sectionX, sectionZ, lodLevel, entry);
                enforceQuota();
            });
        }
    }

    /**
     * Invalidate (remove) all LOD levels for a given section.
     *
     * @param sectionX section X coordinate
     * @param sectionZ section Z coordinate
     */
    public static void invalidate(int sectionX, int sectionZ) {
        if (!initialized) return;

        // Remove from memory index - try common LOD levels 0-8
        for (int lod = 0; lod <= 8; lod++) {
            long key = combinedKey(sectionX, sectionZ, lod);
            memoryIndex.remove(key);

            Path file = getCacheFilePath(sectionX, sectionZ, lod);
            try {
                if (Files.deleteIfExists(file)) {
                    LOGGER.debug("Invalidated cache file: {}", file.getFileName());
                }
            } catch (IOException e) {
                LOGGER.warn("Failed to delete cache file: {}", file, e);
            }
        }
    }

    /**
     * Clear the entire cache, both in-memory and on-disk.
     */
    public static void invalidateAll() {
        if (!initialized) return;

        memoryIndex.clear();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(cacheDir, "*" + FILE_EXTENSION)) {
            for (Path file : stream) {
                try {
                    Files.delete(file);
                } catch (IOException e) {
                    LOGGER.warn("Failed to delete cache file: {}", file, e);
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to clear cache directory", e);
        }

        totalDiskUsage = 0;
        cacheHits = 0;
        cacheMisses = 0;
        LOGGER.info("BRDiskLODCache invalidated all entries");
    }

    /**
     * Enforce the disk usage quota by evicting the oldest entries (LRU) until
     * usage falls below {@code maxDiskSizeMB}.
     */
    public static void enforceQuota() {
        if (!initialized || cacheDir == null) return;

        long maxBytes = (long) maxDiskSizeMB * 1024 * 1024;
        recalculateDiskUsage();

        if (totalDiskUsage <= maxBytes) return;

        LOGGER.info("Disk usage {:.1f} MB exceeds quota {} MB, evicting old entries",
                getDiskUsageMB(), maxDiskSizeMB);

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(cacheDir, "*" + FILE_EXTENSION)) {
            List<Path> files = new ArrayList<>();
            for (Path file : stream) {
                files.add(file);
            }

            // Sort by last modified time (oldest first)
            files.sort(Comparator.comparingLong(p -> {
                try {
                    return Files.getLastModifiedTime(p).toMillis();
                } catch (IOException e) {
                    return 0L;
                }
            }));

            for (Path file : files) {
                if (totalDiskUsage <= maxBytes) break;
                try {
                    long fileSize = Files.size(file);
                    Files.delete(file);
                    totalDiskUsage -= fileSize;
                    LOGGER.debug("Evicted cache file: {}", file.getFileName());
                } catch (IOException e) {
                    LOGGER.warn("Failed to evict cache file: {}", file, e);
                }
            }

            // Also remove evicted entries from the memory index
            memoryIndex.entrySet().removeIf(e -> {
                CacheEntry entry = e.getValue();
                int sx = decodeSectionX(entry.sectionKey);
                int sz = decodeSectionZ(entry.sectionKey);
                Path fp = getCacheFilePath(sx, sz, entry.lodLevel);
                return !Files.exists(fp);
            });
        } catch (IOException e) {
            LOGGER.error("Failed to enforce disk quota", e);
        }
    }

    // ---- Statistics ----

    /**
     * @return cache hit rate as a float in [0, 1]
     */
    public static float getCacheHitRate() {
        long total = cacheHits + cacheMisses;
        return total > 0 ? (float) cacheHits / total : 0.0f;
    }

    /**
     * @return current disk usage in megabytes
     */
    public static float getDiskUsageMB() {
        return totalDiskUsage / (1024.0f * 1024.0f);
    }

    /**
     * @return number of entries in the memory index
     */
    public static int getEntryCount() {
        return memoryIndex.size();
    }

    // ---- File I/O ----

    private static Path getCacheFilePath(int sectionX, int sectionZ, int lodLevel) {
        String filename = sectionX + "_" + sectionZ + "_" + lodLevel + FILE_EXTENSION;
        return cacheDir.resolve(filename);
    }

    private static void writeToDisk(int sectionX, int sectionZ, int lodLevel, CacheEntry entry) {
        Path file = getCacheFilePath(sectionX, sectionZ, lodLevel);
        try (DataOutputStream dos = new DataOutputStream(Files.newOutputStream(file))) {
            // Header
            dos.write(MAGIC);
            dos.writeInt(FORMAT_VERSION);
            dos.writeInt(entry.vertexCount);
            dos.writeInt(entry.indexCount);
            dos.writeLong(entry.timestamp);

            // Body: GZIP compressed mesh data
            try (GZIPOutputStream gzos = new GZIPOutputStream(dos)) {
                gzos.write(entry.meshData);
                gzos.finish();
            }

            LOGGER.debug("Wrote cache file: {} ({} bytes mesh data)",
                    file.getFileName(), entry.meshData.length);
        } catch (IOException e) {
            LOGGER.error("Failed to write cache file: {}", file, e);
        }
    }

    private static CacheEntry readFromDisk(int sectionX, int sectionZ, int lodLevel) {
        Path file = getCacheFilePath(sectionX, sectionZ, lodLevel);
        if (!Files.exists(file)) return null;

        try (DataInputStream dis = new DataInputStream(Files.newInputStream(file))) {
            // Verify magic
            byte[] magic = new byte[MAGIC.length];
            dis.readFully(magic);
            for (int i = 0; i < MAGIC.length; i++) {
                if (magic[i] != MAGIC[i]) {
                    LOGGER.error("Invalid magic in cache file: {}", file.getFileName());
                    return null;
                }
            }

            int version = dis.readInt();
            if (version != FORMAT_VERSION) {
                LOGGER.warn("Unsupported cache file version {} in {}", version, file.getFileName());
                return null;
            }

            int vertexCount = dis.readInt();
            int indexCount = dis.readInt();
            long timestamp = dis.readLong();

            // Read GZIP compressed body
            try (GZIPInputStream gzis = new GZIPInputStream(dis)) {
                byte[] meshData = gzis.readAllBytes();
                long sectionKey = encodeSectionKey(sectionX, sectionZ);
                return new CacheEntry(sectionKey, lodLevel, meshData,
                        timestamp, vertexCount, indexCount);
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to read cache file: {}", file, e);
            return null;
        }
    }

    private static void loadIndex() {
        if (cacheDir == null || !Files.isDirectory(cacheDir)) return;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(cacheDir, "*" + FILE_EXTENSION)) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                // Parse filename: sectionX_sectionZ_lodLevel.brlod
                String base = name.substring(0, name.length() - FILE_EXTENSION.length());
                String[] parts = base.split("_");
                if (parts.length != 3) {
                    LOGGER.warn("Skipping malformed cache file: {}", name);
                    continue;
                }

                try {
                    int sectionX = Integer.parseInt(parts[0]);
                    int sectionZ = Integer.parseInt(parts[1]);
                    int lodLevel = Integer.parseInt(parts[2]);

                    CacheEntry entry = readFromDisk(sectionX, sectionZ, lodLevel);
                    if (entry != null) {
                        long key = combinedKey(sectionX, sectionZ, lodLevel);
                        memoryIndex.put(key, entry);
                    }

                    totalDiskUsage += Files.size(file);
                } catch (NumberFormatException e) {
                    LOGGER.warn("Skipping cache file with invalid name: {}", name);
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to scan cache directory", e);
        }
    }

    private static void recalculateDiskUsage() {
        totalDiskUsage = 0;
        if (cacheDir == null || !Files.isDirectory(cacheDir)) return;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(cacheDir, "*" + FILE_EXTENSION)) {
            for (Path file : stream) {
                totalDiskUsage += Files.size(file);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to calculate disk usage", e);
        }
    }
}
