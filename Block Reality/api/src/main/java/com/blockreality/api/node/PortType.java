package com.blockreality.api.node;

/**
 * 節點端口型別定義（基礎層）。
 *
 * <p>★ Audit fix (API 設計師): 型別系統分裂修復。
 * 此為基礎層的精簡定義，僅包含型別相容性規則（{@link #canConnectTo}）。
 *
 * <p>兩個 enum 共享相同的 14 個型別和相容性矩陣。
 * 基礎層模組（BRNode 核心圖引擎）使用此版本以避免依賴 fastdesign。
 *
 * @see com.blockreality.api.node.BRNode
 */
public enum PortType {
    FLOAT(float.class, "FLOAT", 0xCCCCCC),
    INT(int.class, "INT", 0x88BBFF),
    BOOL(boolean.class, "BOOL", 0xFFCC00),
    VEC2(float[].class, "VEC2", 0xCC88FF),
    VEC3(float[].class, "VEC3", 0x00CCCC),
    VEC4(float[].class, "VEC4", 0xFF88CC),
    COLOR(int.class, "COLOR", 0xFFFFFF),
    MATERIAL(Object.class, "MATERIAL", 0x44CC44),
    BLOCK(Object.class, "BLOCK", 0x00CC88),
    SHAPE(Object.class, "SHAPE", 0xAA7744),
    TEXTURE(int.class, "TEXTURE", 0xFF6644),
    ENUM(Object.class, "ENUM", 0xFFFFFF),
    CURVE(float[].class, "CURVE", 0x8844CC),
    STRUCT(Object.class, "STRUCT", 0xAAAAAA);

    public final Class<?> javaType;
    public final String id;
    public final int wireColor;

    PortType(Class<?> t, String id, int c) {
        this.javaType = t;
        this.id = id;
        this.wireColor = c;
    }

    /**
     * Check whether this port type can connect to the given target type.
     * Allows same-type connections and a set of auto-conversions.
     */
    public boolean canConnectTo(PortType target) {
        if (this == target) return true;
        // Auto-conversions
        if (this == FLOAT && target == INT) return true;
        if (this == INT && target == FLOAT) return true;
        if (this == BOOL && (target == INT || target == FLOAT)) return true;
        if (this == VEC3 && target == COLOR) return true;
        if (this == COLOR && target == VEC3) return true;
        if (this == BLOCK && target == MATERIAL) return true;
        return false;
    }
}
