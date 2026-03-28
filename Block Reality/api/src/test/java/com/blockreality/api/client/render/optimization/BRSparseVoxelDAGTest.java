package com.blockreality.api.client.render.optimization;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BRSparseVoxelDAG} — pure Java logic only (no OpenGL).
 */
class BRSparseVoxelDAGTest {

    @BeforeEach
    void setUp() {
        BRSparseVoxelDAG.cleanup();
    }

    // ---- Lifecycle ----

    @Test
    void testInitAndIsInitialized() {
        assertFalse(BRSparseVoxelDAG.isInitialized());
        BRSparseVoxelDAG.init(3);
        assertTrue(BRSparseVoxelDAG.isInitialized());
        assertEquals(3, BRSparseVoxelDAG.getMaxDepth());
    }

    @Test
    void testCleanupResetsState() {
        BRSparseVoxelDAG.init(4);
        int[] grid = createUniformGrid(8, 8, 8, 5);
        BRSparseVoxelDAG.buildFromVoxelGrid(grid, 8, 8, 8);

        assertTrue(BRSparseVoxelDAG.isInitialized());
        assertTrue(BRSparseVoxelDAG.getTotalNodes() > 0);

        BRSparseVoxelDAG.cleanup();

        assertFalse(BRSparseVoxelDAG.isInitialized());
        assertEquals(0, BRSparseVoxelDAG.getTotalNodes());
        assertEquals(1.0f, BRSparseVoxelDAG.getCompressionRatio());
    }

    // ---- Build: uniform grid ----

    @Test
    void testBuildUniformGridProducesFewNodes() {
        BRSparseVoxelDAG.init(3);
        int[] grid = createUniformGrid(8, 8, 8, 42);
        int root = BRSparseVoxelDAG.buildFromVoxelGrid(grid, 8, 8, 8);

        assertTrue(root >= 0, "Root index should be non-negative");
        // A completely uniform grid should collapse heavily — very few unique nodes
        int nodeCount = BRSparseVoxelDAG.getTotalNodes();
        assertTrue(nodeCount <= 4,
                "Uniform 8x8x8 grid should produce very few nodes due to dedup, got " + nodeCount);
    }

    // ---- Build: varied grid ----

    @Test
    void testBuildVariedGridProducesMoreNodes() {
        BRSparseVoxelDAG.init(3);
        int[] uniform = createUniformGrid(8, 8, 8, 1);
        BRSparseVoxelDAG.buildFromVoxelGrid(uniform, 8, 8, 8);
        int uniformNodes = BRSparseVoxelDAG.getTotalNodes();

        BRSparseVoxelDAG.cleanup();
        BRSparseVoxelDAG.init(3);

        int[] varied = createVariedGrid(8, 8, 8);
        BRSparseVoxelDAG.buildFromVoxelGrid(varied, 8, 8, 8);
        int variedNodes = BRSparseVoxelDAG.getTotalNodes();

        assertTrue(variedNodes > uniformNodes,
                "Varied grid should produce more nodes (" + variedNodes + ") than uniform (" + uniformNodes + ")");
    }

    // ---- Query ----

    @Test
    void testQueryReturnsCorrectMaterial() {
        BRSparseVoxelDAG.init(3);
        int size = 8;
        int[] grid = new int[size * size * size];
        // Place a known material at (2, 3, 4)
        int targetMaterial = 99;
        grid[2 + 3 * size + 4 * size * size] = targetMaterial;
        BRSparseVoxelDAG.buildFromVoxelGrid(grid, size, size, size);

        assertEquals(targetMaterial, BRSparseVoxelDAG.query(2, 3, 4),
                "Query should return material placed at (2,3,4)");
        assertEquals(0, BRSparseVoxelDAG.query(0, 0, 0),
                "Query should return 0 for empty voxels");
    }

    @Test
    void testQueryUniformGrid() {
        BRSparseVoxelDAG.init(3);
        int[] grid = createUniformGrid(8, 8, 8, 7);
        BRSparseVoxelDAG.buildFromVoxelGrid(grid, 8, 8, 8);

        // Every position should return material 7
        assertEquals(7, BRSparseVoxelDAG.query(0, 0, 0));
        assertEquals(7, BRSparseVoxelDAG.query(3, 5, 2));
        assertEquals(7, BRSparseVoxelDAG.query(7, 7, 7));
    }

    // ---- QueryLOD ----

    @Test
    void testQueryLODAtDifferentLevels() {
        BRSparseVoxelDAG.init(3);
        int[] grid = createUniformGrid(8, 8, 8, 15);
        BRSparseVoxelDAG.buildFromVoxelGrid(grid, 8, 8, 8);

        // At LOD 0 (full resolution) should still return the material
        int resultLOD0 = BRSparseVoxelDAG.queryLOD(2, 2, 2, 0);
        assertEquals(15, resultLOD0, "LOD 0 should return exact material");

        // At higher LOD levels, should still return material for uniform grid
        int resultLOD1 = BRSparseVoxelDAG.queryLOD(2, 2, 2, 1);
        assertEquals(15, resultLOD1, "LOD 1 on uniform grid should still return material");

        int resultLOD2 = BRSparseVoxelDAG.queryLOD(2, 2, 2, 2);
        assertEquals(15, resultLOD2, "LOD 2 on uniform grid should still return material");
    }

    @Test
    void testQueryLODNotInitializedReturnsZero() {
        // Not initialized — should return 0
        assertEquals(0, BRSparseVoxelDAG.queryLOD(0, 0, 0, 0));
    }

    // ---- Compression ratio ----

    @Test
    void testCompressionRatioGreaterThanOneForUniform() {
        BRSparseVoxelDAG.init(3);
        int[] grid = createUniformGrid(8, 8, 8, 1);
        BRSparseVoxelDAG.buildFromVoxelGrid(grid, 8, 8, 8);

        float ratio = BRSparseVoxelDAG.getCompressionRatio();
        assertTrue(ratio > 1.0f,
                "Compression ratio for uniform data should be > 1.0, got " + ratio);
    }

    // ---- Serialize / Deserialize round-trip ----

    @Test
    void testSerializeDeserializeRoundTrip() {
        BRSparseVoxelDAG.init(3);
        int size = 8;
        int[] grid = new int[size * size * size];
        grid[0 + 0 * size + 0 * size * size] = 10;
        grid[3 + 2 * size + 1 * size * size] = 20;
        grid[7 + 7 * size + 7 * size * size] = 30;
        BRSparseVoxelDAG.buildFromVoxelGrid(grid, size, size, size);

        // Capture query results before serialization
        int q1 = BRSparseVoxelDAG.query(0, 0, 0);
        int q2 = BRSparseVoxelDAG.query(3, 2, 1);
        int q3 = BRSparseVoxelDAG.query(7, 7, 7);
        int q4 = BRSparseVoxelDAG.query(4, 4, 4);

        byte[] data = BRSparseVoxelDAG.serialize();
        assertNotNull(data, "Serialized data should not be null");
        assertTrue(data.length > 0, "Serialized data should not be empty");

        // Cleanup and deserialize
        BRSparseVoxelDAG.cleanup();
        assertFalse(BRSparseVoxelDAG.isInitialized());

        BRSparseVoxelDAG.deserialize(data);
        assertTrue(BRSparseVoxelDAG.isInitialized(), "Should be initialized after deserialize");

        // Queries should return same results
        assertEquals(q1, BRSparseVoxelDAG.query(0, 0, 0));
        assertEquals(q2, BRSparseVoxelDAG.query(3, 2, 1));
        assertEquals(q3, BRSparseVoxelDAG.query(7, 7, 7));
        assertEquals(q4, BRSparseVoxelDAG.query(4, 4, 4));
    }

    @Test
    void testSerializeReturnsNullWhenNotInitialized() {
        assertNull(BRSparseVoxelDAG.serialize());
    }

    // ---- Empty grid ----

    @Test
    void testEmptyGridAllZeros() {
        BRSparseVoxelDAG.init(3);
        int[] grid = new int[8 * 8 * 8]; // all zeros
        int root = BRSparseVoxelDAG.buildFromVoxelGrid(grid, 8, 8, 8);

        assertTrue(root >= 0, "Root index should be valid even for empty grid");
        assertEquals(0, BRSparseVoxelDAG.query(0, 0, 0));
        assertEquals(0, BRSparseVoxelDAG.query(4, 4, 4));
        assertEquals(0, BRSparseVoxelDAG.query(7, 7, 7));
    }

    // ---- Hash ----

    @Test
    void testComputeNodeHashDifferentInputs() {
        long hash1 = BRSparseVoxelDAG.computeNodeHash(0xFF, new int[]{1, 2, 3}, -1);
        long hash2 = BRSparseVoxelDAG.computeNodeHash(0xFF, new int[]{1, 2, 4}, -1);
        long hash3 = BRSparseVoxelDAG.computeNodeHash(0xFE, new int[]{1, 2, 3}, -1);
        long hash4 = BRSparseVoxelDAG.computeNodeHash(0, new int[0], 5);
        long hash5 = BRSparseVoxelDAG.computeNodeHash(0, new int[0], 6);

        assertNotEquals(hash1, hash2, "Different child indices should produce different hashes");
        assertNotEquals(hash1, hash3, "Different child masks should produce different hashes");
        assertNotEquals(hash4, hash5, "Different material IDs should produce different hashes");
    }

    @Test
    void testComputeNodeHashConsistent() {
        long hash1 = BRSparseVoxelDAG.computeNodeHash(0x0F, new int[]{10, 20}, 3);
        long hash2 = BRSparseVoxelDAG.computeNodeHash(0x0F, new int[]{10, 20}, 3);
        assertEquals(hash1, hash2, "Same inputs should produce the same hash");
    }

    // ---- Helpers ----

    private static int[] createUniformGrid(int sizeX, int sizeY, int sizeZ, int material) {
        int[] grid = new int[sizeX * sizeY * sizeZ];
        for (int i = 0; i < grid.length; i++) {
            grid[i] = material;
        }
        return grid;
    }

    private static int[] createVariedGrid(int sizeX, int sizeY, int sizeZ) {
        int[] grid = new int[sizeX * sizeY * sizeZ];
        for (int i = 0; i < grid.length; i++) {
            // Each voxel gets a unique-ish material based on position
            grid[i] = (i % 64) + 1;
        }
        return grid;
    }
}
