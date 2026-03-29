package com.blockreality.fastdesign.client.node.canvas;

import com.blockreality.fastdesign.client.node.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

/**
 * 節點操作 Undo/Redo — 設計報告 §12.1 N2-10
 *
 * 使用 Command 模式追蹤節點圖操作。
 * 獨立於 FastDesign 的 UndoManager（那個追蹤方塊操作）。
 *
 * 支援操作：
 *   - 新增/移除節點
 *   - 建立/斷開連線
 *   - 移動節點
 */
public class NodeCanvasUndoManager {

    private static final Logger LOGGER = LogManager.getLogger("NodeCanvasUndo");
    private static final int MAX_HISTORY = 100;

    private final Deque<UndoAction> undoStack = new ArrayDeque<>();
    private final Deque<UndoAction> redoStack = new ArrayDeque<>();

    // ─── 記錄操作 ───

    public void recordAddNode(BRNode node) {
        push(new AddNodeAction(node));
    }

    public void recordRemoveNode(BRNode node, NodeGraph graph) {
        // 記錄被移除的連線（以便 undo 時還原）
        List<WireSnapshot> wires = new ArrayList<>();
        for (Wire w : graph.allWires()) {
            if (w.from().owner() == node || w.to().owner() == node) {
                wires.add(new WireSnapshot(w.from().qualifiedName(), w.to().qualifiedName()));
            }
        }
        push(new RemoveNodeAction(node, node.posX(), node.posY(), wires));
    }

    public void recordConnect(Wire wire) {
        push(new ConnectAction(wire.from().qualifiedName(), wire.to().qualifiedName()));
    }

    public void recordDisconnect(Wire wire) {
        push(new DisconnectAction(wire.from().qualifiedName(), wire.to().qualifiedName()));
    }

    /**
     * ★ ICReM-9: 記錄節點移動操作（支援多節點同時移動）。
     */
    public void recordMoveNodes(List<BRNode> nodes, java.util.Map<String, float[]> startPositions) {
        java.util.Map<BRNode, float[]> fromPos = new java.util.LinkedHashMap<>();
        java.util.Map<BRNode, float[]> toPos = new java.util.LinkedHashMap<>();
        for (BRNode node : nodes) {
            float[] start = startPositions.get(node.nodeId());
            if (start != null) {
                fromPos.put(node, new float[]{start[0], start[1]});
                toPos.put(node, new float[]{node.posX(), node.posY()});
            }
        }
        if (!fromPos.isEmpty()) {
            push(new MoveNodesAction(fromPos, toPos));
        }
    }

    // ─── Undo / Redo ───

    public void undo(NodeGraph graph) {
        if (undoStack.isEmpty()) return;
        UndoAction action = undoStack.pop();
        action.undo(graph);
        redoStack.push(action);
        LOGGER.debug("Undo: {}", action);
    }

    public void redo(NodeGraph graph) {
        if (redoStack.isEmpty()) return;
        UndoAction action = redoStack.pop();
        action.redo(graph);
        undoStack.push(action);
        LOGGER.debug("Redo: {}", action);
    }

    public boolean canUndo() { return !undoStack.isEmpty(); }
    public boolean canRedo() { return !redoStack.isEmpty(); }

    private void push(UndoAction action) {
        undoStack.push(action);
        redoStack.clear();
        if (undoStack.size() > MAX_HISTORY) {
            // 移除最舊的（底部）
            ((ArrayDeque<UndoAction>) undoStack).removeLast();
        }
    }

    // ─── Action 介面 ───

    private interface UndoAction {
        void undo(NodeGraph graph);
        void redo(NodeGraph graph);
    }

    // ─── 新增節點 ───
    private record AddNodeAction(BRNode node) implements UndoAction {
        @Override
        public void undo(NodeGraph graph) {
            graph.removeNode(node);
        }

        @Override
        public void redo(NodeGraph graph) {
            graph.addNode(node);
        }

        @Override
        public String toString() { return "AddNode(" + node.displayName() + ")"; }
    }

    // ─── 移除節點 ───
    private record RemoveNodeAction(BRNode node, float posX, float posY,
                                     List<WireSnapshot> wires) implements UndoAction {
        @Override
        public void undo(NodeGraph graph) {
            node.setPosition(posX, posY);
            graph.addNode(node);
            // 還原連線
            for (WireSnapshot ws : wires) {
                OutputPort from = graph.findOutputPort(ws.fromPath);
                InputPort to = graph.findInputPort(ws.toPath);
                if (from != null && to != null) {
                    graph.connect(from, to);
                }
            }
        }

        @Override
        public void redo(NodeGraph graph) {
            graph.removeNode(node);
        }

        @Override
        public String toString() { return "RemoveNode(" + node.displayName() + ")"; }
    }

    // ─── 建立連線 ───
    private record ConnectAction(String fromPath, String toPath) implements UndoAction {
        @Override
        public void undo(NodeGraph graph) {
            // 找到並斷開此連線
            for (Wire w : graph.allWires()) {
                if (w.serializeFromPath().equals(fromPath) && w.serializeToPath().equals(toPath)) {
                    graph.disconnect(w);
                    return;
                }
            }
        }

        @Override
        public void redo(NodeGraph graph) {
            OutputPort from = graph.findOutputPort(fromPath);
            InputPort to = graph.findInputPort(toPath);
            if (from != null && to != null) graph.connect(from, to);
        }

        @Override
        public String toString() { return "Connect(" + fromPath + " -> " + toPath + ")"; }
    }

    // ─── 斷開連線 ───
    private record DisconnectAction(String fromPath, String toPath) implements UndoAction {
        @Override
        public void undo(NodeGraph graph) {
            OutputPort from = graph.findOutputPort(fromPath);
            InputPort to = graph.findInputPort(toPath);
            if (from != null && to != null) graph.connect(from, to);
        }

        @Override
        public void redo(NodeGraph graph) {
            for (Wire w : graph.allWires()) {
                if (w.serializeFromPath().equals(fromPath) && w.serializeToPath().equals(toPath)) {
                    graph.disconnect(w);
                    return;
                }
            }
        }

        @Override
        public String toString() { return "Disconnect(" + fromPath + " -> " + toPath + ")"; }
    }

    // ─── 移動節點 ───
    /**
     * ★ ICReM-9: 節點移動 undo/redo 操作。
     */
    private static final class MoveNodesAction implements UndoAction {
        private final java.util.Map<BRNode, float[]> fromPositions;
        private final java.util.Map<BRNode, float[]> toPositions;

        MoveNodesAction(java.util.Map<BRNode, float[]> from, java.util.Map<BRNode, float[]> to) {
            this.fromPositions = from;
            this.toPositions = to;
        }

        @Override
        public void undo(NodeGraph graph) {
            for (var entry : fromPositions.entrySet()) {
                entry.getKey().setPosition(entry.getValue()[0], entry.getValue()[1]);
            }
        }

        @Override
        public void redo(NodeGraph graph) {
            for (var entry : toPositions.entrySet()) {
                entry.getKey().setPosition(entry.getValue()[0], entry.getValue()[1]);
            }
        }

        @Override
        public String toString() { return "MoveNodes(" + fromPositions.size() + " nodes)"; }
    }

    // ─── Wire 快照 ───
    private record WireSnapshot(String fromPath, String toPath) {}
}
