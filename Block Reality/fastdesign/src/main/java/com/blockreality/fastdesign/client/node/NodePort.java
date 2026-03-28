package com.blockreality.fastdesign.client.node;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * 節點端口基類 — 設計報告 §3.1
 *
 * 端口是節點的輸入/輸出接口，承載型別化的資料。
 * 每個端口屬於一個 {@link BRNode}，有名稱和 {@link PortType}。
 */
public abstract class NodePort {

    private final String name;
    private final String displayName;
    private final PortType type;
    private BRNode owner;
    protected Object value;

    protected NodePort(String name, String displayName, PortType type, @Nullable Object defaultValue) {
        this.name = Objects.requireNonNull(name, "port name");
        this.displayName = displayName != null ? displayName : name;
        this.type = Objects.requireNonNull(type, "port type");
        this.value = defaultValue != null ? defaultValue : type.defaultValue();
    }

    protected NodePort(String name, PortType type) {
        this(name, name, type, null);
    }

    // ─── 識別 ───

    public String name()        { return name; }
    public String displayName() { return displayName; }
    public PortType type()      { return type; }
    public BRNode owner()       { return owner; }

    /** 設定擁有者（由 BRNode 在 addInput/addOutput 時呼叫） */
    void setOwner(BRNode node) { this.owner = node; }

    // ─── 值存取 ───

    @SuppressWarnings("unchecked")
    public <T> T getValue() {
        return (T) value;
    }

    public Object getRawValue() {
        return value;
    }

    /** 取得 float 值（自動處理 INT→FLOAT, BOOL→FLOAT 轉換） */
    public float getFloat() {
        if (value instanceof Float f) return f;
        if (value instanceof Integer i) return i.floatValue();
        if (value instanceof Boolean b) return b ? 1.0f : 0.0f;
        if (value instanceof Number n) return n.floatValue();
        return 0.0f;
    }

    /** 取得 int 值（自動處理 FLOAT→INT, BOOL→INT 轉換） */
    public int getInt() {
        if (value instanceof Integer i) return i;
        if (value instanceof Float f) return Math.round(f);
        if (value instanceof Boolean b) return b ? 1 : 0;
        if (value instanceof Number n) return n.intValue();
        return 0;
    }

    /** 取得 boolean 值 */
    public boolean getBool() {
        if (value instanceof Boolean b) return b;
        if (value instanceof Integer i) return i != 0;
        if (value instanceof Float f) return f != 0.0f;
        return false;
    }

    /** 取得 VEC3 值 */
    public float[] getVec3() {
        if (value instanceof float[] arr && arr.length >= 3) return arr;
        return new float[]{0, 0, 0};
    }

    /** 取得 COLOR (ARGB int) 值 */
    public int getColor() {
        if (value instanceof Integer i) return i;
        if (value instanceof float[] arr && arr.length >= 3) {
            int r = (int) (arr[0] * 255) & 0xFF;
            int g = (int) (arr[1] * 255) & 0xFF;
            int b = (int) (arr[2] * 255) & 0xFF;
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return 0xFFFFFFFF;
    }

    // ─── 全限定名稱（序列化/連線用） ───

    /**
     * 回傳 "nodeId.portName" 格式的完整路徑。
     */
    public String qualifiedName() {
        return (owner != null ? owner.nodeId() : "?") + "." + name;
    }

    @Override
    public String toString() {
        return type.serializedName() + " " + name + "=" + value;
    }
}
