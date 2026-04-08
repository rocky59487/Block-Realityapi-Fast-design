package com.blockreality.api.physics.pfsf;

import com.blockreality.api.material.DefaultMaterial;
import com.blockreality.api.material.VanillaMaterialMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chunk-level physics LOD — ML-driven 4-tier system for global physics.
 *
 * <p>Every loaded chunk gets a physics LOD tier based on ML classification:</p>
 * <pre>
 *   Tier 0 (SKIP):  Untouched terrain — no physics computation
 *   Tier 1 (MARK):  Player-modified — track critical blocks only
 *   Tier 2 (PFSF):  Active structures — full PFSF iterative solve
 *   Tier 3 (FNO):   Complex irregular — FNO ML inference
 * </pre>
 *
 * <p>Tier assignment uses a lightweight heuristic (no GPU needed):</p>
 * <ul>
 *   <li>Chunk never modified by player → SKIP</li>
 *   <li>Few blocks changed → MARK (identify keystones only)</li>
 *   <li>Significant modification or mod blocks present → PFSF/FNO</li>
 * </ul>
 *
 * <p>This enables physics for the ENTIRE world without crushing performance:
 * only chunks where players have built/destroyed get computed.</p>
 *
 * @since v1.0 (BIFROST Sprint 2)
 */
public class ChunkPhysicsLOD {

    private static final Logger LOGGER = LoggerFactory.getLogger("BIFROST-LOD");

    public enum Tier {
        SKIP(0),      // No physics — pristine terrain
        MARK(1),      // Keystone tracking only — critical block identification
        PFSF(2),      // Full PFSF solve — regular modified structures
        FNO(3);       // FNO ML inference — complex irregular structures

        public final int level;
        Tier(int level) { this.level = level; }
    }

    /** Per-chunk LOD tier cache. Key = chunk long pos. */
    private final ConcurrentHashMap<Long, ChunkLODEntry> cache = new ConcurrentHashMap<>();

    /** Chunks known to be player-modified. Populated by block change events. */
    private final Set<Long> modifiedChunks = ConcurrentHashMap.newKeySet();

    // ── Thresholds (configurable) ──
    private int skipToMarkThreshold = 1;       // blocks changed to upgrade from SKIP→MARK
    private int markToPfsfThreshold = 8;       // blocks changed to upgrade from MARK→PFSF
    private float pfsfToFnoThreshold = 0.45f;  // irregularity score for PFSF→FNO

    /**
     * Get physics LOD tier for a chunk.
     *
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return LOD tier
     */
    public Tier getTier(int chunkX, int chunkZ) {
        long key = chunkKey(chunkX, chunkZ);
        ChunkLODEntry entry = cache.get(key);
        return entry != null ? entry.tier : Tier.SKIP;
    }

    /**
     * Notify a block change in a chunk. May upgrade LOD tier.
     *
     * @param pos         Changed block position
     * @param wasAir      True if the old block was air (placement)
     * @param isAir       True if the new block is air (destruction)
     * @param isModBlock   True if this is a Block Reality mod block
     */
    public void onBlockChange(BlockPos pos, boolean wasAir, boolean isAir, boolean isModBlock) {
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        long key = chunkKey(cx, cz);

        modifiedChunks.add(key);

        ChunkLODEntry entry = cache.computeIfAbsent(key,
                k -> new ChunkLODEntry(Tier.SKIP, 0));

        entry.changeCount++;

        // Upgrade tier based on modification intensity
        if (isModBlock) {
            // Mod blocks always get full physics
            entry.tier = Tier.PFSF;
        } else if (entry.changeCount >= markToPfsfThreshold) {
            // Many changes → full physics
            if (entry.tier.level < Tier.PFSF.level) {
                entry.tier = Tier.PFSF;
            }
        } else if (entry.changeCount >= skipToMarkThreshold) {
            // Some changes → at least track keystones
            if (entry.tier == Tier.SKIP) {
                entry.tier = Tier.MARK;
            }
        }
    }

    /**
     * Upgrade a chunk from PFSF to FNO based on irregularity score.
     * Called after ShapeClassifier analysis.
     */
    public void upgradeToFNO(int chunkX, int chunkZ) {
        long key = chunkKey(chunkX, chunkZ);
        ChunkLODEntry entry = cache.get(key);
        if (entry != null && entry.tier == Tier.PFSF) {
            entry.tier = Tier.FNO;
        }
    }

    /**
     * Check if a chunk needs any physics computation.
     */
    public boolean needsPhysics(int chunkX, int chunkZ) {
        return getTier(chunkX, chunkZ) != Tier.SKIP;
    }

    /**
     * Get all chunks that need physics this tick (Tier >= MARK).
     */
    public List<long[]> getActiveChunks() {
        List<long[]> result = new ArrayList<>();
        for (var entry : cache.entrySet()) {
            if (entry.getValue().tier != Tier.SKIP) {
                long key = entry.getKey();
                result.add(new long[]{key >> 32, key & 0xFFFFFFFFL});
            }
        }
        return result;
    }

    /** Reset a chunk back to SKIP (e.g., when all structures removed). */
    public void resetChunk(int chunkX, int chunkZ) {
        cache.remove(chunkKey(chunkX, chunkZ));
    }

    public String getStats() {
        int[] counts = new int[4];
        for (var e : cache.values()) counts[e.tier.ordinal()]++;
        return String.format("LOD: %d skip, %d mark, %d pfsf, %d fno (%d total)",
                counts[0], counts[1], counts[2], counts[3], cache.size());
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private static class ChunkLODEntry {
        volatile Tier tier;
        volatile int changeCount;

        ChunkLODEntry(Tier tier, int changeCount) {
            this.tier = tier;
            this.changeCount = changeCount;
        }
    }
}
