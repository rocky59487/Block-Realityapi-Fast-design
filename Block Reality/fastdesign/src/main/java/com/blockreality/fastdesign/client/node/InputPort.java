package com.blockreality.fastdesign.client.node;

import javax.annotation.Nullable;

/**
 * 輸入端口 — 設計報告 §3.1
 *
 * 規則：
 *   - 最多接受一條 Wire（多對一：最後連線覆蓋之前的）
 *   - 斷開連線後恢復為預設值
 *   - 可有內嵌控件（滑桿、勾選框等）用於手動設值
 */
public class InputPort extends NodePort {

    @Nullable private Wire incomingWire;
    private final Object defaultValue;

    // 數值參數的範圍限制（用於 InlineSlider）
    private float min = Float.NEGATIVE_INFINITY;
    private float max = Float.POSITIVE_INFINITY;
    private float step = 0.01f;

    // 是否為必填連接
    private boolean required = false;

    public InputPort(String name, String displayName, PortType type, @Nullable Object defaultValue) {
        super(name, displayName, type, defaultValue);
        this.defaultValue = defaultValue != null ? defaultValue : type.defaultValue();
    }

    public InputPort(String name, PortType type) {
        this(name, name, type, null);
    }

    public InputPort(String name, PortType type, Object defaultValue) {
        this(name, name, type, defaultValue);
    }

    // ─── 範圍設定（鏈式）───

    public InputPort range(float min, float max) {
        this.min = min;
        this.max = max;
        return this;
    }

    public InputPort step(float step) {
        this.step = step;
        return this;
    }

    public InputPort setRequired(boolean required) {
        this.required = required;
        return this;
    }

    // ─── 連線管理 ───

    @Nullable
    public Wire incomingWire() { return incomingWire; }

    public boolean isConnected() { return incomingWire != null; }

    /**
     * 設定連入的 Wire（由 Wire.connect 呼叫）。
     * 如果已有連線，先斷開舊連線。
     */
    void setIncomingWire(@Nullable Wire wire) {
        if (this.incomingWire != null && wire != null && this.incomingWire != wire) {
            this.incomingWire.disconnect();
        }
        this.incomingWire = wire;
    }

    // ─── 值存取 ───

    /**
     * 取得當前有效值：
     *   - 若已連線 → 取上游輸出端口的值
     *   - 若未連線 → 取本地設定值
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T getValue() {
        if (incomingWire != null) {
            Object upstream = incomingWire.from().getRawValue();
            return (T) TypeChecker.convert(upstream, incomingWire.from().type(), this.type());
        }
        return (T) value;
    }

    @Override
    public Object getRawValue() {
        if (incomingWire != null) {
            Object upstream = incomingWire.from().getRawValue();
            return TypeChecker.convert(upstream, incomingWire.from().type(), this.type());
        }
        return value;
    }

    /**
     * 手動設定本地值（來自 UI 控件），標記擁有者節點為髒。
     */
    public void setLocalValue(Object newValue) {
        if (newValue == null) newValue = defaultValue;
        // 數值裁切
        if (type().isNumeric() && newValue instanceof Number n) {
            float v = n.floatValue();
            v = Math.max(min, Math.min(max, v));
            newValue = type() == PortType.INT ? (Object) Math.round(v)
                     : type() == PortType.FLOAT ? (Object) v
                     : newValue;
        }
        this.value = newValue;
        if (owner() != null) {
            owner().markDirty();
        }
    }

    /**
     * 重置為預設值。
     */
    public void resetToDefault() {
        this.value = defaultValue;
        if (owner() != null) {
            owner().markDirty();
        }
    }

    // ─── 屬性 ───

    public Object defaultValue() { return defaultValue; }
    public float min()           { return min; }
    public float max()           { return max; }
    public float step()          { return step; }
    public boolean isRequired()  { return required; }
}
