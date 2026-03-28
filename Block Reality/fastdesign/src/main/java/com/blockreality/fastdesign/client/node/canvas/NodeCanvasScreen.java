package com.blockreality.fastdesign.client.node.canvas;

import com.blockreality.fastdesign.client.node.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 節點畫布主 Screen — 設計報告 §10.3, §12.1 N2-1
 *
 * Grasshopper 風格的無限平移/縮放 2D 畫布。
 * 繼承 Minecraft Screen，使用 GuiGraphics + 自定義渲染。
 *
 * 操作：
 *   - 中鍵拖曳：平移
 *   - 滾輪：縮放
 *   - 左鍵拖曳空白：框選
 *   - 左鍵拖曳端口：連線
 *   - Tab / 雙擊空白：搜尋面板
 *   - Ctrl+G：建立群組
 *   - Ctrl+Z/Y：Undo/Redo
 *   - F：全部適配
 *   - Delete：刪除選中
 */
@OnlyIn(Dist.CLIENT)
public class NodeCanvasScreen extends Screen {

    private static final Logger LOGGER = LogManager.getLogger("NodeCanvas");

    /** 深色網格背景 */
    private static final int BG_COLOR = 0xFF1A1A2E;
    private static final int GRID_COLOR = 0xFF222240;
    private static final int GRID_MAJOR_COLOR = 0xFF2A2A50;
    private static final float GRID_SPACING = 20.0f;
    private static final int GRID_MAJOR_EVERY = 5;

    private final NodeGraph graph;
    private final EvaluateScheduler scheduler;
    private final CanvasTransform transform = new CanvasTransform();
    private final NodeWidgetRenderer nodeRenderer = new NodeWidgetRenderer();
    private final WireRenderer wireRenderer = new WireRenderer();
    private final PortInteraction portInteraction;
    private final BoxSelectionHandler boxSelection = new BoxSelectionHandler();
    private final NodeCanvasUndoManager undoManager = new NodeCanvasUndoManager();

    @Nullable private NodeSearchPanel searchPanel;

    // 選中的節點
    private final List<BRNode> selectedNodes = new ArrayList<>();
    @Nullable private BRNode dragNode;  // 正在拖曳的節點
    private float dragOffsetX, dragOffsetY;

    // 中鍵平移
    private boolean panning = false;
    private double panStartX, panStartY;

    // 雙擊偵測
    private long lastClickTime;
    private double lastClickX, lastClickY;

    @Nullable private Path savePath;

    public NodeCanvasScreen(NodeGraph graph) {
        super(Component.literal("Node Graph Editor"));
        this.graph = graph;
        this.scheduler = new EvaluateScheduler(graph);
        this.portInteraction = new PortInteraction(graph, transform);
    }

    public NodeCanvasScreen() {
        this(new NodeGraph());
    }

    // ─── 初始化 ───

    @Override
    protected void init() {
        super.init();
        LOGGER.debug("NodeCanvasScreen init: {}x{}", width, height);
    }

    // ─── 渲染 ───

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        // 每幀評估髒節點
        scheduler.evaluateDirty();

        // 背景
        gui.fill(0, 0, width, height, BG_COLOR);

        // 網格
        renderGrid(gui);

        // 群組（在節點下面）
        for (NodeGroup group : graph.allGroups()) {
            renderGroup(gui, group);
        }

        // 連線
        for (Wire wire : graph.allWires()) {
            if (wire.isConnected()) {
                wireRenderer.renderWire(gui, wire, transform, partialTick);
            }
        }

        // 拖曳中的臨時連線
        if (portInteraction.isDragging()) {
            portInteraction.renderDragWire(gui, mouseX, mouseY, transform);
        }

        // 節點
        for (BRNode node : graph.topologicalOrder()) {
            boolean selected = selectedNodes.contains(node);
            nodeRenderer.renderNode(gui, node, transform, selected, mouseX, mouseY);
        }

        // 框選矩形
        if (boxSelection.isSelecting()) {
            boxSelection.renderSelectionRect(gui);
        }

        // 搜尋面板
        if (searchPanel != null && searchPanel.isVisible()) {
            searchPanel.render(gui, mouseX, mouseY, partialTick);
        }

        // HUD 資訊
        renderHUD(gui);

        super.render(gui, mouseX, mouseY, partialTick);
    }

    private void renderGrid(GuiGraphics gui) {
        float gridScreenSize = GRID_SPACING * transform.zoom();
        if (gridScreenSize < 4) return;  // 太密就不畫

        float startX = transform.toScreenX(
                (float) Math.floor(transform.toCanvasX(0) / GRID_SPACING) * GRID_SPACING);
        float startY = transform.toScreenY(
                (float) Math.floor(transform.toCanvasY(0) / GRID_SPACING) * GRID_SPACING);

        int canvasStartCol = (int) Math.floor(transform.toCanvasX(0) / GRID_SPACING);
        int canvasStartRow = (int) Math.floor(transform.toCanvasY(0) / GRID_SPACING);

        for (float x = startX; x < width; x += gridScreenSize) {
            int col = canvasStartCol + (int) ((x - startX) / gridScreenSize);
            int color = (col % GRID_MAJOR_EVERY == 0) ? GRID_MAJOR_COLOR : GRID_COLOR;
            gui.fill((int) x, 0, (int) x + 1, height, color);
        }
        for (float y = startY; y < height; y += gridScreenSize) {
            int row = canvasStartRow + (int) ((y - startY) / gridScreenSize);
            int color = (row % GRID_MAJOR_EVERY == 0) ? GRID_MAJOR_COLOR : GRID_COLOR;
            gui.fill(0, (int) y, width, (int) y + 1, color);
        }
    }

    private void renderGroup(GuiGraphics gui, NodeGroup group) {
        float[] bounds = group.computeBounds(graph);
        int sx = (int) transform.toScreenX(bounds[0]);
        int sy = (int) transform.toScreenY(bounds[1]);
        int sw = (int) transform.toScreenSize(bounds[2]);
        int sh = (int) transform.toScreenSize(bounds[3]);

        // 半透明背景
        int bgColor = (0x22 << 24) | (group.color() & 0x00FFFFFF);
        gui.fill(sx, sy, sx + sw, sy + sh, bgColor);

        // 標題
        if (transform.zoom() > 0.3f) {
            gui.drawString(font, group.name(), sx + 4, sy + 4, group.color());
        }
    }

    private void renderHUD(GuiGraphics gui) {
        String info = String.format("Nodes: %d | Wires: %d | Zoom: %.0f%% | Eval: %.1fms",
                graph.nodeCount(), graph.wireCount(),
                transform.zoom() * 100, scheduler.totalEvalTimeMs());
        gui.drawString(font, info, 4, height - 12, 0xFFAAAAAA);
    }

    // ─── 滑鼠事件 ───

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 搜尋面板優先
        if (searchPanel != null && searchPanel.isVisible()) {
            if (searchPanel.mouseClicked(mouseX, mouseY, button)) return true;
            searchPanel.close();
        }

        float cx = transform.toCanvasX((float) mouseX);
        float cy = transform.toCanvasY((float) mouseY);

        if (button == 0) { // 左鍵
            // 雙擊偵測 → 搜尋面板
            long now = System.currentTimeMillis();
            if (now - lastClickTime < 400
                    && Math.abs(mouseX - lastClickX) < 5
                    && Math.abs(mouseY - lastClickY) < 5) {
                openSearchPanel((float) mouseX, (float) mouseY);
                lastClickTime = 0;
                return true;
            }
            lastClickTime = now;
            lastClickX = mouseX;
            lastClickY = mouseY;

            // 端口拖曳連線
            if (portInteraction.tryStartDrag(cx, cy, graph)) {
                return true;
            }

            // 節點拖曳
            BRNode hit = graph.nodeAtPoint(cx, cy);
            if (hit != null) {
                if (!selectedNodes.contains(hit)) {
                    if (!hasShiftDown()) selectedNodes.clear();
                    selectedNodes.add(hit);
                }
                dragNode = hit;
                dragOffsetX = cx - hit.posX();
                dragOffsetY = cy - hit.posY();
                return true;
            }

            // 空白 → 框選
            if (!hasShiftDown()) selectedNodes.clear();
            boxSelection.startSelection((float) mouseX, (float) mouseY);
            return true;
        }

        if (button == 2) { // 中鍵
            panning = true;
            panStartX = mouseX;
            panStartY = mouseY;
            return true;
        }

        if (button == 1) { // 右鍵
            // 右鍵連線 → 斷開
            for (Wire w : graph.allWires()) {
                if (isNearWire(w, cx, cy)) {
                    undoManager.recordDisconnect(w);
                    graph.disconnect(w);
                    return true;
                }
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (panning && button == 2) {
            transform.panByScreen((float) (mouseX - panStartX), (float) (mouseY - panStartY));
            panStartX = mouseX;
            panStartY = mouseY;
            return true;
        }

        if (portInteraction.isDragging()) {
            return true; // 端口連線拖曳中，render 時繪製
        }

        if (dragNode != null && button == 0) {
            float cx = transform.toCanvasX((float) mouseX);
            float cy = transform.toCanvasY((float) mouseY);
            float newX = cx - dragOffsetX;
            float newY = cy - dragOffsetY;

            // 移動選中的所有節點
            float nodeDx = newX - dragNode.posX();
            float nodeDy = newY - dragNode.posY();
            for (BRNode node : selectedNodes) {
                node.setPosition(node.posX() + nodeDx, node.posY() + nodeDy);
            }
            return true;
        }

        if (boxSelection.isSelecting() && button == 0) {
            boxSelection.updateSelection((float) mouseX, (float) mouseY);
            return true;
        }

        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 2) {
            panning = false;
            return true;
        }

        if (portInteraction.isDragging() && button == 0) {
            float cx = transform.toCanvasX((float) mouseX);
            float cy = transform.toCanvasY((float) mouseY);
            Wire wire = portInteraction.finishDrag(cx, cy, graph);
            if (wire != null) {
                undoManager.recordConnect(wire);
            }
            return true;
        }

        if (dragNode != null && button == 0) {
            dragNode = null;
            return true;
        }

        if (boxSelection.isSelecting() && button == 0) {
            boxSelection.finishSelection();
            float[] rect = boxSelection.getCanvasRect(transform);
            selectedNodes.clear();
            selectedNodes.addAll(graph.nodesInRect(rect[0], rect[1], rect[2], rect[3]));
            return true;
        }

        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (searchPanel != null && searchPanel.isVisible()) {
            return searchPanel.mouseScrolled(mouseX, mouseY, delta);
        }
        transform.zoomAt((float) mouseX, (float) mouseY, (float) delta);
        return true;
    }

    // ─── 鍵盤事件 ───

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (searchPanel != null && searchPanel.isVisible()) {
            if (searchPanel.keyPressed(keyCode, scanCode, modifiers)) return true;
            if (keyCode == 256) { // ESC
                searchPanel.close();
                return true;
            }
        }

        // Tab → 搜尋面板
        if (keyCode == 258) { // Tab
            openSearchPanel(width / 2.0f, height / 2.0f);
            return true;
        }

        // F → 全部適配
        if (keyCode == 70 && !hasControlDown()) { // F
            fitAllNodes();
            return true;
        }

        // Delete → 刪除選中
        if (keyCode == 261) { // Delete
            deleteSelectedNodes();
            return true;
        }

        // Ctrl+G → 建立群組
        if (keyCode == 71 && hasControlDown()) { // Ctrl+G
            createGroupFromSelection();
            return true;
        }

        // Ctrl+Z → Undo
        if (keyCode == 90 && hasControlDown() && !hasShiftDown()) {
            undoManager.undo(graph);
            return true;
        }

        // Ctrl+Y / Ctrl+Shift+Z → Redo
        if ((keyCode == 89 && hasControlDown())
                || (keyCode == 90 && hasControlDown() && hasShiftDown())) {
            undoManager.redo(graph);
            return true;
        }

        // Ctrl+D → 複製選中
        if (keyCode == 68 && hasControlDown()) {
            duplicateSelectedNodes();
            return true;
        }

        // Ctrl+A → 全選
        if (keyCode == 65 && hasControlDown()) {
            selectedNodes.clear();
            selectedNodes.addAll(graph.allNodes());
            return true;
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (searchPanel != null && searchPanel.isVisible()) {
            return searchPanel.charTyped(c, modifiers);
        }
        return super.charTyped(c, modifiers);
    }

    // ─── 操作方法 ───

    private void openSearchPanel(float screenX, float screenY) {
        searchPanel = new NodeSearchPanel(this, screenX, screenY);
    }

    public void addNodeFromSearch(String typeId, float screenX, float screenY) {
        BRNode node = NodeRegistry.create(typeId);
        if (node == null) return;
        node.setPosition(transform.toCanvasX(screenX), transform.toCanvasY(screenY));
        graph.addNode(node);
        undoManager.recordAddNode(node);
        if (searchPanel != null) searchPanel.close();
    }

    private void deleteSelectedNodes() {
        for (BRNode node : new ArrayList<>(selectedNodes)) {
            undoManager.recordRemoveNode(node, graph);
            graph.removeNode(node);
        }
        selectedNodes.clear();
    }

    private void createGroupFromSelection() {
        if (selectedNodes.size() < 2) return;
        NodeGroup group = new NodeGroup("Group", 0x44FFFFFF);
        for (BRNode node : selectedNodes) {
            group.addNode(node);
        }
        graph.addGroup(group);
    }

    private void duplicateSelectedNodes() {
        // 簡化版：只複製節點，不複製連線
        List<BRNode> newNodes = new ArrayList<>();
        for (BRNode node : selectedNodes) {
            BRNode copy = NodeRegistry.create(node.typeId());
            if (copy == null) continue;
            copy.setPosition(node.posX() + 20, node.posY() + 20);
            // 複製輸入值
            for (int i = 0; i < node.inputs().size() && i < copy.inputs().size(); i++) {
                InputPort src = node.inputs().get(i);
                InputPort dst = copy.inputs().get(i);
                if (!src.isConnected()) {
                    dst.setLocalValue(src.getRawValue());
                }
            }
            graph.addNode(copy);
            newNodes.add(copy);
        }
        selectedNodes.clear();
        selectedNodes.addAll(newNodes);
    }

    private void fitAllNodes() {
        if (graph.nodeCount() == 0) {
            transform.reset();
            return;
        }
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (BRNode node : graph.allNodes()) {
            minX = Math.min(minX, node.posX());
            minY = Math.min(minY, node.posY());
            maxX = Math.max(maxX, node.posX() + node.width());
            maxY = Math.max(maxY, node.posY() + node.height());
        }
        transform.fitRect(minX, minY, maxX - minX, maxY - minY, width, height);
    }

    private boolean isNearWire(Wire wire, float cx, float cy) {
        // 簡化：檢查點到連線中點的距離
        OutputPort from = wire.from();
        InputPort to = wire.to();
        if (from.owner() == null || to.owner() == null) return false;

        float x1 = from.owner().posX() + from.owner().width();
        float y1 = from.owner().posY() + 24 + from.owner().outputs().indexOf(from) * 20 + 10;
        float x2 = to.owner().posX();
        float y2 = to.owner().posY() + 24 + to.owner().inputs().indexOf(to) * 20 + 10;

        float midX = (x1 + x2) / 2;
        float midY = (y1 + y2) / 2;
        float dist = (float) Math.sqrt((cx - midX) * (cx - midX) + (cy - midY) * (cy - midY));
        return dist < 10;
    }

    // ─── 屬性 ───

    @Override
    public boolean isPauseScreen() { return false; }

    public NodeGraph getGraph()            { return graph; }
    public CanvasTransform getTransform()  { return transform; }
    public List<BRNode> getSelectedNodes() { return selectedNodes; }
}
