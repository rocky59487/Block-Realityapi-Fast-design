package com.blockreality.api.placement;

/**
 * Build Mode — state machine for multi-block placement patterns.
 *
 * Each mode defines how two (or more) anchor points translate into
 * a set of world positions for block placement.
 *
 * NORMAL   — vanilla single-block placement (pass-through)
 * LINE     — straight line between pos1 → pos2 (Bresenham-like)
 * WALL     — 2D rectangular plane spanned by pos1 + pos2
 * CUBE     — 3D filled cuboid (identical to /fd fill)
 * MIRROR_X — real-time mirror across YZ plane at mirror anchor
 * MIRROR_Z — real-time mirror across XY plane at mirror anchor
 */
public enum BuildMode {

    NORMAL("Normal", "single block"),
    LINE("Line", "pos1 to pos2"),
    WALL("Wall", "2D rectangle"),
    CUBE("Cube", "3D cuboid"),
    MIRROR_X("Mirror X", "mirror across X axis"),
    MIRROR_Z("Mirror Z", "mirror across Z axis");

    private final String displayName;
    private final String description;

    BuildMode(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    /** Cycle to the next mode (wraps around). */
    public BuildMode next() {
        BuildMode[] vals = values();
        return vals[(ordinal() + 1) % vals.length];
    }

    /** Cycle to the previous mode (wraps around). */
    public BuildMode prev() {
        BuildMode[] vals = values();
        return vals[(ordinal() - 1 + vals.length) % vals.length];
    }
}
