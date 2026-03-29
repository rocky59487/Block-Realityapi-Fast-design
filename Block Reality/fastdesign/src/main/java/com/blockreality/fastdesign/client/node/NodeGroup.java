package com.blockreality.fastdesign.client.node;

import java.util.*;

/**
 * 節點群組 — 設計報告 §2.2
 *
 * 可折疊的節點群組，對應 Grasshopper 的 GH_Group。
 *
 * 功能：
 *   - 框選多個節點後 Ctrl+G 建立群組
 *   - 群組有名稱、顏色、摺疊狀態
 *   - 摺疊時隱藏所有內部節點（只顯示群組框 + 標題）
 *   - 移動群組時所有內部節點跟隨移動
 */
public class NodeGroup {

    private final String groupId;
    private String name;
    private int color;  // ARGB
    private boolean collapsed;
    private final Set<String> nodeIds = new LinkedHashSet<>();

    // 摺疊前記錄的邊界（恢復用）
    private float savedX, savedY, savedW, savedH;

    public NodeGroup(String name, int color) {
        this.groupId = UUID.randomUUID().toString();
        this.name = name;
        this.color = color;
    }

    // ─── 節點管理 ───

    public void addNode(BRNode node) {
        nodeIds.add(node.nodeId());
    }

    public void removeNode(BRNode node) {
        nodeIds.remove(node.nodeId());
    }

    public boolean containsNode(BRNode node) {
        return nodeIds.contains(node.nodeId());
    }

    public boolean containsNodeId(String nodeId) {
        return nodeIds.contains(nodeId);
    }

    public Set<String> nodeIds() {
        return Collections.unmodifiableSet(nodeIds);
    }

    public int size() { return nodeIds.size(); }

    // ─── 幾何（從成員節點計算包圍盒）───

    /**
     * 計算群組的包圍矩形（含 padding）。
     */
    public float[] computeBounds(NodeGraph graph) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE;

        for (String id : nodeIds) {
            BRNode node = graph.getNode(id);
            if (node == null) continue;
            minX = Math.min(minX, node.posX());
            minY = Math.min(minY, node.posY());
            maxX = Math.max(maxX, node.posX() + node.width());
            maxY = Math.max(maxY, node.posY() + node.height());
        }

        if (minX > maxX) return new float[]{0, 0, 100, 50}; // 空群組

        float padding = 12.0f;
        float headerHeight = 24.0f;
        return new float[]{
                minX - padding,
                minY - padding - headerHeight,
                (maxX - minX) + padding * 2,
                (maxY - minY) + padding * 2 + headerHeight
        };
    }

    /**
     * 移動群組（移動所有內部節點）。
     */
    public void moveBy(float dx, float dy, NodeGraph graph) {
        for (String id : nodeIds) {
            BRNode node = graph.getNode(id);
            if (node != null) {
                node.setPosition(node.posX() + dx, node.posY() + dy);
            }
        }
    }

    // ─── 折疊 ───

    public void collapse(NodeGraph graph) {
        if (collapsed) return;
        float[] bounds = computeBounds(graph);
        savedX = bounds[0]; savedY = bounds[1];
        savedW = bounds[2]; savedH = bounds[3];
        collapsed = true;
    }

    public void expand() {
        collapsed = false;
    }

    public void toggleCollapse(NodeGraph graph) {
        if (collapsed) expand(); else collapse(graph);
    }

    // ─── 屬性 ───

    public String groupId()   { return groupId; }
    public String name()      { return name; }
    public int color()        { return color; }
    public boolean isCollapsed() { return collapsed; }

    public void setName(String name)  { this.name = name; }
    public void setColor(int color)   { this.color = color; }

    /** 折疊時的顯示位置 */
    public float savedX() { return savedX; }
    public float savedY() { return savedY; }
    public float savedW() { return savedW; }
    public float savedH() { return savedH; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NodeGroup that)) return false;
        return groupId.equals(that.groupId);
    }

    @Override
    public int hashCode() { return groupId.hashCode(); }
}
