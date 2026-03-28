package com.blockreality.api.client.render.animation;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 緩動函數庫 — GeckoLib 風格動畫插值。
 *
 * 提供 30+ 種緩動函數，涵蓋：
 *   - 線性、二次、三次、四次、五次
 *   - 正弦、指數、圓形、彈性、回彈、反彈
 *   - 每種含 easeIn / easeOut / easeInOut 三個變體
 *
 * 所有函數接受 t ∈ [0,1]，回傳 [0,1]。
 * 無分配、無分支預測失敗 — 純數學運算。
 */
@OnlyIn(Dist.CLIENT)
public final class EasingFunctions {
    private EasingFunctions() {}

    // ═══════════════════════════════════════════════════════
    //  枚舉（方便動畫 clip 引用）
    // ═══════════════════════════════════════════════════════

    public enum Type {
        LINEAR,
        QUAD_IN, QUAD_OUT, QUAD_IN_OUT,
        CUBIC_IN, CUBIC_OUT, CUBIC_IN_OUT,
        QUART_IN, QUART_OUT, QUART_IN_OUT,
        QUINT_IN, QUINT_OUT, QUINT_IN_OUT,
        SINE_IN, SINE_OUT, SINE_IN_OUT,
        EXPO_IN, EXPO_OUT, EXPO_IN_OUT,
        CIRC_IN, CIRC_OUT, CIRC_IN_OUT,
        ELASTIC_IN, ELASTIC_OUT, ELASTIC_IN_OUT,
        BACK_IN, BACK_OUT, BACK_IN_OUT,
        BOUNCE_IN, BOUNCE_OUT, BOUNCE_IN_OUT,
        CATMULL_ROM
    }

    /**
     * 依枚舉計算緩動值。
     */
    public static float apply(Type type, float t) {
        return switch (type) {
            case LINEAR       -> t;
            case QUAD_IN      -> quadIn(t);
            case QUAD_OUT     -> quadOut(t);
            case QUAD_IN_OUT  -> quadInOut(t);
            case CUBIC_IN     -> cubicIn(t);
            case CUBIC_OUT    -> cubicOut(t);
            case CUBIC_IN_OUT -> cubicInOut(t);
            case QUART_IN     -> quartIn(t);
            case QUART_OUT    -> quartOut(t);
            case QUART_IN_OUT -> quartInOut(t);
            case QUINT_IN     -> quintIn(t);
            case QUINT_OUT    -> quintOut(t);
            case QUINT_IN_OUT -> quintInOut(t);
            case SINE_IN      -> sineIn(t);
            case SINE_OUT     -> sineOut(t);
            case SINE_IN_OUT  -> sineInOut(t);
            case EXPO_IN      -> expoIn(t);
            case EXPO_OUT     -> expoOut(t);
            case EXPO_IN_OUT  -> expoInOut(t);
            case CIRC_IN      -> circIn(t);
            case CIRC_OUT     -> circOut(t);
            case CIRC_IN_OUT  -> circInOut(t);
            case ELASTIC_IN   -> elasticIn(t);
            case ELASTIC_OUT  -> elasticOut(t);
            case ELASTIC_IN_OUT -> elasticInOut(t);
            case BACK_IN      -> backIn(t);
            case BACK_OUT     -> backOut(t);
            case BACK_IN_OUT  -> backInOut(t);
            case BOUNCE_IN    -> bounceIn(t);
            case BOUNCE_OUT   -> bounceOut(t);
            case BOUNCE_IN_OUT -> bounceInOut(t);
            case CATMULL_ROM  -> t; // 需要額外控制點，此處退化為線性
        };
    }

    // ─── 基礎函數 ──────────────────────────────────────

    public static float quadIn(float t) { return t * t; }
    public static float quadOut(float t) { return t * (2 - t); }
    public static float quadInOut(float t) { return t < 0.5f ? 2 * t * t : -1 + (4 - 2 * t) * t; }

    public static float cubicIn(float t) { return t * t * t; }
    public static float cubicOut(float t) { float u = t - 1; return u * u * u + 1; }
    public static float cubicInOut(float t) { return t < 0.5f ? 4 * t * t * t : (t - 1) * (2 * t - 2) * (2 * t - 2) + 1; }

    public static float quartIn(float t) { return t * t * t * t; }
    public static float quartOut(float t) { float u = t - 1; return 1 - u * u * u * u; }
    public static float quartInOut(float t) { float u = t - 1; return t < 0.5f ? 8 * t * t * t * t : 1 - 8 * u * u * u * u; }

    public static float quintIn(float t) { return t * t * t * t * t; }
    public static float quintOut(float t) { float u = t - 1; return 1 + u * u * u * u * u; }
    public static float quintInOut(float t) { float u = t - 1; return t < 0.5f ? 16 * t * t * t * t * t : 1 + 16 * u * u * u * u * u; }

    public static float sineIn(float t) { return 1 - (float) Math.cos(t * Math.PI / 2); }
    public static float sineOut(float t) { return (float) Math.sin(t * Math.PI / 2); }
    public static float sineInOut(float t) { return 0.5f * (1 - (float) Math.cos(Math.PI * t)); }

    public static float expoIn(float t) { return t == 0 ? 0 : (float) Math.pow(2, 10 * (t - 1)); }
    public static float expoOut(float t) { return t == 1 ? 1 : 1 - (float) Math.pow(2, -10 * t); }
    public static float expoInOut(float t) {
        if (t == 0) return 0;
        if (t == 1) return 1;
        return t < 0.5f
            ? 0.5f * (float) Math.pow(2, 20 * t - 10)
            : 1 - 0.5f * (float) Math.pow(2, -20 * t + 10);
    }

    public static float circIn(float t) { return 1 - (float) Math.sqrt(1 - t * t); }
    public static float circOut(float t) { float u = t - 1; return (float) Math.sqrt(1 - u * u); }
    public static float circInOut(float t) {
        return t < 0.5f
            ? 0.5f * (1 - (float) Math.sqrt(1 - 4 * t * t))
            : 0.5f * ((float) Math.sqrt(1 - (2 * t - 2) * (2 * t - 2)) + 1);
    }

    public static float elasticIn(float t) {
        if (t == 0 || t == 1) return t;
        return -(float)(Math.pow(2, 10 * (t - 1)) * Math.sin((t - 1.1) * 5 * Math.PI));
    }
    public static float elasticOut(float t) {
        if (t == 0 || t == 1) return t;
        return (float)(Math.pow(2, -10 * t) * Math.sin((t - 0.1) * 5 * Math.PI)) + 1;
    }
    public static float elasticInOut(float t) {
        if (t == 0 || t == 1) return t;
        t *= 2;
        if (t < 1) return -0.5f * (float)(Math.pow(2, 10 * (t - 1)) * Math.sin((t - 1.1) * 5 * Math.PI));
        return 0.5f * (float)(Math.pow(2, -10 * (t - 1)) * Math.sin((t - 1.1) * 5 * Math.PI)) + 1;
    }

    private static final float BACK_S = 1.70158f;
    public static float backIn(float t) { return t * t * ((BACK_S + 1) * t - BACK_S); }
    public static float backOut(float t) { float u = t - 1; return u * u * ((BACK_S + 1) * u + BACK_S) + 1; }
    public static float backInOut(float t) {
        float s = BACK_S * 1.525f;
        t *= 2;
        if (t < 1) return 0.5f * (t * t * ((s + 1) * t - s));
        t -= 2;
        return 0.5f * (t * t * ((s + 1) * t + s) + 2);
    }

    public static float bounceOut(float t) {
        if (t < 1 / 2.75f)    return 7.5625f * t * t;
        if (t < 2 / 2.75f)    { t -= 1.5f / 2.75f; return 7.5625f * t * t + 0.75f; }
        if (t < 2.5f / 2.75f) { t -= 2.25f / 2.75f; return 7.5625f * t * t + 0.9375f; }
        t -= 2.625f / 2.75f;   return 7.5625f * t * t + 0.984375f;
    }
    public static float bounceIn(float t) { return 1 - bounceOut(1 - t); }
    public static float bounceInOut(float t) {
        return t < 0.5f ? bounceIn(t * 2) * 0.5f : bounceOut(t * 2 - 1) * 0.5f + 0.5f;
    }

    /**
     * Catmull-Rom 樣條插值（需要 4 個控制點）。
     */
    public static float catmullRom(float t, float p0, float p1, float p2, float p3) {
        float t2 = t * t;
        float t3 = t2 * t;
        return 0.5f * (
            (2 * p1) +
            (-p0 + p2) * t +
            (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 +
            (-p0 + 3 * p1 - 3 * p2 + p3) * t3
        );
    }
}
