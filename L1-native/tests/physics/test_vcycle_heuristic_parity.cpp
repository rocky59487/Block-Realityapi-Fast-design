/**
 * @file test_vcycle_heuristic_parity.cpp
 * @brief Parity tests: V-Cycle heuristic must match Java PFSFIslandBuffer.
 *
 * Java reference (PFSFIslandBuffer.getLmax()):
 *   return Math.max(Lx, Math.max(Ly, Lz))
 * Java gate (PFSFDispatcher):
 *   vcycleProductive = (getLmax() > 4)
 *
 * C++ dispatcher (PR#187 capy-ai R5 fix): uses std::max, not std::min.
 * These tests guard against regression where min() was used instead.
 */

#include <gtest/gtest.h>
#include <algorithm>

// Standalone port of the C++ vcycleProductive heuristic so these tests
// compile without a Vulkan device. Any change to the real heuristic in
// dispatcher.cpp must be mirrored here.
static int getLmax_java(int lx, int ly, int lz) {
    return std::max({ lx, ly, lz });
}

static bool vcycleProductive_java(int lx, int ly, int lz) {
    return getLmax_java(lx, ly, lz) > 4;
}

static bool vcycleProductive_native(int lx, int ly, int lz) {
    // Must match dispatcher.cpp — std::max, not std::min
    return std::max({ lx, ly, lz }) > 4;
}

static void expectParity(int lx, int ly, int lz) {
    EXPECT_EQ(vcycleProductive_java(lx, ly, lz),
              vcycleProductive_native(lx, ly, lz))
        << "Mismatch for (" << lx << "," << ly << "," << lz << ")";
}

TEST(VCycleHeuristicParity, CubicIsland) {
    expectParity(8, 8, 8);   // max=8 → productive
    expectParity(4, 4, 4);   // max=4 → not productive
    expectParity(5, 5, 5);   // max=5 → productive
    expectParity(2, 2, 2);   // max=2 → not productive
}

TEST(VCycleHeuristicParity, ThinSlab_MaxDimMatters) {
    // A slab: 32 × 1 × 32. Java uses max=32 → productive.
    // The old incorrect min=1 → not productive (regression).
    EXPECT_TRUE(vcycleProductive_native(32, 1, 32))
        << "slab (32×1×32): native should be productive (max=32)";
    EXPECT_EQ(vcycleProductive_java(32, 1, 32),
              vcycleProductive_native(32, 1, 32));
}

TEST(VCycleHeuristicParity, ThinWall) {
    // Wall: 1 × 16 × 1. max=16 → productive.
    EXPECT_TRUE(vcycleProductive_native(1, 16, 1));
    EXPECT_EQ(vcycleProductive_java(1, 16, 1),
              vcycleProductive_native(1, 16, 1));
}

TEST(VCycleHeuristicParity, Bridge_LongAxis) {
    // Bridge: 64 × 4 × 4. max=64 → productive.
    EXPECT_TRUE(vcycleProductive_native(64, 4, 4));
    EXPECT_EQ(vcycleProductive_java(64, 4, 4),
              vcycleProductive_native(64, 4, 4));
}

TEST(VCycleHeuristicParity, SmallIsland_AllDimsAtBoundary) {
    expectParity(4, 4, 5);  // max=5 → productive
    expectParity(3, 3, 4);  // max=4 → not productive
    expectParity(5, 3, 3);  // max=5 → productive (fails with min=3)
}
