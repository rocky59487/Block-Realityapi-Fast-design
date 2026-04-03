package com.blockreality.api.spi;

import net.minecraft.core.BlockPos;

import javax.annotation.Nonnull;
import java.util.Set;

/**
 * Per-block curing management — tracks concrete curing progress.
 * Construction Intern module will provide full implementation.
 *
 * Responsibilities:
 * - Track which blocks are currently curing
 * - Maintain curing progress (0.0-1.0) for each block
 * - Determine when curing is complete
 * - Periodic tick to advance curing time
 *
 * Thread-safe for concurrent access from game and physics threads.
 *
 * @since 1.0.0
 */
@SPIVersion(major = 1, minor = 0)
public interface ICuringManager {

    /**
     * Start curing a block at the given position.
     *
     * @param pos        The block position to start curing
     * @param totalTicks Total ticks required for full curing
     */
    void startCuring(@Nonnull BlockPos pos, int totalTicks);

    /**
     * Get the curing progress for a block.
     *
     * @param pos The block position
     * @return Progress from 0.0 (not started) to 1.0 (complete)
     */
    float getCuringProgress(@Nonnull BlockPos pos);

    /**
     * Check if a block has finished curing.
     *
     * @param pos The block position
     * @return true if curing is complete (progress >= 1.0), false otherwise
     */
    boolean isCuringComplete(@Nonnull BlockPos pos);

    /**
     * Advance curing progress by one tick.
     * Called once per server tick.
     *
     * @return Set of positions that completed curing this tick (empty if none)
     */
    @Nonnull Set<BlockPos> tickCuring();

    /**
     * Stop tracking a cured block.
     *
     * @param pos The block position
     */
    void removeCuring(@Nonnull BlockPos pos);

    /**
     * Get the number of blocks currently curing.
     *
     * @return Active curing block count
     */
    int getActiveCuringCount();
}
