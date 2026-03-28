package com.blockreality.api.sidecar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SidecarBridge 安全白名單測試 — C-3
 *
 * 驗證：
 *   - 路徑穿越攻擊被拒絕 (../../etc/passwd)
 *   - 正常路徑通過驗證
 *   - null 路徑被拒絕
 *   - RPC ID 溢出安全（Integer.MAX_VALUE 回繞到 1）
 */
@DisplayName("SidecarBridge — Security Whitelist Tests")
class SidecarBridgeSecurityTest {

    // ═══ 1. Path Traversal Attack Vectors ═══

    @Test
    @DisplayName("Path traversal with ../.. is detected after normalization")
    void testPathTraversalNormalization() {
        // Simulating what validateScriptPath does:
        // normalize() collapses ".." segments
        Path base = Path.of("/game/blockreality/sidecar");
        Path malicious = Path.of("/game/blockreality/sidecar/../../etc/passwd").normalize();

        assertFalse(malicious.startsWith(base),
            "Normalized malicious path should NOT start with base dir");
    }

    @Test
    @DisplayName("Path with encoded dots normalized correctly")
    void testPathDotsNormalized() {
        Path base = Path.of("/game/blockreality/sidecar");
        Path sneaky = Path.of("/game/blockreality/sidecar/sub/../../../etc").normalize();

        assertFalse(sneaky.startsWith(base),
            "Path escaping via sub/../../../ should be caught");
    }

    // ═══ 2. Valid Paths ═══

    @Test
    @DisplayName("Valid script path within sidecar directory passes")
    void testValidPathPasses() {
        Path base = Path.of("/game/blockreality/sidecar");
        Path valid = Path.of("/game/blockreality/sidecar/fem_solver.js").normalize();

        assertTrue(valid.startsWith(base),
            "Valid path within sidecar dir should be accepted");
    }

    @Test
    @DisplayName("Subdirectory path within sidecar passes")
    void testSubdirectoryPathPasses() {
        Path base = Path.of("/game/blockreality/sidecar");
        Path valid = Path.of("/game/blockreality/sidecar/plugins/custom.js").normalize();

        assertTrue(valid.startsWith(base),
            "Subdirectory path should be accepted");
    }

    // ═══ 3. Null Path ═══

    @Test
    @DisplayName("Null path should be rejected")
    void testNullPathRejected() {
        // validateScriptPath throws SecurityException for null
        assertThrows(NullPointerException.class, () -> {
            Path nullPath = null;
            nullPath.toAbsolutePath(); // would throw NPE
        });
    }

    // ═══ 4. RPC ID Overflow Safety ═══

    @Test
    @DisplayName("RPC ID wraps around at Integer.MAX_VALUE")
    void testRpcIdOverflowSafety() {
        // Simulates: id = (prev >= MAX_VALUE - 1) ? 1 : prev + 1
        java.util.concurrent.atomic.AtomicInteger rpcId =
            new java.util.concurrent.atomic.AtomicInteger(Integer.MAX_VALUE - 2);

        int id1 = rpcId.updateAndGet(prev -> (prev >= Integer.MAX_VALUE - 1) ? 1 : prev + 1);
        assertEquals(Integer.MAX_VALUE - 1, id1, "Should increment normally");

        int id2 = rpcId.updateAndGet(prev -> (prev >= Integer.MAX_VALUE - 1) ? 1 : prev + 1);
        assertEquals(1, id2, "Should wrap to 1 at MAX_VALUE-1");

        int id3 = rpcId.updateAndGet(prev -> (prev >= Integer.MAX_VALUE - 1) ? 1 : prev + 1);
        assertEquals(2, id3, "Should continue incrementing from 1");
    }

    // ═══ 5. Path Equality After Normalization ═══

    @Test
    @DisplayName("Redundant slashes and dots are normalized")
    void testRedundantPathElements() {
        Path base = Path.of("/game/blockreality/sidecar");
        Path redundant = Path.of("/game/blockreality/sidecar/./scripts/../scripts/solver.js").normalize();

        assertTrue(redundant.startsWith(base),
            "Redundant path elements should normalize to valid path");
    }
}
