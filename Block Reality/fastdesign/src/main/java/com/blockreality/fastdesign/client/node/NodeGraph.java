package com.blockreality.fastdesign.client.node;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.*;

/**
 * 節點圖 DAG 管理器 — 設計報告 §2.2
 *
 * 管理所有節點和連線，提供：
 *   - 新增/移除節點
 *   - 建立/斷開連線（含環路檢測）
 *   - Kahn's 拓撲排序
 *   - 依 nodeId 或端口路徑查找
 *
 * Grasshopper 類比：GH_Document
 */
public class NodeGraph {

    private static final Logger LOGGER = LogManager.getLogger("NodeGraph");

    private String name = "Untitled";
    private String author = "";
    private long lastModified;

    private final Map<String, BRNode> nodes = new LinkedHashMap<>();
    private final List<Wire> wires = new ArrayList<>();
    private final List<NodeGroup> groups = new ArrayList<>();

    // 拓撲排序快取
    private List<BRNode> topoOrder;
    private boolean topoOrderDirty = true;

    // ─── 節點管理 ───

    public void addNode(BRNode node) {
        nodes.put(node.nodeId(), node);
        topoOrderDirty = true;
        lastModified = System.currentTimeMillis();
    }

    public void removeNode(BRNode node) {
        // 先斷開所有相關連線
        disconnectAll(node);
        nodes.remove(node.nodeId());

        // 從群組中移除
        for (NodeGroup group : groups) {
            group.removeNode(node);
        }

        topoOrderDirty = true;
        lastModified = System.currentTimeMillis();
    }

    @Nullable
    public BRNode getNode(String nodeId) {
        return nodes.get(nodeId);
    }

    public Collection<BRNode> allNodes() {
        return Collections.unmodifiableCollection(nodes.values());
    }

    public int nodeCount() { return nodes.size(); }

    // ─── 連線管理 ───

    /**
     * 建立連線。自動檢查：
     *   1. 型別相容
     *   2. 不形成環路
     *
     * @return 新 Wire，或 null（不相容/環路/同節點）
     */
    @Nullable
    public Wire connect(OutputPort from, InputPort to) {
        // 環路檢測
        if (wouldCreateCycle(from.owner(), to.owner())) {
            LOGGER.warn("拒絕連線：會形成環路 {} -> {}", from.qualifiedName(), to.qualifiedName());
            return null;
        }

        Wire wire = Wire.connect(from, to);
        if (wire != null) {
            wires.add(wire);
            topoOrderDirty = true;
            lastModified = System.currentTimeMillis();
        }
        return wire;
    }

    public void disconnect(Wire wire) {
        wire.disconnect();
        wires.remove(wire);
        topoOrderDirty = true;
        lastModified = System.currentTimeMillis();
    }

    /**
     * 斷開節點的所有連線。
     */
    public void disconnectAll(BRNode node) {
        List<Wire> toRemove = new ArrayList<>();
        for (Wire w : wires) {
            if (w.from().owner() == node || w.to().owner() == node) {
                toRemove.add(w);
            }
        }
        for (Wire w : toRemove) {
            disconnect(w);
        }
    }

    public List<Wire> allWires() {
        return Collections.unmodifiableList(wires);
    }

    public int wireCount() { return wires.size(); }

    // ─── 群組管理 ───

    public void addGroup(NodeGroup group) {
        groups.add(group);
        lastModified = System.currentTimeMillis();
    }

    public void removeGroup(NodeGroup group) {
        groups.remove(group);
        lastModified = System.currentTimeMillis();
    }

    public List<NodeGroup> allGroups() {
        return Collections.unmodifiableList(groups);
    }

    // ─── 環路檢測 ───

    /**
     * 檢查從 fromNode 的輸出連到 toNode 的輸入是否會形成環路。
     * 使用 BFS 從 toNode 的輸出向下搜索，看是否能到達 fromNode。
     */
    private boolean wouldCreateCycle(@Nullable BRNode fromNode, @Nullable BRNode toNode) {
        if (fromNode == null || toNode == null) return false;
        if (fromNode == toNode) return true;

        // BFS：從 toNode 向下游搜索
        Set<String> visited = new HashSet<>();
        Queue<BRNode> queue = new ArrayDeque<>();
        queue.add(toNode);
        visited.add(toNode.nodeId());

        while (!queue.isEmpty()) {
            BRNode current = queue.poll();
            for (OutputPort out : current.outputs()) {
                for (Wire w : out.outgoingWires()) {
                    BRNode downstream = w.to().owner();
                    if (downstream == null) continue;
                    if (downstream == fromNode) return true;
                    if (visited.add(downstream.nodeId())) {
                        queue.add(downstream);
                    }
                }
            }
        }
        return false;
    }

    // ─── 拓撲排序（Kahn's Algorithm）───

    /**
     * 取得拓撲排序結果（快取）。
     * 順序：上游在前、下游在後。
     */
    public List<BRNode> topologicalOrder() {
        if (!topoOrderDirty && topoOrder != null) {
            return topoOrder;
        }

        topoOrder = computeTopologicalOrder();
        topoOrderDirty = false;
        return topoOrder;
    }

    private List<BRNode> computeTopologicalOrder() {
        // 計算每個節點的入度（有多少上游節點連入）
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, Set<String>> adjacency = new HashMap<>();

        for (BRNode node : nodes.values()) {
            inDegree.putIfAbsent(node.nodeId(), 0);
            adjacency.putIfAbsent(node.nodeId(), new HashSet<>());
        }

        for (Wire w : wires) {
            if (!w.isConnected()) continue;
            BRNode src = w.from().owner();
            BRNode dst = w.to().owner();
            if (src == null || dst == null) continue;
            if (!adjacency.containsKey(src.nodeId())) continue;

            if (adjacency.get(src.nodeId()).add(dst.nodeId())) {
                inDegree.merge(dst.nodeId(), 1, Integer::sum);
            }
        }

        // Kahn's
        Queue<String> queue = new ArrayDeque<>();
        for (var entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        List<BRNode> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            String nodeId = queue.poll();
            BRNode node = nodes.get(nodeId);
            if (node != null) {
                result.add(node);
            }

            Set<String> neighbors = adjacency.getOrDefault(nodeId, Collections.emptySet());
            for (String neighborId : neighbors) {
                int deg = inDegree.merge(neighborId, -1, Integer::sum);
                if (deg == 0) {
                    queue.add(neighborId);
                }
            }
        }

        if (result.size() != nodes.size()) {
            LOGGER.error("拓撲排序不完整：{}/{} 節點（可能存在殘留環路）",
                    result.size(), nodes.size());
        }

        return result;
    }

    public void invalidateTopology() {
        topoOrderDirty = true;
    }

    // ─── 查找 ───

    /**
     * 透過 "nodeId.portName" 路徑查找輸出端口。
     */
    @Nullable
    public OutputPort findOutputPort(String path) {
        int dot = path.indexOf('.');
        if (dot < 0) return null;
        String nodeId = path.substring(0, dot);
        String portName = path.substring(dot + 1);
        BRNode node = nodes.get(nodeId);
        return node != null ? node.getOutput(portName) : null;
    }

    /**
     * 透過 "nodeId.portName" 路徑查找輸入端口。
     */
    @Nullable
    public InputPort findInputPort(String path) {
        int dot = path.indexOf('.');
        if (dot < 0) return null;
        String nodeId = path.substring(0, dot);
        String portName = path.substring(dot + 1);
        BRNode node = nodes.get(nodeId);
        return node != null ? node.getInput(portName) : null;
    }

    /**
     * 取得指定位置的節點（畫布點擊用），Z-order：後加的在上面。
     */
    @Nullable
    public BRNode nodeAtPoint(float x, float y) {
        List<BRNode> list = new ArrayList<>(nodes.values());
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).containsPoint(x, y)) return list.get(i);
        }
        return null;
    }

    /**
     * 取得矩形範圍內的所有節點（框選用）。
     */
    public List<BRNode> nodesInRect(float x, float y, float w, float h) {
        List<BRNode> result = new ArrayList<>();
        for (BRNode node : nodes.values()) {
            if (node.intersectsRect(x, y, w, h)) result.add(node);
        }
        return result;
    }

    // ─── 屬性 ───

    public String name()           { return name; }
    public String author()         { return author; }
    public long lastModified()     { return lastModified; }

    public void setName(String name)     { this.name = name; }
    public void setAuthor(String author) { this.author = author; }

    /**
     * 清空整個圖。
     */
    public void clear() {
        for (Wire w : new ArrayList<>(wires)) w.disconnect();
        wires.clear();
        nodes.clear();
        groups.clear();
        topoOrderDirty = true;
        lastModified = System.currentTimeMillis();
    }
}
