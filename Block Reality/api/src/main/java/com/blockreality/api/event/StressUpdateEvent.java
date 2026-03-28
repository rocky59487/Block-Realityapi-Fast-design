package com.blockreality.api.event;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.eventbus.api.Event;

/**
 * Stress Update Event — Fired when a block's stress level changes.
 *
 * This event is posted on the FORGE event bus whenever a RBlockEntity's
 * stress level is updated via propagateLoadDown() in LoadPathEngine.
 *
 * Modules can listen to this event to trigger visual effects, update
 * monitoring systems, or perform stress-dependent calculations.
 *
 * Module use case: Construction Intern can listen for stress exceeding
 * thresholds to trigger reinforcement suggestions.
 */
public class StressUpdateEvent extends Event {

    private final ServerLevel level;
    private final BlockPos pos;
    private final float oldStress;
    private final float newStress;

    /**
     * Construct a stress update event.
     *
     * @param level The server level where the stress change occurred
     * @param pos The block position
     * @param oldStress The previous stress value (0.0 to 1.0)
     * @param newStress The new stress value (0.0 to 1.0)
     */
    public StressUpdateEvent(ServerLevel level, BlockPos pos, float oldStress, float newStress) {
        this.level = level;
        this.pos = pos;
        this.oldStress = oldStress;
        this.newStress = newStress;
    }

    /**
     * Get the server level where the stress change occurred.
     *
     * @return The ServerLevel
     */
    public ServerLevel getLevel() {
        return level;
    }

    /**
     * Get the block position where the stress changed.
     *
     * @return The BlockPos
     */
    public BlockPos getPos() {
        return pos;
    }

    /**
     * Get the previous stress value before this update.
     *
     * @return The old stress value (0.0 to 1.0)
     */
    public float getOldStress() {
        return oldStress;
    }

    /**
     * Get the new stress value after this update.
     *
     * @return The new stress value (0.0 to 1.0)
     */
    public float getNewStress() {
        return newStress;
    }

    /**
     * Check if stress crossed an upward threshold during this update.
     *
     * Useful for triggering events when stress reaches critical levels.
     *
     * @param threshold The threshold value (0.0 to 1.0)
     * @return true if oldStress < threshold <= newStress
     */
    public boolean crossedThreshold(float threshold) {
        return oldStress < threshold && threshold <= newStress;
    }

    /**
     * Get the stress delta (change in stress).
     *
     * @return newStress - oldStress. Positive indicates stress increase, negative indicates decrease.
     */
    public float getDelta() {
        return newStress - oldStress;
    }
}
