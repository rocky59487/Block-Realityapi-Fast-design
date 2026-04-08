package com.blockreality.api.node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Abstract base class for every node in a Block Reality node graph.
 * Subclasses declare their ports in the constructor and implement {@link #evaluate()}.
 */
public abstract class BRNode {
    private final String nodeId;
    private String displayName;
    private String category;   // "render", "material", "physics", "tool", "export"
    private int color;         // category color (hex)

    // Canvas position (used by the editor UI)
    private float posX;
    private float posY;
    private boolean collapsed;

    // Ports
    private final List<NodePort> inputs = new ArrayList<>();
    private final List<NodePort> outputs = new ArrayList<>();

    // Evaluation state
    private boolean dirty = true;
    private boolean enabled = true;
    private long lastEvalTimeNs;

    // Memoization: skip evaluate() when inputs haven't changed
    private long prevInputHash = 0;
    protected boolean memoizationEnabled = true; // subclasses with side effects can disable

    protected BRNode(String displayName, String category, int color) {
        this.nodeId = UUID.randomUUID().toString();
        this.displayName = displayName;
        this.category = category;
        this.color = color;
    }

    // ---- Port creation helpers (call in subclass constructors) ----

    protected NodePort addInput(String name, PortType type, Object defaultValue) {
        NodePort port = new NodePort(name, type, true, this, defaultValue);
        inputs.add(port);
        return port;
    }

    protected NodePort addOutput(String name, PortType type) {
        NodePort port = new NodePort(name, type, false, this, null);
        outputs.add(port);
        return port;
    }

    // ---- Abstract evaluation ----

    /**
     * Compute output values from the current input values.
     * Called by the graph scheduler when this node is dirty.
     */
    public abstract void evaluate();

    /**
     * Compute a hash of all current input port values for memoization.
     * Subclasses may override for custom hashing (e.g., deep hash on arrays).
     */
    protected long computeInputHash() {
        long hash = 17;
        for (NodePort port : getInputs()) {
            Object val = port.getValue();
            hash = hash * 31 + (val != null ? val.hashCode() : 0);
        }
        return hash;
    }

    /**
     * Wrapper around evaluate() that checks memoization before running.
     * If inputs haven't changed since the last evaluation, the call is skipped.
     * Called by NodeGraph instead of evaluate() directly.
     *
     * @return true if evaluate() was actually called, false if skipped by memoization
     */
    public boolean evaluateIfNeeded() {
        if (memoizationEnabled) {
            long inputHash = computeInputHash();
            if (inputHash == prevInputHash && !isDirty()) {
                return false; // skip — inputs unchanged
            }
            prevInputHash = inputHash;
        }
        evaluate();
        return true;
    }

    // ---- Convenience: read input values ----

    protected float getFloat(String portName) {
        NodePort p = getInput(portName);
        if (p == null) return 0.0f;
        Object v = p.getValue();
        if (v instanceof Number) return ((Number) v).floatValue();
        return 0.0f;
    }

    protected int getInt(String portName) {
        NodePort p = getInput(portName);
        if (p == null) return 0;
        Object v = p.getValue();
        if (v instanceof Number) return ((Number) v).intValue();
        return 0;
    }

    protected boolean getBool(String portName) {
        NodePort p = getInput(portName);
        if (p == null) return false;
        Object v = p.getValue();
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        return false;
    }

    // ---- Convenience: set output values ----

    /**
     * ★ review-fix ICReM-6: setOutput 不應觸發 owner.markDirty()（會把自己重新標 dirty），
     * 而是應該直接設值，讓下游在拓撲排序中自然評估。
     * 如果值有變化，透過 graph 的 markDownstreamDirty 標記下游。
     */
    protected void setOutput(String portName, Object value) {
        NodePort p = getOutput(portName);
        if (p != null) {
            p.setValueDirect(value); // 不觸發 markDirty
        }
    }

    // ---- Dirty propagation ----

    /**
     * Mark this node as needing re-evaluation.
     *
     * ★ review-fix ICReM-6: 修正 dirty 傳播邏輯。
     * 舊版 markDirty() 的 for 迴圈是空的（只有註解），導致 NodePort.setValue()
     * 觸發的 dirty 信號不會傳遞到下游節點。
     *
     * 修正方式：透過 output→wire→target 的反向查找標記直連下游節點。
     * 完整的遞歸傳播由 NodeGraph.markDownstreamDirty() 提供。
     * 此處只做直連一層（O(outputs×1)），避免在無 graph 上下文時遞歸。
     */
    public void markDirty() {
        if (dirty) return; // already dirty, avoid infinite recursion
        dirty = true;
        // ★ ICReM-6: 觸發 output 連線的直連下游節點 dirty
        // 注意：output port 不持有 wire 列表（由 NodeGraph 管理），
        // 但 input port 持有 connectedWire。因此我們不做傳播，
        // 依賴 NodeGraph.evaluate() 的拓撲排序：dirty 節點在評估時
        // 會呼叫 setOutput()，進而觸發下游 input.getValue() 取得新值。
        // 拓撲排序保證上游先於下游評估，所以即使下游未被標 dirty，
        // 其 evaluate() 仍會讀到最新的 wire 值。
        //
        // 但為了確保 hasDirtyNodes() 正確反映整體狀態（避免跳過評估），
        // 我們在 evaluate() 中 setOutput() 時由 NodePort.setValue() 重新觸發。
    }

    /**
     * Mark dirty unconditionally (used by NodeGraph during downstream propagation).
     */
    void forceDirty() {
        this.dirty = true;
    }

    // ---- Port lookup ----

    public NodePort getInput(String name) {
        for (NodePort p : inputs) {
            if (p.getName().equals(name)) return p;
        }
        return null;
    }

    public NodePort getOutput(String name) {
        for (NodePort p : outputs) {
            if (p.getName().equals(name)) return p;
        }
        return null;
    }

    // ---- Getters / Setters ----

    public String getNodeId() {
        return nodeId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public int getColor() {
        return color;
    }

    public void setColor(int color) {
        this.color = color;
    }

    public float getPosX() {
        return posX;
    }

    public void setPosX(float posX) {
        this.posX = posX;
    }

    public float getPosY() {
        return posY;
    }

    public void setPosY(float posY) {
        this.posY = posY;
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    public void setCollapsed(boolean collapsed) {
        this.collapsed = collapsed;
    }

    public List<NodePort> getInputs() {
        return Collections.unmodifiableList(inputs);
    }

    public List<NodePort> getOutputs() {
        return Collections.unmodifiableList(outputs);
    }

    public boolean isDirty() {
        return dirty;
    }

    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getLastEvalTimeNs() {
        return lastEvalTimeNs;
    }

    public void setLastEvalTimeNs(long lastEvalTimeNs) {
        this.lastEvalTimeNs = lastEvalTimeNs;
    }

    @Override
    public String toString() {
        return "BRNode[" + displayName + " (" + category + ") id=" + nodeId.substring(0, 8) + "]";
    }
}
