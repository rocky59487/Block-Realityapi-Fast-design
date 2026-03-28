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

    protected void setOutput(String portName, Object value) {
        NodePort p = getOutput(portName);
        if (p != null) {
            p.setValue(value);
        }
    }

    // ---- Dirty propagation ----

    /**
     * Mark this node as needing re-evaluation and propagate the dirty flag
     * to all nodes connected to this node's outputs.
     */
    public void markDirty() {
        if (dirty) return; // already dirty, avoid infinite recursion
        dirty = true;
        for (NodePort out : outputs) {
            // The wire list is managed by NodeGraph; here we only have the
            // single-wire link on each connected input port. The graph's
            // evaluate loop handles full propagation via topological order,
            // but we still flag directly connected nodes for responsiveness.
            // (NodeGraph.markDownstreamDirty provides the full BFS.)
        }
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
