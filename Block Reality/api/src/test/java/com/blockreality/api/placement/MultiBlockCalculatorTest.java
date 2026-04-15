package com.blockreality.api.placement;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for MultiBlockCalculator — pure geometry functions.
 *
 * Tests cover:
 * - NORMAL mode
 * - LINE (Bresenham DDA): horizontal, vertical, diagonal, single point
 * - WALL: XY plane, YZ plane, XZ plane, degenerate cases
 * - CUBE: 1×1×1, 2×3×4, inverted corners, symmetry
 * - MIRROR_X / MIRROR_Z: basic mirror, on-axis, null anchor
 * - applyMirrorToList: deduplication on mirror plane
 * - BuildMode next() / prev() cycling
 * - calculate() dispatch to each mode
 */
@DisplayName("MultiBlockCalculator — geometry tests")
class MultiBlockCalculatorTest {

    // ─── NORMAL ───

    @Nested
    @DisplayName("NORMAL mode")
    class NormalMode {

        @Test
        @DisplayName("returns exactly pos2")
        void testNormalReturnPos2() {
            BlockPos p1 = new BlockPos(0, 0, 0);
            BlockPos p2 = new BlockPos(5, 10, 3);
            List<BlockPos> result = MultiBlockCalculator.calculate(BuildMode.NORMAL, p1, p2, null);
            assertEquals(1, result.size());
            assertEquals(p2, result.get(0));
        }

        @Test
        @DisplayName("result is same regardless of p1")
        void testNormalIgnoresP1() {
            BlockPos p1a = new BlockPos(-100, -100, -100);
            BlockPos p1b = new BlockPos(0, 0, 0);
            BlockPos p2 = new BlockPos(3, 4, 5);
            assertEquals(
                MultiBlockCalculator.calculate(BuildMode.NORMAL, p1a, p2, null),
                MultiBlockCalculator.calculate(BuildMode.NORMAL, p1b, p2, null)
            );
        }
    }

    // ─── LINE ───

    @Nested
    @DisplayName("LINE (Bresenham DDA)")
    class LineMode {

        @Test
        @DisplayName("single point (pos1 == pos2) returns 1 block")
        void testLineSinglePoint() {
            BlockPos p = new BlockPos(3, 5, 7);
            List<BlockPos> result = MultiBlockCalculator.computeLine(p, p);
            assertEquals(1, result.size());
            assertEquals(p, result.get(0));
        }

        @Test
        @DisplayName("horizontal X line produces correct count and endpoints")
        void testLineHorizontalX() {
            BlockPos from = new BlockPos(0, 0, 0);
            BlockPos to = new BlockPos(4, 0, 0);
            List<BlockPos> result = MultiBlockCalculator.computeLine(from, to);
            assertEquals(5, result.size());
            assertEquals(from, result.get(0));
            assertEquals(to, result.get(result.size() - 1));
        }

        @Test
        @DisplayName("horizontal Z line produces correct count")
        void testLineHorizontalZ() {
            BlockPos from = new BlockPos(0, 0, 0);
            BlockPos to = new BlockPos(0, 0, 6);
            List<BlockPos> result = MultiBlockCalculator.computeLine(from, to);
            assertEquals(7, result.size());
        }

        @Test
        @DisplayName("vertical Y line produces correct count")
        void testLineVertical() {
            BlockPos from = new BlockPos(0, 0, 0);
            BlockPos to = new BlockPos(0, 5, 0);
            List<BlockPos> result = MultiBlockCalculator.computeLine(from, to);
            assertEquals(6, result.size());
        }

        @Test
        @DisplayName("diagonal XZ line (45°) has correct length")
        void testLineDiagonal() {
            BlockPos from = new BlockPos(0, 0, 0);
            BlockPos to = new BlockPos(3, 0, 3);
            List<BlockPos> result = MultiBlockCalculator.computeLine(from, to);
            // DDA along driving axis = 3, so 4 points
            assertEquals(4, result.size());
            assertEquals(from, result.get(0));
            assertEquals(to, result.get(result.size() - 1));
        }

        @Test
        @DisplayName("reversed line has same length as forward line")
        void testLineReversed() {
            BlockPos a = new BlockPos(0, 0, 0);
            BlockPos b = new BlockPos(5, 3, 2);
            int forward = MultiBlockCalculator.computeLine(a, b).size();
            int reverse = MultiBlockCalculator.computeLine(b, a).size();
            assertEquals(forward, reverse);
        }

        @Test
        @DisplayName("3D diagonal line contains no duplicate positions")
        void testLineNoDuplicates() {
            BlockPos from = new BlockPos(0, 0, 0);
            BlockPos to = new BlockPos(7, 5, 3);
            List<BlockPos> result = MultiBlockCalculator.computeLine(from, to);
            Set<BlockPos> unique = new HashSet<>(result);
            assertEquals(result.size(), unique.size(), "Line should have no duplicate positions");
        }

        @Test
        @DisplayName("negative direction line works correctly")
        void testLineNegativeDirection() {
            BlockPos from = new BlockPos(5, 0, 0);
            BlockPos to = new BlockPos(0, 0, 0);
            List<BlockPos> result = MultiBlockCalculator.computeLine(from, to);
            assertEquals(6, result.size());
            assertEquals(from, result.get(0));
            assertEquals(to, result.get(result.size() - 1));
        }

        @Test
        @DisplayName("via calculate(LINE) delegates to computeLine")
        void testCalculateLine() {
            BlockPos p1 = new BlockPos(0, 0, 0);
            BlockPos p2 = new BlockPos(3, 0, 0);
            List<BlockPos> fromCalc = MultiBlockCalculator.calculate(BuildMode.LINE, p1, p2, null);
            List<BlockPos> fromDirect = MultiBlockCalculator.computeLine(p1, p2);
            assertEquals(fromDirect, fromCalc);
        }
    }

    // ─── WALL ───

    @Nested
    @DisplayName("WALL (2D rectangular plane)")
    class WallMode {

        @Test
        @DisplayName("same point returns 1 block")
        void testWallSingleBlock() {
            BlockPos p = new BlockPos(1, 1, 1);
            List<BlockPos> result = MultiBlockCalculator.computeWall(p, p);
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("XY plane wall (spanZ=0): correct block count")
        void testWallXYPlane() {
            // Both positions share same Z
            BlockPos p1 = new BlockPos(0, 0, 5);
            BlockPos p2 = new BlockPos(3, 4, 5);  // spanX=3, spanY=4, spanZ=0 → collapse Z
            List<BlockPos> result = MultiBlockCalculator.computeWall(p1, p2);
            // XY wall: (3+1)*(4+1) = 20 blocks
            assertEquals(20, result.size());
            // All at same Z
            result.forEach(b -> assertEquals(5, b.getZ()));
        }

        @Test
        @DisplayName("YZ plane wall (spanX=0): correct block count")
        void testWallYZPlane() {
            BlockPos p1 = new BlockPos(3, 0, 0);
            BlockPos p2 = new BlockPos(3, 3, 4);  // spanX=0, spanY=3, spanZ=4 → collapse X
            List<BlockPos> result = MultiBlockCalculator.computeWall(p1, p2);
            assertEquals(4 * 5, result.size()); // (3+1)*(4+1)=20
            result.forEach(b -> assertEquals(3, b.getX()));
        }

        @Test
        @DisplayName("XZ floor wall (spanY=0): correct block count")
        void testWallXZFloor() {
            BlockPos p1 = new BlockPos(0, 5, 0);
            BlockPos p2 = new BlockPos(4, 5, 3);  // spanX=4, spanY=0, spanZ=3 → collapse Y
            List<BlockPos> result = MultiBlockCalculator.computeWall(p1, p2);
            assertEquals(5 * 4, result.size()); // (4+1)*(3+1)=20
            result.forEach(b -> assertEquals(5, b.getY()));
        }

        @Test
        @DisplayName("wall has no duplicate positions")
        void testWallNoDuplicates() {
            BlockPos p1 = new BlockPos(0, 0, 0);
            BlockPos p2 = new BlockPos(4, 4, 0);
            List<BlockPos> result = MultiBlockCalculator.computeWall(p1, p2);
            Set<BlockPos> unique = new HashSet<>(result);
            assertEquals(result.size(), unique.size());
        }

        @Test
        @DisplayName("via calculate(WALL) delegates to computeWall")
        void testCalculateWall() {
            BlockPos p1 = new BlockPos(0, 0, 0);
            BlockPos p2 = new BlockPos(2, 3, 0);
            List<BlockPos> fromCalc = MultiBlockCalculator.calculate(BuildMode.WALL, p1, p2, null);
            List<BlockPos> fromDirect = MultiBlockCalculator.computeWall(p1, p2);
            assertEquals(fromDirect.size(), fromCalc.size());
        }
    }

    // ─── CUBE ───

    @Nested
    @DisplayName("CUBE (3D filled cuboid)")
    class CubeMode {

        @Test
        @DisplayName("1×1×1 cube is 1 block")
        void testCube1x1x1() {
            BlockPos p = new BlockPos(0, 0, 0);
            List<BlockPos> result = MultiBlockCalculator.computeCube(p, p);
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("2×2×2 cube is 8 blocks")
        void testCube2x2x2() {
            BlockPos p1 = new BlockPos(0, 0, 0);
            BlockPos p2 = new BlockPos(1, 1, 1);
            List<BlockPos> result = MultiBlockCalculator.computeCube(p1, p2);
            assertEquals(8, result.size());
        }

        @Test
        @DisplayName("2×3×4 cuboid has correct count")
        void testCubeRectangular() {
            BlockPos p1 = new BlockPos(0, 0, 0);
            BlockPos p2 = new BlockPos(1, 2, 3);
            List<BlockPos> result = MultiBlockCalculator.computeCube(p1, p2);
            assertEquals(2 * 3 * 4, result.size());
        }

        @Test
        @DisplayName("cube with inverted corners (p2 < p1) still works")
        void testCubeInvertedCorners() {
            BlockPos p1 = new BlockPos(3, 3, 3);
            BlockPos p2 = new BlockPos(0, 0, 0);
            List<BlockPos> result = MultiBlockCalculator.computeCube(p1, p2);
            assertEquals(4 * 4 * 4, result.size());
        }

        @Test
        @DisplayName("cube contains both corners")
        void testCubeContainsCorners() {
            BlockPos p1 = new BlockPos(1, 2, 3);
            BlockPos p2 = new BlockPos(4, 6, 8);
            List<BlockPos> result = MultiBlockCalculator.computeCube(p1, p2);
            assertTrue(result.contains(p1));
            assertTrue(result.contains(p2));
        }

        @Test
        @DisplayName("cube has no duplicate positions")
        void testCubeNoDuplicates() {
            BlockPos p1 = new BlockPos(0, 0, 0);
            BlockPos p2 = new BlockPos(3, 3, 3);
            List<BlockPos> result = MultiBlockCalculator.computeCube(p1, p2);
            Set<BlockPos> unique = new HashSet<>(result);
            assertEquals(result.size(), unique.size());
        }

        @Test
        @DisplayName("via calculate(CUBE) delegates to computeCube")
        void testCalculateCube() {
            BlockPos p1 = new BlockPos(0, 0, 0);
            BlockPos p2 = new BlockPos(2, 2, 2);
            List<BlockPos> fromCalc = MultiBlockCalculator.calculate(BuildMode.CUBE, p1, p2, null);
            List<BlockPos> fromDirect = MultiBlockCalculator.computeCube(p1, p2);
            assertEquals(fromDirect.size(), fromCalc.size());
        }
    }

    // ─── MIRROR ───

    @Nested
    @DisplayName("MIRROR modes")
    class MirrorMode {

        @Test
        @DisplayName("MIRROR_X with null anchor returns only pos2")
        void testMirrorXNullAnchor() {
            BlockPos p2 = new BlockPos(5, 0, 0);
            List<BlockPos> result = MultiBlockCalculator.computeMirror(null, p2, null, 'x');
            assertEquals(1, result.size());
            assertEquals(p2, result.get(0));
        }

        @Test
        @DisplayName("MIRROR_X reflects across YZ plane")
        void testMirrorX() {
            BlockPos p2 = new BlockPos(5, 0, 0);
            BlockPos anchor = new BlockPos(0, 0, 0);  // mirror plane at x=0
            List<BlockPos> result = MultiBlockCalculator.computeMirror(null, p2, anchor, 'x');
            assertEquals(2, result.size());
            assertTrue(result.contains(p2));
            assertTrue(result.contains(new BlockPos(-5, 0, 0)));  // mirror: 2*0 - 5 = -5
        }

        @Test
        @DisplayName("MIRROR_Z reflects across XY plane")
        void testMirrorZ() {
            BlockPos p2 = new BlockPos(0, 0, 3);
            BlockPos anchor = new BlockPos(0, 0, 0);  // mirror plane at z=0
            List<BlockPos> result = MultiBlockCalculator.computeMirror(null, p2, anchor, 'z');
            assertEquals(2, result.size());
            assertTrue(result.contains(p2));
            assertTrue(result.contains(new BlockPos(0, 0, -3)));
        }

        @Test
        @DisplayName("MIRROR_X on the mirror plane returns 1 block (deduplicated)")
        void testMirrorXOnPlane() {
            // pos2.x == anchor.x → mirrored == pos2 (no duplicate added)
            BlockPos p2 = new BlockPos(0, 0, 0);
            BlockPos anchor = new BlockPos(0, 0, 0);
            List<BlockPos> result = MultiBlockCalculator.computeMirror(null, p2, anchor, 'x');
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("MIRROR_X non-zero anchor offset")
        void testMirrorXNonZeroAnchor() {
            BlockPos p2 = new BlockPos(3, 5, 0);
            BlockPos anchor = new BlockPos(1, 0, 0);   // mirror plane at x=1
            // reflected x = 2*1 - 3 = -1
            List<BlockPos> result = MultiBlockCalculator.computeMirror(null, p2, anchor, 'x');
            assertEquals(2, result.size());
            assertTrue(result.contains(new BlockPos(-1, 5, 0)));
        }

        @Test
        @DisplayName("invalid axis throws IllegalArgumentException")
        void testMirrorInvalidAxis() {
            BlockPos p2 = new BlockPos(1, 1, 1);
            BlockPos anchor = new BlockPos(0, 0, 0);
            assertThrows(IllegalArgumentException.class,
                () -> MultiBlockCalculator.computeMirror(null, p2, anchor, 'w'));
        }

        @Test
        @DisplayName("via calculate(MIRROR_X) delegates correctly")
        void testCalculateMirrorX() {
            BlockPos p1 = BlockPos.ZERO;
            BlockPos p2 = new BlockPos(5, 0, 0);
            BlockPos anchor = new BlockPos(0, 0, 0);
            List<BlockPos> result = MultiBlockCalculator.calculate(BuildMode.MIRROR_X, p1, p2, anchor);
            assertTrue(result.contains(p2));
            assertTrue(result.contains(new BlockPos(-5, 0, 0)));
        }

        @Test
        @DisplayName("via calculate(MIRROR_Z) delegates correctly")
        void testCalculateMirrorZ() {
            BlockPos p1 = BlockPos.ZERO;
            BlockPos p2 = new BlockPos(0, 0, 4);
            BlockPos anchor = new BlockPos(0, 0, 0);
            List<BlockPos> result = MultiBlockCalculator.calculate(BuildMode.MIRROR_Z, p1, p2, anchor);
            assertTrue(result.contains(p2));
            assertTrue(result.contains(new BlockPos(0, 0, -4)));
        }
    }

    // ─── applyMirrorToList ───

    @Nested
    @DisplayName("applyMirrorToList")
    class ApplyMirrorToList {

        @Test
        @DisplayName("mirrors all positions in a line")
        void testApplyMirrorToLine() {
            // Line: x=0 to x=3 at y=0,z=0
            List<BlockPos> line = MultiBlockCalculator.computeLine(
                new BlockPos(0, 0, 0), new BlockPos(3, 0, 0));
            BlockPos anchor = new BlockPos(0, 0, 0);
            List<BlockPos> result = MultiBlockCalculator.applyMirrorToList(line, anchor, 'z');
            // Line is all on z=0 so z-mirror produces z=0 (on plane) → no new blocks
            // (mirror: 2*0 - 0 = 0, same as input)
            assertEquals(line.size(), result.size());
        }

        @Test
        @DisplayName("mirrors across x produces double the positions (when none on mirror plane)")
        void testApplyMirrorDoublesOffPlane() {
            List<BlockPos> positions = List.of(
                new BlockPos(1, 0, 0),
                new BlockPos(2, 0, 0),
                new BlockPos(3, 0, 0)
            );
            BlockPos anchor = new BlockPos(0, 0, 0);
            List<BlockPos> result = MultiBlockCalculator.applyMirrorToList(positions, anchor, 'x');
            // Each x>0 mirrors to x<0; none on plane → 6 total
            assertEquals(6, result.size());
        }

        @Test
        @DisplayName("deduplicates positions on the mirror plane")
        void testApplyMirrorDeduplication() {
            // Position at x=0 (on the mirror plane x=0)
            List<BlockPos> positions = List.of(
                new BlockPos(0, 0, 0),
                new BlockPos(1, 0, 0)
            );
            BlockPos anchor = new BlockPos(0, 0, 0);
            List<BlockPos> result = MultiBlockCalculator.applyMirrorToList(positions, anchor, 'x');
            Set<BlockPos> unique = new HashSet<>(result);
            assertEquals(result.size(), unique.size(), "No duplicates after mirror");
        }
    }

    // ─── BuildMode cycling ───

    @Nested
    @DisplayName("BuildMode enum cycling")
    class BuildModeCycling {

        @Test
        @DisplayName("next() cycles through all modes and wraps")
        void testNextCycles() {
            BuildMode mode = BuildMode.NORMAL;
            for (int i = 0; i < BuildMode.values().length; i++) {
                mode = mode.next();
            }
            assertEquals(BuildMode.NORMAL, mode, "After full cycle, should return to NORMAL");
        }

        @Test
        @DisplayName("prev() cycles backwards through all modes and wraps")
        void testPrevCycles() {
            BuildMode mode = BuildMode.NORMAL;
            for (int i = 0; i < BuildMode.values().length; i++) {
                mode = mode.prev();
            }
            assertEquals(BuildMode.NORMAL, mode, "After full reverse cycle, should return to NORMAL");
        }

        @Test
        @DisplayName("next() then prev() returns to original mode")
        void testNextPrevRoundTrip() {
            for (BuildMode mode : BuildMode.values()) {
                assertEquals(mode, mode.next().prev(),
                    "next().prev() should be identity for " + mode);
            }
        }

        @Test
        @DisplayName("getDisplayName() and getDescription() are non-null and non-empty")
        void testDisplayNameAndDescription() {
            for (BuildMode mode : BuildMode.values()) {
                assertNotNull(mode.getDisplayName());
                assertFalse(mode.getDisplayName().isEmpty());
                assertNotNull(mode.getDescription());
                assertFalse(mode.getDescription().isEmpty());
            }
        }
    }
}
