package com.blockreality.fastdesign.client.node;

import java.util.Objects;
import java.util.UUID;

/**
 * 連線 — 設計報告 §3.3
 *
 * 從 OutputPort 到 InputPort 的有向資料連線。
 *
 * 規則：
 *   - 連線前必須通過 TypeChecker 驗證
 *   - 一個 InputPort 最多一條連線（後連覆蓋先連）
 *   - 一個 OutputPort 可多條連線（fan-out）
 *   - 禁止形成環路（由 NodeGraph 檢查）
 */
public final class Wire {

    private final String wireId;
    private final OutputPort from;
    private final InputPort to;
    private boolean connected;

    private Wire(OutputPort from, InputPort to) {
        this.wireId = UUID.randomUUID().toString();
        this.from = Objects.requireNonNull(from, "from");
        this.to = Objects.requireNonNull(to, "to");
        this.connected = false;
    }

    /**
     * 建立並連接一條 Wire。
     *
     * @return 新 Wire，若型別不相容或同一節點則回傳 null
     */
    public static Wire connect(OutputPort from, InputPort to) {
        // 不能連接同一節點的端口
        if (from.owner() == to.owner() && from.owner() != null) {
            return null;
        }

        // 型別檢查
        if (!TypeChecker.canConnect(from.type(), to.type())) {
            return null;
        }

        Wire wire = new Wire(from, to);
        wire.attach();
        return wire;
    }

    /**
     * 實際掛載連線到端口。
     */
    private void attach() {
        to.setIncomingWire(this);
        from.addOutgoingWire(this);
        connected = true;

        // 標記下游為髒
        if (to.owner() != null) {
            to.owner().markDirty();
        }
    }

    /**
     * 斷開連線，輸入端口恢復預設值。
     */
    public void disconnect() {
        if (!connected) return;
        connected = false;

        from.removeOutgoingWire(this);

        // 僅當此 Wire 仍是 to 的當前連線時才清除
        if (to.incomingWire() == this) {
            to.setIncomingWire(null);
            to.resetToDefault();
        }
    }

    // ─── 存取 ───

    public String wireId()    { return wireId; }
    public OutputPort from()  { return from; }
    public InputPort to()     { return to; }
    public boolean isConnected() { return connected; }

    /**
     * 來源端口型別。
     */
    public PortType fromType() { return from.type(); }

    /**
     * 目標端口型別。
     */
    public PortType toType() { return to.type(); }

    /**
     * 是否為自動轉換連線（來源和目標型別不同）。
     */
    public boolean isAutoConverted() {
        return from.type() != to.type();
    }

    /**
     * 取得線色（使用來源端口的型別色）。
     */
    public int wireColor() {
        return from.type().wireColor();
    }

    // ─── 序列化 ───

    /**
     * 序列化為 "outputNodeId.portName -> inputNodeId.portName"
     */
    public String serializeFromPath() { return from.qualifiedName(); }
    public String serializeToPath()   { return to.qualifiedName(); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Wire that)) return false;
        return wireId.equals(that.wireId);
    }

    @Override
    public int hashCode() { return wireId.hashCode(); }

    @Override
    public String toString() {
        return "Wire{" + from.qualifiedName() + " -> " + to.qualifiedName() + "}";
    }
}
