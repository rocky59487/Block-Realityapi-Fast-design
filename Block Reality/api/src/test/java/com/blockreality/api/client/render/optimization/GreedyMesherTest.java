package com.blockreality.api.client.render.optimization;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GreedyMesher 正確性測試 — C-9
 *
 * 覆蓋：
 *   - 空體素陣列 → 0 面
 *   - 單一方塊 → 6 面（每個暴露面一個）
 *   - 同材質牆面合併 → 每面合為 1 個 MergedFace
 *   - 不同材質不合併
 *   - 被遮蔽面不產生
 *   - 面面積計算正確
 *   - faceToVertices 輸出正確頂點數
 */
@DisplayName("GreedyMesher — Face Merging Correctness Tests")
class GreedyMesherTest {

    private static final int SIZE = 16;
    private static final int MAT_CONCRETE = 1;
    private static final int MAT_STEEL = 2;

    private GreedyMesher mesher = new GreedyMesher(256); // max area 256 = full 16×16

    private static int idx(int x, int y, int z) {
        return x + y * 16 + z * 256;
    }

    // ═══ 1. Empty Voxels ═══

    @Test
    @DisplayName("Empty voxel array produces zero faces")
    void testEmptyVoxelsProduceNoFaces() {
        int[] voxels = new int[4096]; // all zeros
        List<GreedyMesher.MergedFace> faces = mesher.mesh(voxels);
        assertTrue(faces.isEmpty(), "Empty voxels should produce no faces");
    }

    // ═══ 2. Single Block → 6 Exposed Faces ═══

    @Test
    @DisplayName("Single block at center produces 6 faces")
    void testSingleBlockSixFaces() {
        int[] voxels = new int[4096];
        voxels[idx(8, 8, 8)] = MAT_CONCRETE;

        List<GreedyMesher.MergedFace> faces = mesher.mesh(voxels);
        assertEquals(6, faces.size(), "Single block should have 6 exposed faces");

        // Each face should have area = 1
        for (GreedyMesher.MergedFace face : faces) {
            assertEquals(1, face.area(), "Single block face area should be 1");
            assertEquals(MAT_CONCRETE, face.materialId);
        }
    }

    // ═══ 3. Full Layer Merging ═══

    @Test
    @DisplayName("16×16 single-layer floor merges to minimal faces")
    void testFullLayerMerging() {
        int[] voxels = new int[4096];
        // Fill y=0 layer with concrete
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                voxels[idx(x, 0, z)] = MAT_CONCRETE;
            }
        }

        List<GreedyMesher.MergedFace> faces = mesher.mesh(voxels);

        // Should have far fewer than 256×6 = 1536 unmerged faces
        // Optimal: 2 big faces (top/bottom) + 4 edge strips
        assertTrue(faces.size() < 100,
            "16×16 layer should merge significantly (got " + faces.size() + " faces)");

        // Find the Y-axis faces (top and bottom of the layer)
        long yFaces = faces.stream()
            .filter(f -> f.axis == 1)  // Y axis
            .count();
        // Should have at most a few Y-axis faces (ideally 2: top+bottom merged)
        assertTrue(yFaces <= 10,
            "Y-axis faces should be heavily merged (got " + yFaces + ")");
    }

    // ═══ 4. Different Materials Don't Merge ═══

    @Test
    @DisplayName("Adjacent blocks with different materials produce separate faces")
    void testDifferentMaterialsDontMerge() {
        int[] voxels = new int[4096];
        // Two adjacent blocks with different materials
        voxels[idx(7, 8, 8)] = MAT_CONCRETE;
        voxels[idx(8, 8, 8)] = MAT_STEEL;

        List<GreedyMesher.MergedFace> faces = mesher.mesh(voxels);

        // Should have faces from both materials
        boolean hasConcrete = faces.stream().anyMatch(f -> f.materialId == MAT_CONCRETE);
        boolean hasSteel = faces.stream().anyMatch(f -> f.materialId == MAT_STEEL);

        assertTrue(hasConcrete, "Should have concrete faces");
        assertTrue(hasSteel, "Should have steel faces");
    }

    // ═══ 5. Occluded Faces Not Generated ═══

    @Test
    @DisplayName("Interior faces between two same-material blocks are culled")
    void testOccludedFacesCulled() {
        int[] voxels = new int[4096];
        // Two adjacent blocks along X axis
        voxels[idx(7, 8, 8)] = MAT_CONCRETE;
        voxels[idx(8, 8, 8)] = MAT_CONCRETE;

        List<GreedyMesher.MergedFace> faces = mesher.mesh(voxels);

        // Two isolated blocks = 12 faces. Two adjacent = 10 faces (shared face culled from each)
        // With greedy merging the X-axis faces may merge, so count could be less
        assertTrue(faces.size() <= 10,
            "Adjacent blocks should have fewer faces than 12 (got " + faces.size() + ")");
    }

    // ═══ 6. 2×2×2 Solid Cube ═══

    @Test
    @DisplayName("2×2×2 cube: only exterior faces, each side merged")
    void testSmallCube() {
        int[] voxels = new int[4096];
        for (int x = 4; x < 6; x++) {
            for (int y = 4; y < 6; y++) {
                for (int z = 4; z < 6; z++) {
                    voxels[idx(x, y, z)] = MAT_CONCRETE;
                }
            }
        }

        List<GreedyMesher.MergedFace> faces = mesher.mesh(voxels);

        // 2×2×2 cube: 6 sides, each side 2×2 = 4 faces unmerged, but greedy merges each to 1
        // So ideal = 6 faces. Allow some tolerance for greedy heuristic.
        assertTrue(faces.size() >= 6 && faces.size() <= 12,
            "2×2×2 cube should produce 6-12 merged faces (got " + faces.size() + ")");

        // Total area should equal surface area = 6 × 4 = 24
        int totalArea = faces.stream().mapToInt(GreedyMesher.MergedFace::area).sum();
        assertEquals(24, totalArea,
            "Total face area should equal surface area (6 sides × 2×2)");
    }

    // ═══ 7. Corner Block (position 0,0,0) ═══

    @Test
    @DisplayName("Block at corner (0,0,0) has 6 faces (boundary = exposed)")
    void testCornerBlock() {
        int[] voxels = new int[4096];
        voxels[idx(0, 0, 0)] = MAT_CONCRETE;

        List<GreedyMesher.MergedFace> faces = mesher.mesh(voxels);
        assertEquals(6, faces.size(), "Corner block should also have 6 exposed faces");
    }

    // ═══ 8. faceToVertices Output ═══

    @Test
    @DisplayName("faceToVertices outputs correct number of floats (4 vertices × 10 floats)")
    void testFaceToVerticesOutput() {
        GreedyMesher.MergedFace face = new GreedyMesher.MergedFace(
            1, true, 0, 0, 1, 1, 5, MAT_CONCRETE);

        float[] out = new float[40]; // 4 vertices × 10 floats
        int written = GreedyMesher.faceToVertices(face, out, 0, 0f, 0f, 0f);

        assertTrue(written > 0, "Should write some floats");
        assertEquals(40, written, "4 vertices × 10 floats = 40");
    }

    // ═══ 9. Full Section (16³) ═══

    @Test
    @DisplayName("Full 16³ solid section: only 6 exterior faces")
    void testFullSectionMinimalFaces() {
        int[] voxels = new int[4096];
        for (int i = 0; i < 4096; i++) {
            voxels[i] = MAT_CONCRETE;
        }

        List<GreedyMesher.MergedFace> faces = mesher.mesh(voxels);

        // All interior faces are occluded. Only boundary faces remain.
        // Each of 6 sides is a 16×16 face → greedy merges to 1 face per side = 6 total.
        // Boundary faces are those at depth=0 (negative direction) and depth=15 (positive direction)
        // But since we don't have neighbor sections, boundary = exposed
        assertEquals(6, faces.size(),
            "Full 16³ section should produce exactly 6 merged faces (got " + faces.size() + ")");

        // Each face should be 16×16 = 256 area
        for (GreedyMesher.MergedFace face : faces) {
            assertEquals(256, face.area(),
                "Each face of full section should be 16×16 = 256");
        }
    }

    // ═══ 10. maxMergeArea Limit ═══

    @Test
    @DisplayName("maxMergeArea limits face size")
    void testMaxMergeAreaLimit() {
        GreedyMesher limitedMesher = new GreedyMesher(4); // max 2×2

        int[] voxels = new int[4096];
        // Fill y=0 layer
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                voxels[idx(x, 0, z)] = MAT_CONCRETE;
            }
        }

        List<GreedyMesher.MergedFace> faces = limitedMesher.mesh(voxels);

        // No face should exceed maxMergeArea
        for (GreedyMesher.MergedFace face : faces) {
            assertTrue(face.area() <= 4,
                "Face area " + face.area() + " exceeds maxMergeArea 4");
        }
    }
}
