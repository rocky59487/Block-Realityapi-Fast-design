package com.blockreality.api;

/**
 * Centralized message prefix constants for command feedback and logging.
 * Uses Minecraft color codes: §c=red, §6=gold, §a=green, §e=yellow, §b=blue, §r=reset
 */
public final class MessageConstants {
    private MessageConstants() {}

    // ═══════════════════════════════════════════════════════
    //  Command prefixes
    // ═══════════════════════════════════════════════════════

    /** Block Reality main prefix (red) */
    public static final String PREFIX_BR = "§c[BR] §r";

    /** Fast Design prefix (gold) */
    public static final String PREFIX_FD = "§6[FD] §r";

    /** Construction / BR-CI prefix (cyan) */
    public static final String PREFIX_CI = "§b[CI] §r";

    // ═══════════════════════════════════════════════════════
    //  Status message prefixes
    // ═══════════════════════════════════════════════════════

    /** Error message prefix (red) */
    public static final String PREFIX_ERROR = "§c";

    /** Success message prefix (green) */
    public static final String PREFIX_SUCCESS = "§a";

    /** Warning message prefix (yellow) */
    public static final String PREFIX_WARNING = "§e";

    /** Information message prefix (gray) */
    public static final String PREFIX_INFO = "§7";

    // ═══════════════════════════════════════════════════════
    //  Common error messages
    // ═══════════════════════════════════════════════════════

    public static final String ERROR_REQUIRES_PLAYER = "This command requires a player";
    public static final String ERROR_SET_POSITIONS = "Set pos1 and pos2 first!";
    public static final String ERROR_SELECTION_TOO_LARGE = "Selection too large!";
}
