package com.blockreality.api.event;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.eventbus.api.Event;

/**
 * Curing Progress Event — Fired when a block's curing progress changes.
 *
 * This event is posted on the FORGE event bus when a material curing process
 * (e.g., concrete hardening) progresses or completes. The event can be used
 * to track material maturation, trigger phase transitions, or display curing
 * progress to players.
 *
 * Module use case: Construction Intern can listen to curing completion events
 * to trigger reinforcement readiness checks or material strength updates.
 */
public class CuringProgressEvent extends Event {

    private final ServerLevel level;
    private final BlockPos pos;
    private final float progress;  // 0.0 to 1.0
    private final boolean completed;

    /**
     * Construct a curing progress event.
     *
     * @param level The server level where curing is occurring
     * @param pos The block position being cured
     * @param progress The curing progress as a fraction from 0.0 to 1.0
     * @param completed Whether curing has completed (progress >= 1.0)
     */
    public CuringProgressEvent(ServerLevel level, BlockPos pos, float progress, boolean completed) {
        this.level = level;
        this.pos = pos;
        this.progress = Math.min(1.0f, Math.max(0.0f, progress));  // Clamp to [0, 1]
        this.completed = completed;
    }

    /**
     * Get the server level where curing is occurring.
     *
     * @return The ServerLevel
     */
    public ServerLevel getLevel() {
        return level;
    }

    /**
     * Get the block position being cured.
     *
     * @return The BlockPos
     */
    public BlockPos getPos() {
        return pos;
    }

    /**
     * Get the curing progress as a normalized fraction.
     *
     * @return Curing progress from 0.0 (not started) to 1.0 (fully cured)
     */
    public float getProgress() {
        return progress;
    }

    /**
     * Check if curing has completed.
     *
     * @return true if curing progress >= 1.0, false otherwise
     */
    public boolean isCompleted() {
        return completed;
    }

    /**
     * Get the curing progress as a percentage.
     *
     * @return Curing progress from 0 to 100 percent
     */
    public int getProgressPercent() {
        return Math.round(progress * 100);
    }

    /**
     * Get the remaining curing progress needed.
     *
     * Useful for calculating remaining time estimates or displaying progress bars.
     *
     * @return Remaining progress from 1.0 to 0.0 (complementary to getProgress())
     */
    public float getRemaining() {
        return 1.0f - progress;
    }
}
