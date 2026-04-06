package com.blockreality.fastdesign.client.node;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.*;

/**
 * 節點基礎類別 — 設計報告 §3.1
 *
 * 每個 BRNode 是 DAG 中的一個計算單元：
 *   - 從 InputPort 讀取上游值
 *   - 執行 evaluate() 計算
 *   - 將結果寫入 OutputPort
 *
 * Grasshopper 類比：GH_Component
 */
public abstract class BRNode {

    // ─── 識別 ───
    private final String nodeId;
    private String displayName;
    private String displayNameCN;
    private final String category;
    private final NodeColor color;

    // ─── 幾何（畫布座標）───
    private float posX;
    private float posY;

    // 動畫平滑目標座標
    private float targetPosX;
    private float targetPosY;

    // 動畫縮放 (例如選中時的彈出效果)
    private float animScale = 1.0f;
    private float targetAnimScale = 1.0f;

    private float width = 140.0f;
    private float height;  // 自動計算
    private boolean collapsed;

    // ─── 端口 ───
    private final List<InputPort> inputs = new ArrayList<>();
    private final List<OutputPort> outputs = new ArrayList<>();

    // ─── 狀態 ───
    private boolean dirty = true;
    protected Throwable lastEvalError;
    private boolean enabled = true;
    private long lastEvalTimeNs;

    protected BRNode(String displayName, String displayNameCN, String category, NodeColor color) {
        this.nodeId = UUID.randomUUID().toString();
        this.displayName = displayName;
        this.displayNameCN = displayNameCN;
        this.category = category;
        this.color = color;
    }

    // ─── 核心抽象方法 ───

    /**
     * 從 inputs 計算 outputs。
     * 實作時透過 getInput(name).getValue() 讀取輸入，
     * 透過 getOutput(name).setValue() 寫入輸出。
     */
    public abstract void evaluate();

    /**
     * 懸停提示文字。
     */
    public abstract String getTooltip();

    /**
     * 節點型別 ID（用於 NodeRegistry 反查和序列化）。
     * 格式：category.subcategory.NodeName，如 "render.postfx.SSAO_GTAO"
     */
    public abstract String typeId();

    // ─── 端口管理 ───

    protected InputPort addInput(String name, PortType type) {
        InputPort port = new InputPort(name, type);
        port.setOwner(this);
        inputs.add(port);
        recalcHeight();
        return port;
    }

    protected InputPort addInput(String name, String displayName, PortType type, Object defaultValue) {
        InputPort port = new InputPort(name, displayName, type, defaultValue);
        port.setOwner(this);
        inputs.add(port);
        recalcHeight();
        return port;
    }

    protected OutputPort addOutput(String name, PortType type) {
        OutputPort port = new OutputPort(name, type);
        port.setOwner(this);
        outputs.add(port);
        recalcHeight();
        return port;
    }

    protected OutputPort addOutput(String name, String displayName, PortType type) {
        OutputPort port = new OutputPort(name, displayName, type);
        port.setOwner(this);
        outputs.add(port);
        recalcHeight();
        return port;
    }

    @Nullable
    public InputPort getInput(String name) {
        for (InputPort p : inputs) {
            if (p.name().equals(name)) return p;
        }
        return null;
    }

    @Nullable
    public OutputPort getOutput(String name) {
        for (OutputPort p : outputs) {
            if (p.name().equals(name)) return p;
        }
        return null;
    }

    public List<InputPort> inputs()   { return Collections.unmodifiableList(inputs); }
    public List<OutputPort> outputs() { return Collections.unmodifiableList(outputs); }

    // ─── 髒標記 ───

    public boolean isDirty()  { return dirty; }
    public boolean isEnabled() { return enabled; }

    public boolean hasError() {
        if (lastEvalError != null) return true;
        for (InputPort p : inputs) {
            if (p.isRequired() && p.getRawValue() == null) return true;
        }
        return false;
    }

    public Throwable getLastEvalError() { return lastEvalError; }

    public void setLastEvalError(Throwable e) { this.lastEvalError = e; }

    /**
     * 標記自身為髒，並沿所有輸出連線傳播到下游節點。
     */
    public void markDirty() {
        if (dirty) return;  // 避免重複傳播
        dirty = true;
        for (OutputPort out : outputs) {
            for (Wire w : out.outgoingWires()) {
                BRNode downstream = w.to().owner();
                if (downstream != null) {
                    downstream.markDirty();
                }
            }
        }
    }

    /**
     * 清除髒標記（由 EvaluateScheduler 在 evaluate 後呼叫）。
     */
    public void clearDirty() {
        this.dirty = false;
        this.lastEvalError = null;
    }

    /**
     * 強制標記為髒（不檢查當前狀態）。
     */
    public void forceDirty() {
        this.dirty = true;
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled != enabled) {
            this.enabled = enabled;
            markDirty();
        }
    }

    // ─── 幾何 ───

    public String nodeId()        { return nodeId; }
    public String displayName()   { return displayName; }
    public String displayNameCN() { return displayNameCN; }
    public String category()      { return category; }
    public NodeColor color()      { return color; }

    public float posX()           { return posX; }
    public float posY()           { return posY; }
    public float targetPosX()     { return targetPosX; }
    public float targetPosY()     { return targetPosY; }
    public float animScale()      { return animScale; }
    public float targetAnimScale(){ return targetAnimScale; }
    public float width()          { return width; }
    public float height()         { return height; }
    public boolean isCollapsed()  { return collapsed; }

    public void setPosition(float x, float y) {
        this.posX = x;
        this.posY = y;
        this.targetPosX = x;
        this.targetPosY = y;
    }

    public void setTargetPosition(float x, float y) {
        this.targetPosX = x;
        this.targetPosY = y;
    }

    public void setTargetAnimScale(float scale) {
        this.targetAnimScale = scale;
    }

    public void tickLerp() {
        this.posX += (this.targetPosX - this.posX) * 0.4f;
        this.posY += (this.targetPosY - this.posY) * 0.4f;
        this.animScale += (this.targetAnimScale - this.animScale) * 0.3f;
    }

    public void setCollapsed(boolean c)        { this.collapsed = c; recalcHeight(); }
    public void setDisplayName(String name)    { this.displayName = name; }
    public void setDisplayNameCN(String name)  { this.displayNameCN = name; }

    public long lastEvalTimeNs()               { return lastEvalTimeNs; }
    public void setLastEvalTimeNs(long ns)     { this.lastEvalTimeNs = ns; }

    private void recalcHeight() {
        if (collapsed) {
            height = 24.0f;  // 僅標題列
        } else {
            int portCount = Math.max(inputs.size(), outputs.size());
            height = 24.0f + portCount * 20.0f + 8.0f;  // header + ports + padding
        }
    }

    // ─── 碰撞檢測（畫布用）───

    public boolean containsPoint(float cx, float cy) {
        return cx >= posX && cx <= posX + width
            && cy >= posY && cy <= posY + height;
    }

    public boolean intersectsRect(float rx, float ry, float rw, float rh) {
        return posX < rx + rw && posX + width > rx
            && posY < ry + rh && posY + height > ry;
    }

    // ─── 序列化 ───

    public CompoundTag serialize() {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", nodeId);
        tag.putString("type", typeId());
        tag.putString("displayName", displayName);
        if (displayNameCN != null) tag.putString("displayNameCN", displayNameCN);
        tag.putFloat("posX", posX);
        tag.putFloat("posY", posY);
        tag.putBoolean("collapsed", collapsed);
        tag.putBoolean("enabled", enabled);

        // 序列化輸入端口本地值
        CompoundTag inputValues = new CompoundTag();
        for (InputPort port : inputs) {
            if (!port.isConnected()) {
                serializePortValue(inputValues, port.name(), port.type(), port.getRawValue());
            }
        }
        tag.put("inputs", inputValues);

        return tag;
    }

    public void deserialize(CompoundTag tag) {
        // nodeId 在建構時已設定（或由 IO 層覆寫）
        if (tag.contains("displayName")) displayName = tag.getString("displayName");
        if (tag.contains("displayNameCN")) displayNameCN = tag.getString("displayNameCN");
        posX = tag.getFloat("posX");
        posY = tag.getFloat("posY");
        targetPosX = posX;
        targetPosY = posY;
        collapsed = tag.getBoolean("collapsed");
        enabled = tag.getBoolean("enabled");

        // 還原輸入值
        CompoundTag inputValues = tag.getCompound("inputs");
        for (InputPort port : inputs) {
            if (inputValues.contains(port.name())) {
                Object val = deserializePortValue(inputValues, port.name(), port.type());
                if (val != null) port.setLocalValue(val);
            }
        }
        recalcHeight();
    }

    // ─── 端口值序列化工具 ───

    private static void serializePortValue(CompoundTag tag, String key, PortType type, Object value) {
        if (value == null) return;
        switch (type) {
            case FLOAT   -> tag.putFloat(key, ((Number) value).floatValue());
            case INT     -> tag.putInt(key, ((Number) value).intValue());
            case BOOL    -> tag.putBoolean(key, (Boolean) value);
            case COLOR   -> tag.putInt(key, (Integer) value);
            case TEXTURE -> tag.putInt(key, (Integer) value);
            case VEC2, VEC3, VEC4, CURVE -> {
                float[] arr = (float[]) value;
                ListTag list = new ListTag();
                for (float v : arr) {
                    CompoundTag el = new CompoundTag();
                    el.putFloat("v", v);
                    list.add(el);
                }
                tag.put(key, list);
            }
            case ENUM -> {
                if (value instanceof Enum<?> e) tag.putString(key, e.name());
                else tag.putString(key, value.toString());
            }
            case STRUCT -> {
                if (value instanceof CompoundTag ct) tag.put(key, ct);
            }
            default -> {} // MATERIAL, BLOCK, SHAPE — 不序列化（由連線提供）
        }
    }

    @Nullable
    private static Object deserializePortValue(CompoundTag tag, String key, PortType type) {
        if (!tag.contains(key)) return null;
        return switch (type) {
            case FLOAT   -> tag.getFloat(key);
            case INT     -> tag.getInt(key);
            case BOOL    -> tag.getBoolean(key);
            case COLOR   -> tag.getInt(key);
            case TEXTURE -> tag.getInt(key);
            case VEC2, VEC3, VEC4, CURVE -> {
                ListTag list = tag.getList(key, Tag.TAG_COMPOUND);
                float[] arr = new float[list.size()];
                for (int i = 0; i < list.size(); i++) {
                    arr[i] = list.getCompound(i).getFloat("v");
                }
                yield arr;
            }
            case ENUM   -> tag.getString(key);
            case STRUCT -> tag.getCompound(key);
            default     -> null;
        };
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BRNode that)) return false;
        return nodeId.equals(that.nodeId);
    }

    @Override
    public int hashCode() { return nodeId.hashCode(); }

    @Override
    public String toString() { return displayName + " [" + typeId() + "]"; }
}
