package com.blockreality.api.node;

/**
 * A port on a node (input or output). Input ports accept at most one incoming wire;
 * output ports can fan out to many wires (managed by NodeGraph).
 */
public class NodePort {
    private final String name;
    private final PortType type;
    private final boolean isInput;
    private final BRNode owner;
    private Object value;
    private Object defaultValue;
    private Wire connectedWire;   // for input ports (single wire); outputs track wires via NodeGraph
    private float min;
    private float max;
    private boolean hasRange;

    public NodePort(String name, PortType type, boolean isInput, BRNode owner, Object defaultValue) {
        this.name = name;
        this.type = type;
        this.isInput = isInput;
        this.owner = owner;
        this.defaultValue = defaultValue;
        this.value = defaultValue;
    }

    // ---- Value access ----

    /**
     * Set the port value and mark the owning node dirty.
     * Used for INPUT ports (user/binder changes trigger re-evaluation).
     */
    public void setValue(Object value) {
        this.value = value;
        if (owner != null) {
            owner.markDirty();
        }
    }

    /**
     * ★ review-fix ICReM-6: 直接設值不觸發 markDirty。
     * 用於 OUTPUT ports — 節點的 evaluate() 寫入輸出值時不應把自己重新標 dirty，
     * 否則會造成無限重新評估循環。
     */
    public void setValueDirect(Object value) {
        this.value = value;
    }

    /**
     * Returns the effective value of this port. For a connected input port the
     * value is pulled from the source end of the wire (with auto-conversion);
     * otherwise the port's own value is returned.
     */
    public Object getValue() {
        if (isInput && connectedWire != null) {
            return connectedWire.getSourceValue();
        }
        return value;
    }

    public Object getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(Object defaultValue) {
        this.defaultValue = defaultValue;
    }

    // ---- Connection ----

    public boolean isConnected() {
        return connectedWire != null;
    }

    public void setConnectedWire(Wire wire) {
        this.connectedWire = wire;
    }

    public Wire getConnectedWire() {
        return connectedWire;
    }

    /**
     * Disconnect the wire attached to this port (input side).
     */
    public void disconnect() {
        this.connectedWire = null;
    }

    // ---- Range (for FLOAT / INT slider UI) ----

    public void setRange(float min, float max) {
        this.min = min;
        this.max = max;
        this.hasRange = true;
    }

    public float getMin() {
        return min;
    }

    public float getMax() {
        return max;
    }

    public boolean hasRange() {
        return hasRange;
    }

    // ---- Identity ----

    public String getName() {
        return name;
    }

    public PortType getType() {
        return type;
    }

    public boolean isInput() {
        return isInput;
    }

    public BRNode getOwner() {
        return owner;
    }

    @Override
    public String toString() {
        return (isInput ? "IN" : "OUT") + "[" + name + ":" + type.id + "]";
    }
}
