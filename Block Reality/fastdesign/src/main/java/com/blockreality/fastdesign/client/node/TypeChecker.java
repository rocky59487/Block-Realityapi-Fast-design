package com.blockreality.fastdesign.client.node;

import javax.annotation.Nullable;

/**
 * 型別相容性檢查 + 自動轉換 — 設計報告 §3.3 + §14.1
 *
 * 實作 14×14 相容矩陣和轉換函數。
 */
public final class TypeChecker {
    private TypeChecker() {}

    /**
     * 14×14 合法連接矩陣。
     * compatible[from.ordinal()][to.ordinal()] = true 表示可直接或自動轉換連接。
     */
    private static final boolean[][] COMPATIBLE = new boolean[PortType.values().length][PortType.values().length];

    static {
        // 同型別一律可連
        for (PortType pt : PortType.values()) {
            COMPATIBLE[pt.ordinal()][pt.ordinal()] = true;
        }

        // FLOAT → INT（四捨五入）
        allow(PortType.FLOAT, PortType.INT);
        // INT → FLOAT（自動提升）
        allow(PortType.INT, PortType.FLOAT);
        // INT → BOOL
        allow(PortType.INT, PortType.BOOL);
        // BOOL → INT（0/1）
        allow(PortType.BOOL, PortType.INT);
        // BOOL → FLOAT（0.0/1.0）
        allow(PortType.BOOL, PortType.FLOAT);
        // VEC3 → COLOR（RGB→ARGB, alpha=1）
        allow(PortType.VEC3, PortType.COLOR);
        // COLOR → VEC3（ARGB→RGB）
        allow(PortType.COLOR, PortType.VEC3);
        // BLOCK → MATERIAL（自動拆出 RMaterial）v1.1
        allow(PortType.BLOCK, PortType.MATERIAL);
        // ENUM → INT（ordinal）
        allow(PortType.ENUM, PortType.INT);
    }

    private static void allow(PortType from, PortType to) {
        COMPATIBLE[from.ordinal()][to.ordinal()] = true;
    }

    /**
     * 檢查是否可從 from 型別連接到 to 型別。
     */
    public static boolean canConnect(PortType from, PortType to) {
        // STRUCT 需要 schema 相容 — 這裡寬鬆允許
        if (from == PortType.STRUCT && to == PortType.STRUCT) return true;
        return COMPATIBLE[from.ordinal()][to.ordinal()];
    }

    /**
     * 將值從 fromType 轉換為 toType。
     * 若型別相同，直接回傳。
     * 若不相容，回傳 toType 的預設值。
     */
    @Nullable
    public static Object convert(@Nullable Object value, PortType fromType, PortType toType) {
        if (value == null) return toType.defaultValue();
        if (fromType == toType) return value;

        // ─── 數值轉換 ───
        if (fromType == PortType.FLOAT && toType == PortType.INT) {
            return Math.round(((Number) value).floatValue());
        }
        if (fromType == PortType.INT && toType == PortType.FLOAT) {
            return ((Number) value).floatValue();
        }
        if (fromType == PortType.BOOL && toType == PortType.INT) {
            return ((Boolean) value) ? 1 : 0;
        }
        if (fromType == PortType.BOOL && toType == PortType.FLOAT) {
            return ((Boolean) value) ? 1.0f : 0.0f;
        }
        if (fromType == PortType.INT && toType == PortType.BOOL) {
            return ((Number) value).intValue() != 0;
        }

        // ─── VEC3 ↔ COLOR ───
        if (fromType == PortType.VEC3 && toType == PortType.COLOR) {
            float[] v = (float[]) value;
            if (v.length < 3) return 0xFFFFFFFF;
            int r = clamp255(v[0]);
            int g = clamp255(v[1]);
            int b = clamp255(v[2]);
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        if (fromType == PortType.COLOR && toType == PortType.VEC3) {
            int c = (Integer) value;
            return new float[]{
                    ((c >> 16) & 0xFF) / 255.0f,
                    ((c >>  8) & 0xFF) / 255.0f,
                    ((c)       & 0xFF) / 255.0f
            };
        }

        // ─── BLOCK → MATERIAL ───
        if (fromType == PortType.BLOCK && toType == PortType.MATERIAL) {
            if (value instanceof BRBlockDef def) {
                return def.material();
            }
            return null;
        }

        // ─── ENUM → INT ───
        if (fromType == PortType.ENUM && toType == PortType.INT) {
            if (value instanceof Enum<?> e) return e.ordinal();
            return 0;
        }

        // 不相容 — 回傳預設值
        return toType.defaultValue();
    }

    /**
     * 取得自動轉換的顯示標記（畫布連線上顯示）。
     * 回傳 null 表示同型別，無需標記。
     */
    @Nullable
    public static String conversionLabel(PortType from, PortType to) {
        if (from == to) return null;
        if (from == PortType.FLOAT && to == PortType.INT) return "\u2248"; // ≈
        if (from == PortType.INT && to == PortType.FLOAT) return "\u2191"; // ↑
        if (from == PortType.BOOL && (to == PortType.INT || to == PortType.FLOAT)) return "0/1";
        if (from == PortType.VEC3 && to == PortType.COLOR) return "RGB";
        if (from == PortType.COLOR && to == PortType.VEC3) return "RGB";
        if (from == PortType.BLOCK && to == PortType.MATERIAL) return "unwrap";
        if (from == PortType.ENUM && to == PortType.INT) return "ord";
        return "?";
    }

    private static int clamp255(float v) {
        return Math.max(0, Math.min(255, Math.round(v * 255)));
    }
}
