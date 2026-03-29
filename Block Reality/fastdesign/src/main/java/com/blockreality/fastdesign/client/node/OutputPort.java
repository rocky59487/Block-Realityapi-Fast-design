package com.blockreality.fastdesign.client.node;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 輸出端口 — 設計報告 §3.1
 *
 * 規則：
 *   - 可連接到多個 InputPort（一對多 fan-out）
 *   - 值由 BRNode.evaluate() 寫入
 */
public class OutputPort extends NodePort {

    private final List<Wire> outgoingWires = new ArrayList<>();

    public OutputPort(String name, String displayName, PortType type) {
        super(name, displayName, type, null);
    }

    public OutputPort(String name, PortType type) {
        this(name, name, type);
    }

    // ─── 連線管理 ───

    public List<Wire> outgoingWires() {
        return Collections.unmodifiableList(outgoingWires);
    }

    public int connectionCount() {
        return outgoingWires.size();
    }

    public boolean isConnected() {
        return !outgoingWires.isEmpty();
    }

    void addOutgoingWire(Wire wire) {
        if (!outgoingWires.contains(wire)) {
            outgoingWires.add(wire);
        }
    }

    void removeOutgoingWire(Wire wire) {
        outgoingWires.remove(wire);
    }

    // ─── 值寫入 ───

    /**
     * 由 BRNode.evaluate() 呼叫，設定輸出值。
     * 不觸發髒標記傳播 — 那是 EvaluateScheduler 的職責。
     */
    public void setValue(@Nullable Object newValue) {
        this.value = newValue;
    }

    /**
     * 取得所有下游 InputPort 的擁有者節點。
     */
    public List<BRNode> downstreamNodes() {
        List<BRNode> nodes = new ArrayList<>();
        for (Wire w : outgoingWires) {
            BRNode n = w.to().owner();
            if (n != null && !nodes.contains(n)) {
                nodes.add(n);
            }
        }
        return nodes;
    }
}
