package com.blockreality.api.client;

/**
 * Rendering utility constants used across client-side renderers.
 * Centralizes magic numbers and color definitions to reduce duplication.
 */
public final class RenderUtils {
    private RenderUtils() {}

    // ═══════════════════════════════════════════════════════
    //  Anchor Path Renderer colors
    //  ★ R6-10 fix: 想法.docx 要求有效=綠色、無效=紅色
    // ═══════════════════════════════════════════════════════
    /** 有效錨定路徑 — 綠色 (想法.docx: "有效錨定=綠色") */
    public static final float ANCHOR_PATH_VALID_R = 0.2f;
    public static final float ANCHOR_PATH_VALID_G = 0.85f;
    public static final float ANCHOR_PATH_VALID_B = 0.2f;
    public static final float ANCHOR_PATH_VALID_A = 0.6f;

    /** 無效錨定路徑 — 紅色 (想法.docx: "未錨定=紅色") */
    public static final float ANCHOR_PATH_INVALID_R = 1.0f;
    public static final float ANCHOR_PATH_INVALID_G = 0.2f;
    public static final float ANCHOR_PATH_INVALID_B = 0.2f;
    public static final float ANCHOR_PATH_INVALID_A = 0.6f;

    /** @deprecated 使用 ANCHOR_PATH_VALID / ANCHOR_PATH_INVALID 替代 */
    @Deprecated
    public static final float ANCHOR_PATH_R = ANCHOR_PATH_VALID_R;
    @Deprecated
    public static final float ANCHOR_PATH_G = ANCHOR_PATH_VALID_G;
    @Deprecated
    public static final float ANCHOR_PATH_B = ANCHOR_PATH_VALID_B;
    @Deprecated
    public static final float ANCHOR_PATH_A = ANCHOR_PATH_VALID_A;

    // ═══════════════════════════════════════════════════════
    //  Render distance limits
    // ═══════════════════════════════════════════════════════
    public static final double MAX_RENDER_DIST_SQ = 64.0 * 64.0;

    // ═══════════════════════════════════════════════════════
    //  Stress Heatmap Renderer colors
    // ═══════════════════════════════════════════════════════
    // Low stress (blue)
    public static final int STRESS_COLOR_LOW_R = 0;
    public static final int STRESS_COLOR_LOW_G = 80;
    public static final int STRESS_COLOR_LOW_B = 255;

    // High stress (red)
    public static final int STRESS_COLOR_HIGH_R = 255;
    public static final int STRESS_COLOR_HIGH_G = 0;
    public static final int STRESS_COLOR_HIGH_B = 0;

    // ═══════════════════════════════════════════════════════
    //  Selection and block operation limits
    // ═══════════════════════════════════════════════════════
    public static final int MAX_SELECTION_VOLUME = 125_000;
}
