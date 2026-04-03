package com.blockreality.fastdesign.client.node;

import com.blockreality.api.chisel.SubBlockShape;
import com.blockreality.api.material.RMaterial;
import net.minecraft.nbt.CompoundTag;

/**
 * 節點端口型別系統 — 設計報告 §3.2
 *
 * 14 種型別，每種型別定義：
 *   - Java 承載型別
 *   - 線色（用於畫布渲染）
 *   - 預設值
 *
 * 型別轉換規則見 {@link TypeChecker}。
 */
public enum PortType {

    // ─── 基本數值 ───
    FLOAT  (Float.class,           0xFFCCCCCC, 0.0f,      "float",    "浮點數"),
    INT    (Integer.class,         0xFF88BBFF, 0,         "int",      "整數"),
    BOOL   (Boolean.class,         0xFFFFCC00, false,     "bool",     "布林"),

    // ─── 向量 ───
    VEC2   (float[].class,         0xFFCC88FF, new float[]{0, 0},                "vec2", "2D 向量"),
    VEC3   (float[].class,         0xFF00CCCC, new float[]{0, 0, 0},             "vec3", "3D 向量"),
    VEC4   (float[].class,         0xFFFF88CC, new float[]{0, 0, 0, 0},          "vec4", "4D 向量"),

    // ─── 顏色 ───
    COLOR  (Integer.class,         0xFFFF0000, 0xFFFFFFFF, "color",   "顏色"),

    // ─── 複合物件 ───
    MATERIAL(RMaterial.class,      0xFF44CC44, null,       "material", "材料"),
    BLOCK   (BRBlockDef.class,     0xFF00CC88, null,       "block",    "方塊定義"),
    SHAPE   (SubBlockShape.class,  0xFFAA7744, null,       "shape",    "方塊形狀"),
    TEXTURE (Integer.class,        0xFFFF6644, 0,          "texture",  "紋理"),
    ENUM    (Enum.class,           0xFFFFFFFF, null,       "enum",     "枚舉"),
    CURVE   (float[].class,        0xFF8844CC, new float[0], "curve",  "曲線"),
    STRUCT  (CompoundTag.class,    0xFFAAAAAA, null,       "struct",   "複合資料");

    private final Class<?> javaType;
    private final int wireColor;
    private final Object defaultValue;
    private final String serializedName;
    private final String displayNameCN;

    PortType(Class<?> javaType, int wireColor, Object defaultValue,
             String serializedName, String displayNameCN) {
        this.javaType = javaType;
        this.wireColor = wireColor;
        this.defaultValue = defaultValue;
        this.serializedName = serializedName;
        this.displayNameCN = displayNameCN;
    }

    public Class<?> javaType()       { return javaType; }
    public int wireColor()           { return wireColor; }
    public Object defaultValue()     { return defaultValue; }
    public String serializedName()   { return serializedName; }
    public String displayNameCN()    { return displayNameCN; }

    /**
     * 取得線色的 ARGB 分量（畫布渲染用）。
     */
    public float wireAlpha() { return ((wireColor >> 24) & 0xFF) / 255.0f; }
    public float wireRed()   { return ((wireColor >> 16) & 0xFF) / 255.0f; }
    public float wireGreen() { return ((wireColor >>  8) & 0xFF) / 255.0f; }
    public float wireBlue()  { return ((wireColor)       & 0xFF) / 255.0f; }

    /**
     * 是否為數值型別（可參與算術轉換）。
     */
    public boolean isNumeric() {
        return this == FLOAT || this == INT || this == BOOL;
    }

    /**
     * 是否為向量型別。
     */
    public boolean isVector() {
        return this == VEC2 || this == VEC3 || this == VEC4;
    }

    /**
     * 從序列化名稱反查枚舉。
     */
    public static PortType fromSerializedName(String name) {
        for (PortType pt : values()) {
            if (pt.serializedName.equals(name)) return pt;
        }
        throw new IllegalArgumentException("Unknown port type: " + name);
    }

    /**
     * ★ Audit fix (API 設計師): 統一型別相容性規則。
     * 與 {@code com.blockreality.api.node.PortType#canConnectTo} 共享相同的轉換矩陣。
     *
     * <p>允許同型別連接和以下自動轉換：
     * <ul>
     *   <li>FLOAT ↔ INT（數值截斷/擴展）</li>
     *   <li>BOOL → INT / FLOAT（true=1, false=0）</li>
     *   <li>VEC3 ↔ COLOR（RGB 映射）</li>
     *   <li>BLOCK → MATERIAL（提取方塊材料屬性）</li>
     * </ul>
     */
    public boolean canConnectTo(PortType target) {
        if (this == target) return true;
        if (this == FLOAT && target == INT) return true;
        if (this == INT && target == FLOAT) return true;
        if (this == BOOL && (target == INT || target == FLOAT)) return true;
        if (this == VEC3 && target == COLOR) return true;
        if (this == COLOR && target == VEC3) return true;
        if (this == BLOCK && target == MATERIAL) return true;
        return false;
    }

    /**
     * 將此 PortType 映射到 API 基礎層的 PortType。
     * 用於 BRNode 核心圖引擎與 FastDesign 之間的橋接。
     */
    public com.blockreality.api.node.PortType toApiPortType() {
        return com.blockreality.api.node.PortType.valueOf(this.name());
    }
}
