package com.blockreality.api.event;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.eventbus.api.Event;

/**
 * Load Path Changed Event — Fired when a block's load changes due to propagation.
 *
 * This event is posted on the FORGE event bus whenever a RBlockEntity's
 * load is updated during propagateLoadDown() in LoadPathEngine.
 *
 * Modules can listen to this event to monitor structural load distribution,
 * update stress visualizations, or trigger structural analysis updates.
 *
 * Module use case: Fast Design can listen to this event to dynamically
 * update load visualizations as structures are modified.
 */
public class LoadPathChangedEvent extends Event {

    private final ServerLevel level;
    private final BlockPos pos;
    private final float oldLoad;
    private final float newLoad;

    /**
     * Construct a load path changed event.
     *
     * @param level The server level where the load changed
     * @param pos The block position
     * @param oldLoad The previous load value
     * @param newLoad The new load value
     */
    public LoadPathChangedEvent(ServerLevel level, BlockPos pos, float oldLoad, float newLoad) {
        this.level = level;
        this.pos = pos;
        this.oldLoad = oldLoad;
        this.newLoad = newLoad;
    }

    /**
     * Get the server level where the load changed.
     *
     * @return The ServerLevel
     */
    public ServerLevel getLevel() {
        return level;
    }

    /**
     * Get the block position where the load changed.
     *
     * @return The BlockPos
     */
    public BlockPos getPos() {
        return pos;
    }

    /**
     * Get the previous load value before this update.
     *
     * @return The old load value
     */
    public float getOldLoad() {
        return oldLoad;
    }

    /**
     * Get the new load value after this update.
     *
     * @return The new load value
     */
    public float getNewLoad() {
        return newLoad;
    }

    /**
     * Get the load delta (change in load).
     *
     * @return newLoad - oldLoad. Positive indicates load increase, negative indicates decrease.
     */
    public float getDelta() {
        return newLoad - oldLoad;
    }

    /**
     * Get the load ratio (new load relative to old load).
     *
     * Useful for determining the percentage change in load.
     * Returns 1.0 if oldLoad is 0 or less to avoid division by zero.
     *
     * @return newLoad / oldLoad, or 1.0 if oldLoad <= 0
     */
    public float getRatio() {
        if (oldLoad <= 0) return 1.0f;
        return newLoad / oldLoad;
    }
}
