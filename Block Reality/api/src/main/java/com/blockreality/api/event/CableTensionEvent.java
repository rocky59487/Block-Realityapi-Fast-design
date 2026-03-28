package com.blockreality.api.event;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.eventbus.api.Event;

/**
 * Cable Tension Event — Fired when a cable's tension changes significantly.
 *
 * 纜索張力事件 — 當纜索的張力發生顯著變化時觸發。
 *
 * This event is posted on the Forge event bus whenever a cable element's tension
 * undergoes a notable state change (e.g., transitions from slack to taut, or breaks).
 *
 * Modules can listen to this event to:
 * - Trigger visual effects (red glow, particle effects) when cables are under stress
 * - Update monitoring systems to show stress levels
 * - Perform callbacks when cables break
 * - Play sounds or animations
 *
 * Module use case: A construction analysis tool could listen for high-tension cables
 * and highlight them in red, or trigger warning sounds when cables approach failure.
 */
public class CableTensionEvent extends Event {

    private final ServerLevel level;
    private final BlockPos from;
    private final BlockPos to;
    private final float oldTension;
    private final float newTension;
    private final boolean broken;

    /**
     * Construct a cable tension event.
     *
     * 構造纜索張力事件。
     *
     * @param level      The server level where the cable is located
     * @param from       Starting endpoint of the cable
     * @param to         Ending endpoint of the cable
     * @param oldTension Previous tension value in Newtons
     * @param newTension New tension value in Newtons
     * @param broken     Whether the cable broke in this update (true = broken, false = tension changed)
     */
    public CableTensionEvent(ServerLevel level, BlockPos from, BlockPos to,
                             float oldTension, float newTension, boolean broken) {
        this.level = level;
        this.from = from;
        this.to = to;
        this.oldTension = oldTension;
        this.newTension = newTension;
        this.broken = broken;
    }

    /**
     * Get the server level where the cable is located.
     *
     * @return The ServerLevel
     */
    public ServerLevel getLevel() {
        return level;
    }

    /**
     * Get the starting endpoint of the cable.
     *
     * @return The BlockPos of the cable's starting point
     */
    public BlockPos getFrom() {
        return from;
    }

    /**
     * Get the ending endpoint of the cable.
     *
     * @return The BlockPos of the cable's ending point
     */
    public BlockPos getTo() {
        return to;
    }

    /**
     * Get the previous tension value.
     *
     * @return The old tension in Newtons
     */
    public float getOldTension() {
        return oldTension;
    }

    /**
     * Get the new tension value.
     *
     * @return The new tension in Newtons
     */
    public float getNewTension() {
        return newTension;
    }

    /**
     * Get the tension delta (change in tension).
     *
     * 獲得張力變化（張力變化量）。
     *
     * @return newTension - oldTension. Positive = increased, negative = decreased
     */
    public float getTensionDelta() {
        return newTension - oldTension;
    }

    /**
     * Check if the cable broke during this update.
     *
     * 檢查纜索是否在此更新期間斷裂。
     *
     * When true, this event signals a cable failure. The cable should be removed
     * from the manager after this event is processed.
     *
     * @return true if the cable broke (utilization > 1.0), false if just tension changed
     */
    public boolean isBroken() {
        return broken;
    }

    /**
     * Check if the cable transitioned from slack to taut.
     *
     * 檢查纜索是否從鬆弛狀態轉變為緊張狀態。
     *
     * Useful for triggering animations or sound effects when cables "snap taut".
     *
     * @return true if oldTension ≈ 0 and newTension > some threshold (e.g., > 10N)
     */
    public boolean becomeTaut() {
        return oldTension < 10.0f && newTension >= 10.0f;
    }

    /**
     * Check if the cable is now slack (no tension).
     *
     * 檢查纜索現在是否鬆弛（無張力）。
     *
     * @return true if newTension ≤ 0
     */
    public boolean isNowSlack() {
        return newTension <= 0.0f;
    }

    @Override
    public String toString() {
        return String.format(
            "CableTensionEvent[%s→%s, T: %.1fN→%.1fN, broken=%s]",
            from.toShortString(),
            to.toShortString(),
            oldTension,
            newTension,
            broken
        );
    }
}
