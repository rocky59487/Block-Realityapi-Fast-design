package com.blockreality.api.physics.pfsf;

import com.blockreality.api.material.RMaterial;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;

/**
 * Identifies structural keystone blocks — blocks whose removal would
 * cause cascade failure.
 *
 * <p>Used by {@link ChunkPhysicsLOD} Tier 1 (MARK) to track critical
 * blocks in player-modified vanilla terrain without running full physics.</p>
 *
 * <p>A keystone is a block that, if removed:</p>
 * <ul>
 *   <li>Disconnects the structure graph (bridge node in graph theory)</li>
 *   <li>Or supports significant load (high stress concentration)</li>
 *   <li>Or is the sole support path for blocks above</li>
 * </ul>
 *
 * <p>Algorithm: Modified Tarjan's bridge-finding (O(V+E)) on the block
 * adjacency graph. Blocks on bridges or with high betweenness are keystones.</p>
 *
 * <p>This allows the game to warn players: "breaking this block will
 * cause collapse" without running full PFSF/FNO computation.</p>
 *
 * @since v1.0 (BIFROST Sprint 2)
 */
public final class StructuralKeystone {

    private static final Logger LOGGER = LoggerFactory.getLogger("BIFROST-Keystone");

    private StructuralKeystone() {}

    /**
     * Result of keystone analysis.
     */
    public record KeystoneResult(
        /** Blocks whose removal would disconnect the structure. */
        Set<BlockPos> bridgeBlocks,

        /** Blocks that are sole vertical support for blocks above. */
        Set<BlockPos> loadBearingBlocks,

        /** Combined: all critical blocks. */
        Set<BlockPos> allKeystones,

        /** Number of blocks that would fall if each keystone is removed. */
        Map<BlockPos, Integer> impactCount
    ) {}

    /**
     * Analyze a set of blocks for keystones.
     *
     * @param members    All solid blocks in the structure
     * @param anchors    Fixed anchor blocks (ground)
     * @return KeystoneResult with all identified critical blocks
     */
    public static KeystoneResult analyze(Set<BlockPos> members, Set<BlockPos> anchors) {
        if (members.size() < 3) {
            return new KeystoneResult(Set.of(), Set.of(), Set.of(), Map.of());
        }

        // ── 1. Find bridge blocks (Tarjan's algorithm) ──
        Set<BlockPos> bridges = findBridgeBlocks(members);

        // ── 2. Find sole vertical support blocks ──
        Set<BlockPos> loadBearing = findLoadBearingBlocks(members, anchors);

        // ── 3. Estimate impact of each keystone ──
        Set<BlockPos> allKeystones = new HashSet<>();
        allKeystones.addAll(bridges);
        allKeystones.addAll(loadBearing);

        Map<BlockPos, Integer> impact = new HashMap<>();
        for (BlockPos keystone : allKeystones) {
            int count = estimateImpact(keystone, members, anchors);
            impact.put(keystone, count);
        }

        return new KeystoneResult(bridges, loadBearing, allKeystones, impact);
    }

    /**
     * Quick check: is this specific block a keystone?
     * Cheaper than full analysis — only checks immediate neighborhood.
     */
    public static boolean isKeystone(BlockPos pos, Set<BlockPos> members, Set<BlockPos> anchors) {
        if (!members.contains(pos) || anchors.contains(pos)) return false;

        // Count neighbors
        int neighbors = 0;
        for (Direction dir : Direction.values()) {
            if (members.contains(pos.relative(dir))) neighbors++;
        }

        // Blocks with only 1-2 connections AND supporting blocks above are critical
        if (neighbors <= 2) {
            BlockPos above = pos.above();
            if (members.contains(above) && !anchors.contains(above)) {
                return true;
            }
        }

        // Check if removing this block disconnects any above blocks from anchors
        return wouldDisconnect(pos, members, anchors);
    }

    // ── Tarjan's bridge detection (simplified for block grid) ──

    private static Set<BlockPos> findBridgeBlocks(Set<BlockPos> members) {
        Set<BlockPos> bridges = new HashSet<>();
        Map<BlockPos, Integer> disc = new HashMap<>();
        Map<BlockPos, Integer> low = new HashMap<>();
        int[] timer = {0};

        for (BlockPos start : members) {
            if (!disc.containsKey(start)) {
                tarjanDFS(start, null, members, disc, low, timer, bridges);
            }
            if (bridges.size() > 1000) break; // safety limit
        }
        return bridges;
    }

    private static void tarjanDFS(BlockPos u, BlockPos parent, Set<BlockPos> members,
                                   Map<BlockPos, Integer> disc, Map<BlockPos, Integer> low,
                                   int[] timer, Set<BlockPos> bridges) {
        disc.put(u, timer[0]);
        low.put(u, timer[0]);
        timer[0]++;

        int children = 0;
        boolean isArticulation = false;

        for (Direction dir : Direction.values()) {
            BlockPos v = u.relative(dir);
            if (!members.contains(v)) continue;

            if (!disc.containsKey(v)) {
                children++;
                tarjanDFS(v, u, members, disc, low, timer, bridges);
                low.put(u, Math.min(low.get(u), low.get(v)));

                // u is articulation point if:
                // 1. u is root and has 2+ children
                // 2. u is not root and low[v] >= disc[u]
                if (parent == null && children > 1) isArticulation = true;
                if (parent != null && low.get(v) >= disc.get(u)) isArticulation = true;
            } else if (!v.equals(parent)) {
                low.put(u, Math.min(low.get(u), disc.get(v)));
            }
        }

        if (isArticulation) bridges.add(u);
    }

    // ── Load-bearing detection ──

    private static Set<BlockPos> findLoadBearingBlocks(Set<BlockPos> members, Set<BlockPos> anchors) {
        Set<BlockPos> loadBearing = new HashSet<>();

        for (BlockPos pos : members) {
            if (anchors.contains(pos)) continue;

            BlockPos above = pos.above();
            if (!members.contains(above)) continue;

            // Check if 'above' has any other support besides 'pos'
            boolean hasAlternateSupport = false;
            for (Direction dir : Direction.values()) {
                if (dir == Direction.UP) continue;
                BlockPos neighbor = above.relative(dir);
                if (!neighbor.equals(pos) && members.contains(neighbor)) {
                    hasAlternateSupport = true;
                    break;
                }
            }
            if (!hasAlternateSupport) {
                loadBearing.add(pos);
            }
        }
        return loadBearing;
    }

    // ── Disconnection check ──

    private static boolean wouldDisconnect(BlockPos removed, Set<BlockPos> members,
                                            Set<BlockPos> anchors) {
        // Find blocks above that connect to anchors only through 'removed'
        BlockPos above = removed.above();
        if (!members.contains(above)) return false;

        // BFS from 'above' without using 'removed' — can we reach an anchor?
        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> queue = new ArrayDeque<>();
        queue.add(above);
        visited.add(above);
        visited.add(removed); // pretend removed is gone

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            if (anchors.contains(current)) return false; // reachable → not a disconnect

            if (visited.size() > 500) return false; // safety: too large to check quickly

            for (Direction dir : Direction.values()) {
                BlockPos next = current.relative(dir);
                if (members.contains(next) && !visited.contains(next)) {
                    visited.add(next);
                    queue.add(next);
                }
            }
        }
        return true; // could not reach anchor → removing would disconnect
    }

    // ── Impact estimation ──

    private static int estimateImpact(BlockPos keystone, Set<BlockPos> members,
                                       Set<BlockPos> anchors) {
        // BFS from blocks connected to keystone — count those disconnected from anchors
        Set<BlockPos> membersWithout = new HashSet<>(members);
        membersWithout.remove(keystone);

        int disconnected = 0;
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = keystone.relative(dir);
            if (!membersWithout.contains(neighbor)) continue;

            // BFS to see if this neighbor can still reach an anchor
            Set<BlockPos> visited = new HashSet<>();
            Queue<BlockPos> queue = new ArrayDeque<>();
            queue.add(neighbor);
            visited.add(neighbor);
            boolean reachesAnchor = false;

            while (!queue.isEmpty() && visited.size() < 500) {
                BlockPos current = queue.poll();
                if (anchors.contains(current)) {
                    reachesAnchor = true;
                    break;
                }
                for (Direction d : Direction.values()) {
                    BlockPos next = current.relative(d);
                    if (membersWithout.contains(next) && !visited.contains(next)) {
                        visited.add(next);
                        queue.add(next);
                    }
                }
            }

            if (!reachesAnchor) {
                disconnected += visited.size();
            }
        }
        return disconnected;
    }
}
