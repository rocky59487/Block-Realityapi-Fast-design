package com.blockreality.api.client.render.optimization;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BRDiskLODCache} — file I/O logic only (no OpenGL).
 */
class BRDiskLODCacheTest {

    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("br-test-lod-cache");
        BRDiskLODCache.init(tempDir);
    }

    @AfterEach
    void tearDown() throws IOException {
        BRDiskLODCache.cleanup();
        // Recursively delete the temp directory
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walkFileTree(tempDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    // ---- Lifecycle ----

    @Test
    void testInitCreatesCacheDirectory() {
        Path cacheDir = tempDir.resolve("blockreality").resolve("lod-cache");
        assertTrue(Files.isDirectory(cacheDir),
                "init() should create the cache directory at " + cacheDir);
        assertTrue(BRDiskLODCache.isInitialized());
    }

    // ---- Key encoding ----

    @Test
    void testEncodeSectionKeyRoundTrip() {
        int[][] testCases = {
                {0, 0},
                {1, 2},
                {-5, 10},
                {100, -200},
                {Integer.MAX_VALUE, Integer.MIN_VALUE},
                {-1, -1}
        };

        for (int[] tc : testCases) {
            int sx = tc[0];
            int sz = tc[1];
            long key = BRDiskLODCache.encodeSectionKey(sx, sz);
            assertEquals(sx, BRDiskLODCache.decodeSectionX(key),
                    "decodeSectionX should recover X=" + sx);
            assertEquals(sz, BRDiskLODCache.decodeSectionZ(key),
                    "decodeSectionZ should recover Z=" + sz);
        }
    }

    // ---- put + has ----

    @Test
    void testPutThenHasReturnsTrue() throws InterruptedException {
        byte[] meshData = createTestMeshData(256);
        BRDiskLODCache.put(10, 20, 0, meshData, 100, 50);

        // In-memory check should be immediate
        assertTrue(BRDiskLODCache.has(10, 20, 0),
                "has() should return true after put()");
    }

    // ---- put + get ----

    @Test
    void testPutThenGetReturnsCorrectData() {
        byte[] meshData = createTestMeshData(512);
        BRDiskLODCache.put(5, 10, 1, meshData, 200, 300);

        BRDiskLODCache.CacheEntry entry = BRDiskLODCache.get(5, 10, 1);
        assertNotNull(entry, "get() should return the entry after put()");
        assertEquals(200, entry.vertexCount);
        assertEquals(300, entry.indexCount);
        assertArrayEquals(meshData, entry.meshData, "meshData should match what was put");
    }

    // ---- has for non-existent ----

    @Test
    void testHasForNonExistentReturnsFalse() {
        assertFalse(BRDiskLODCache.has(999, 999, 0),
                "has() should return false for a section that was never put");
    }

    // ---- get for non-existent ----

    @Test
    void testGetForNonExistentReturnsNull() {
        assertNull(BRDiskLODCache.get(999, 999, 0),
                "get() should return null for a section that was never put");
    }

    // ---- invalidate ----

    @Test
    void testInvalidateRemovesSection() throws InterruptedException {
        byte[] meshData = createTestMeshData(128);
        BRDiskLODCache.put(3, 4, 0, meshData, 10, 5);
        assertTrue(BRDiskLODCache.has(3, 4, 0));

        // Wait for async disk write before invalidating
        Thread.sleep(500);

        BRDiskLODCache.invalidate(3, 4);

        assertFalse(BRDiskLODCache.has(3, 4, 0),
                "has() should return false after invalidate()");
        assertNull(BRDiskLODCache.get(3, 4, 0),
                "get() should return null after invalidate()");
    }

    // ---- invalidateAll ----

    @Test
    void testInvalidateAllClearsEverything() throws InterruptedException {
        BRDiskLODCache.put(1, 1, 0, createTestMeshData(64), 10, 5);
        BRDiskLODCache.put(2, 2, 0, createTestMeshData(64), 20, 10);
        BRDiskLODCache.put(3, 3, 1, createTestMeshData(64), 30, 15);

        // Wait for async disk writes
        Thread.sleep(500);

        BRDiskLODCache.invalidateAll();

        assertEquals(0, BRDiskLODCache.getEntryCount(),
                "Entry count should be 0 after invalidateAll()");
        assertFalse(BRDiskLODCache.has(1, 1, 0));
        assertFalse(BRDiskLODCache.has(2, 2, 0));
        assertFalse(BRDiskLODCache.has(3, 3, 1));
    }

    // ---- getEntryCount ----

    @Test
    void testGetEntryCountReflectsActualEntries() {
        assertEquals(0, BRDiskLODCache.getEntryCount());

        BRDiskLODCache.put(0, 0, 0, createTestMeshData(32), 5, 3);
        assertEquals(1, BRDiskLODCache.getEntryCount());

        BRDiskLODCache.put(1, 0, 0, createTestMeshData(32), 5, 3);
        assertEquals(2, BRDiskLODCache.getEntryCount());

        BRDiskLODCache.put(0, 1, 0, createTestMeshData(32), 5, 3);
        assertEquals(3, BRDiskLODCache.getEntryCount());
    }

    // ---- getDiskUsageMB ----

    @Test
    void testGetDiskUsageMBIncreasesAfterPut() throws InterruptedException {
        float usageBefore = BRDiskLODCache.getDiskUsageMB();

        // Put a reasonably sized entry
        byte[] meshData = createTestMeshData(4096);
        BRDiskLODCache.put(50, 60, 0, meshData, 500, 250);

        // Wait for async disk write to complete
        Thread.sleep(500);

        // Force recalculation by calling enforceQuota which calls recalculateDiskUsage
        BRDiskLODCache.enforceQuota();

        float usageAfter = BRDiskLODCache.getDiskUsageMB();
        assertTrue(usageAfter > usageBefore,
                "Disk usage should increase after putting data (before=" + usageBefore + ", after=" + usageAfter + ")");
    }

    // ---- Disk persistence round-trip ----

    @Test
    void testDiskPersistenceRoundTrip() throws InterruptedException {
        byte[] meshData = createTestMeshData(1024);
        BRDiskLODCache.put(7, 8, 2, meshData, 150, 75);

        // Wait for async disk write
        Thread.sleep(500);

        // Cleanup and re-init to force reading from disk
        BRDiskLODCache.cleanup();
        BRDiskLODCache.init(tempDir);

        // The entry should be reloaded from disk
        assertTrue(BRDiskLODCache.has(7, 8, 2),
                "Entry should be available after re-init (loaded from disk)");

        BRDiskLODCache.CacheEntry entry = BRDiskLODCache.get(7, 8, 2);
        assertNotNull(entry);
        assertEquals(150, entry.vertexCount);
        assertEquals(75, entry.indexCount);
        assertArrayEquals(meshData, entry.meshData,
                "meshData read from disk should match original");
    }

    // ---- Helpers ----

    private static byte[] createTestMeshData(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (i & 0xFF);
        }
        return data;
    }
}
