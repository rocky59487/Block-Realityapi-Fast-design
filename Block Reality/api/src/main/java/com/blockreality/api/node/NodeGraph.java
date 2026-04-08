package com.blockreality.api.node;

import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * DAG manager that holds all nodes and wires, performs topological sorting,
 * and evaluates dirty nodes in dependency order.
 */
public class NodeGraph {
    private static final Logger LOGGER = Logger.getLogger(NodeGraph.class.getName());
    private static final int PARALLEL_THRESHOLD = 10;

    private final String graphId;
    private String name;
    private final Map<String, BRNode> nodes = new LinkedHashMap<>();
    private final List<Wire> wires = new ArrayList<>();
    private final Map<NodePort, List<BRNode>> wireIndex = new HashMap<>();
    private List<BRNode> evaluationOrder = new ArrayList<>();
    private boolean orderDirty = true;

    public NodeGraph(String name) {
        this.graphId = UUID.randomUUID().toString();
        this.name = name;
    }

    // ---- Node management ----

    public void addNode(BRNode node) {
        nodes.put(node.getNodeId(), node);
        orderDirty = true;
    }

    /**
     * Remove a node and all wires connected to any of its ports.
     */
    public void removeNode(String nodeId) {
        BRNode node = nodes.remove(nodeId);
        if (node == null) return;

        // Remove all wires touching this node
        Iterator<Wire> it = wires.iterator();
        while (it.hasNext()) {
            Wire w = it.next();
            if (w.getSource().getOwner() == node || w.getTarget().getOwner() == node) {
                w.disconnect();
                List<BRNode> targets = wireIndex.get(w.getSource());
                if (targets != null) targets.remove(w.getTarget().getOwner());
                it.remove();
            }
        }
        orderDirty = true;
    }

    public BRNode getNode(String nodeId) {
        return nodes.get(nodeId);
    }

    public Collection<BRNode> getAllNodes() {
        return Collections.unmodifiableCollection(nodes.values());
    }

    // ---- Wire management ----

    /**
     * Create a wire from an output port to an input port after validating
     * type compatibility. If the input port is already connected, the old
     * wire is removed first (inputs accept at most one wire).
     *
     * @throws IllegalArgumentException on type mismatch or wrong port direction
     */
    public Wire connect(NodePort source, NodePort target) {
        // Disconnect any existing wire on the input
        if (target.isConnected()) {
            disconnect(target);
        }

        Wire wire = new Wire(source, target);
        wires.add(wire);
        wireIndex.computeIfAbsent(wire.getSource(), k -> new ArrayList<>()).add(wire.getTarget().getOwner());
        target.setConnectedWire(wire);
        orderDirty = true;

        // Mark the target node (and everything downstream) dirty
        markDownstreamDirty(target.getOwner());

        return wire;
    }

    public void disconnect(Wire wire) {
        wire.disconnect();
        wires.remove(wire);
        List<BRNode> targets = wireIndex.get(wire.getSource());
        if (targets != null) targets.remove(wire.getTarget().getOwner());
        orderDirty = true;
    }

    /**
     * Remove all wires connected to a specific port.
     */
    public void disconnect(NodePort port) {
        Iterator<Wire> it = wires.iterator();
        while (it.hasNext()) {
            Wire w = it.next();
            if (w.getSource() == port || w.getTarget() == port) {
                w.disconnect();
                List<BRNode> targets = wireIndex.get(w.getSource());
                if (targets != null) targets.remove(w.getTarget().getOwner());
                it.remove();
            }
        }
        orderDirty = true;
    }

    // ---- Evaluation ----

    /**
     * Rebuild topological order if needed, then evaluate every dirty node
     * in dependency order. Nodes at the same depth level with no mutual
     * dependencies may be evaluated in parallel when the level is large enough.
     * Returns the number of nodes evaluated.
     */
    public int evaluate() {
        if (orderDirty) {
            rebuildTopologicalOrder();
            orderDirty = false;
        }

        // Compute depth levels for each node
        Map<String, Integer> depthMap = computeDepthLevels();

        // Group dirty+enabled nodes by level, preserving topo order within each level
        Map<Integer, List<BRNode>> levelGroups = new LinkedHashMap<>();
        for (BRNode node : evaluationOrder) {
            if (!node.isEnabled()) continue;
            if (!node.isDirty()) continue;
            int level = depthMap.getOrDefault(node.getNodeId(), 0);
            levelGroups.computeIfAbsent(level, k -> new ArrayList<>()).add(node);
        }

        int evaluated = 0;
        for (List<BRNode> levelNodes : levelGroups.values()) {
            if (levelNodes.size() >= PARALLEL_THRESHOLD) {
                levelNodes.parallelStream().forEach(node -> {
                    try {
                        long t0 = System.nanoTime();
                        node.evaluateIfNeeded();
                        node.setLastEvalTimeNs(System.nanoTime() - t0);
                        node.setDirty(false);
                    } catch (Exception e) {
                        LOGGER.severe("Node " + node + " eval failed: " + e.getMessage());
                    }
                });
            } else {
                for (BRNode node : levelNodes) {
                    long t0 = System.nanoTime();
                    node.evaluateIfNeeded();
                    node.setLastEvalTimeNs(System.nanoTime() - t0);
                    node.setDirty(false);
                }
            }
            evaluated += levelNodes.size();
        }
        return evaluated;
    }

    /**
     * Compute depth levels for each node: nodes with no incoming edges = level 0,
     * each node's level = max(input node levels) + 1.
     */
    private Map<String, Integer> computeDepthLevels() {
        Map<String, Integer> depth = new HashMap<>();
        // Build reverse adjacency: target node id -> set of source node ids
        Map<String, Set<String>> incoming = new HashMap<>();
        for (Wire w : wires) {
            String srcId = w.getSource().getOwner().getNodeId();
            String tgtId = w.getTarget().getOwner().getNodeId();
            incoming.computeIfAbsent(tgtId, k -> new HashSet<>()).add(srcId);
        }

        // Assign depths using the already-computed topo order
        for (BRNode node : evaluationOrder) {
            String id = node.getNodeId();
            Set<String> parents = incoming.get(id);
            if (parents == null || parents.isEmpty()) {
                depth.put(id, 0);
            } else {
                int maxParentDepth = 0;
                for (String parentId : parents) {
                    maxParentDepth = Math.max(maxParentDepth, depth.getOrDefault(parentId, 0));
                }
                depth.put(id, maxParentDepth + 1);
            }
        }
        return depth;
    }

    /**
     * Kahn's algorithm for topological sorting of the node DAG.
     * <ol>
     *   <li>Compute in-degree for each node (count incoming wires to input ports).</li>
     *   <li>Add all zero-in-degree nodes to queue.</li>
     *   <li>Process queue: for each node, reduce in-degree of downstream nodes.</li>
     *   <li>Result = evaluation order.</li>
     * </ol>
     */
    private void rebuildTopologicalOrder() {
        // Compute in-degrees
        Map<String, Integer> inDegree = new HashMap<>();
        for (BRNode node : nodes.values()) {
            inDegree.put(node.getNodeId(), 0);
        }
        // Build adjacency: source node -> set of target nodes
        Map<String, Set<String>> adjacency = new HashMap<>();
        for (BRNode node : nodes.values()) {
            adjacency.put(node.getNodeId(), new HashSet<>());
        }
        for (Wire w : wires) {
            String srcId = w.getSource().getOwner().getNodeId();
            String tgtId = w.getTarget().getOwner().getNodeId();
            if (adjacency.get(srcId).add(tgtId)) {
                inDegree.merge(tgtId, 1, Integer::sum);
            }
        }

        // Seed queue with zero-in-degree nodes
        Deque<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        List<BRNode> sorted = new ArrayList<>(nodes.size());
        while (!queue.isEmpty()) {
            String id = queue.poll();
            sorted.add(nodes.get(id));
            for (String downstream : adjacency.getOrDefault(id, Collections.emptySet())) {
                int deg = inDegree.merge(downstream, -1, Integer::sum);
                if (deg == 0) {
                    queue.add(downstream);
                }
            }
        }

        // If sorted.size() < nodes.size(), there is a cycle — include remaining
        // nodes at the end so they are still reachable (but may evaluate in
        // undefined order).
        if (sorted.size() < nodes.size()) {
            Set<String> visited = new HashSet<>();
            for (BRNode n : sorted) visited.add(n.getNodeId());
            for (BRNode n : nodes.values()) {
                if (!visited.contains(n.getNodeId())) {
                    sorted.add(n);
                }
            }
        }

        this.evaluationOrder = sorted;
    }

    /**
     * ★ review-fix ICReM-6: 優化 downstream dirty 傳播。
     * 舊版在 BFS 中對每個 output port 掃描所有 wire（O(N×W)），
     * 改為使用預建的 source→target 索引（O(N+E)）。
     */
    void markDownstreamDirty(BRNode node) {
        Deque<BRNode> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(node);
        visited.add(node.getNodeId());
        node.forceDirty();

        while (!queue.isEmpty()) {
            BRNode current = queue.poll();
            for (NodePort out : current.getOutputs()) {
                List<BRNode> targets = wireIndex.getOrDefault(out, Collections.emptyList());
                for (BRNode downstream : targets) {
                    if (visited.add(downstream.getNodeId())) {
                        downstream.forceDirty();
                        queue.add(downstream);
                    }
                }
            }
        }
    }

    // ---- Query ----

    public int getNodeCount() {
        return nodes.size();
    }

    public int getWireCount() {
        return wires.size();
    }

    public List<BRNode> getNodesInCategory(String category) {
        List<BRNode> result = new ArrayList<>();
        for (BRNode node : nodes.values()) {
            if (category.equals(node.getCategory())) {
                result.add(node);
            }
        }
        return result;
    }

    /**
     * Find all nodes in the connected subgraph that contains the given node (BFS,
     * treating wires as undirected edges).
     */
    public List<BRNode> findConnectedSubgraph(String nodeId) {
        BRNode start = nodes.get(nodeId);
        if (start == null) return Collections.emptyList();

        // Build undirected adjacency
        Map<String, Set<String>> adj = new HashMap<>();
        for (Wire w : wires) {
            String s = w.getSource().getOwner().getNodeId();
            String t = w.getTarget().getOwner().getNodeId();
            adj.computeIfAbsent(s, k -> new HashSet<>()).add(t);
            adj.computeIfAbsent(t, k -> new HashSet<>()).add(s);
        }

        List<BRNode> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(nodeId);
        visited.add(nodeId);

        while (!queue.isEmpty()) {
            String id = queue.poll();
            result.add(nodes.get(id));
            for (String neighbor : adj.getOrDefault(id, Collections.emptySet())) {
                if (visited.add(neighbor)) {
                    queue.add(neighbor);
                }
            }
        }
        return result;
    }

    // ---- Getters / Setters ----

    public String getGraphId() {
        return graphId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<BRNode> getEvaluationOrder() {
        if (orderDirty) {
            rebuildTopologicalOrder();
            orderDirty = false;
        }
        return Collections.unmodifiableList(evaluationOrder);
    }

    public boolean hasDirtyNodes() {
        for (BRNode node : nodes.values()) {
            if (node.isEnabled() && node.isDirty()) return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return "NodeGraph[" + name + " nodes=" + nodes.size() + " wires=" + wires.size() + "]";
    }
}
