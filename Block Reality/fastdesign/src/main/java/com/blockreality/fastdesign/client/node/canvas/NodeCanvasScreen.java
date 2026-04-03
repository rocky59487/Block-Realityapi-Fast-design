package com.blockreality.fastdesign.client.node.canvas;

import com.blockreality.fastdesign.client.node.*;
import com.blockreality.fastdesign.client.node.NodeRegistry;
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

    /** ★ FTB-STYLE: 深色背景 — 對齊 FTB 模組包 UI 風格（更深沉、低飽和度） */
    private static final int BG_COLOR = 0xFF141420;
    private static final int GRID_COLOR = 0xFF1C1C30;
    private static final int GRID_MAJOR_COLOR = 0xFF242440;
    private static final float GRID_SPACING = 20.0f;
    private static final int GRID_MAJOR_EVERY = 5;

    private final NodeGraph graph;
    private final EvaluateScheduler scheduler;
    private final CanvasTransform transform = new CanvasTransform();
    private final NodeWidgetRenderer nodeRenderer = new NodeWidgetRenderer();
    private final WireRenderer wireRenderer = new WireRenderer();
    private final NodeTooltipRenderer tooltipRenderer = new NodeTooltipRenderer();
    private final PortInteraction portInteraction;
    private final BoxSelectionHandler boxSelection = new BoxSelectionHandler();
    private final NodeCanvasUndoManager undoManager = new NodeCanvasUndoManager();

    @Nullable private NodeSearchPanel searchPanel;

    // 選中的節點
    private final List<BRNode> selectedNodes = new ArrayList<>();
    @Nullable private BRNode dragNode;  // 正在拖曳的節點
    private float dragOffsetX, dragOffsetY;
    // ★ ICReM-9: 記錄拖曳起始位置（用於 move undo）
    private final java.util.Map<String, float[]> dragStartPositions = new java.util.HashMap<>();

    // Inline 控件拖曳
    @Nullable private InputPort draggingInlineSlider = null;

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
        // ★ 確保節點型別已註冊（registerAll 有內部 guard，重複呼叫安全）
        NodeRegistry.registerAll();

        // ★ Fix: 綁定 LivePreviewBridge 以確保節點系統能影響實際渲染
        com.blockreality.fastdesign.client.node.binding.LivePreviewBridge.getInstance().bindGraph(graph);

        LOGGER.debug("NodeCanvasScreen init: {}x{}, registered node types: {}",
                width, height, NodeRegistry.allTypeIds().size());
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

        // ★ ICReM-9: Tooltip 渲染 — 懸停在節點上顯示資訊
        if (dragNode == null && !portInteraction.isDragging() && !boxSelection.isSelecting()) {
            float cx = transform.toCanvasX(mouseX);
            float cy = transform.toCanvasY(mouseY);
            BRNode hoverNode = graph.nodeAtPoint(cx, cy);
            if (hoverNode != null) {
                tooltipRenderer.renderTooltip(gui, hoverNode, mouseX, mouseY);
            }
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

        // ★ 操作提示（畫布上方）
        gui.drawString(font, "§7[Tab] 新增節點 | [中鍵拖曳] 平移 | [滾輪] 縮放 | [左鍵] 拖曳/框選 | [右鍵] 斷線 | [Ctrl+S] 儲存",
                4, 4, 0xFF888888);

        // 空畫布時顯示大提示
        if (graph.nodeCount() == 0) {
            String hint1 = "§e按 Tab 或雙擊空白處新增節點";
            String hint2 = "§7Ctrl+D 複製 | Delete 刪除 | Ctrl+Z 還原";
            int w1 = font.width(hint1);
            int w2 = font.width(hint2);
            gui.drawString(font, hint1, (width - w1) / 2, height / 2 - 10, 0xFFFFCC00);
            gui.drawString(font, hint2, (width - w2) / 2, height / 2 + 6, 0xFF888888);
        }
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

            // 檢查是否點擊了 Inline 控件 (Slider / Checkbox)
            BRNode hit = graph.nodeAtPoint(cx, cy);
            if (hit != null && !hit.isCollapsed()) {
                int portIdx = 0;
                for (InputPort port : hit.inputs()) {
                    if (!port.isConnected()) {
                        float sy = transform.toScreenY(hit.posY() + 24.0f + portIdx * 20.0f);
                        float sx = transform.toScreenX(hit.posX());
                        float sw = transform.toScreenSize(hit.width());

                        if (port.type() == PortType.FLOAT || port.type() == PortType.INT) {
                            int sliderW = (int) transform.toScreenSize(40);
                            int sliderH = (int) transform.toScreenSize(10);
                            int sliderX = (int) (sx + sw - sliderW - 8);
                            int sliderY = (int) (sy - sliderH / 2);

                            if (mouseX >= sliderX && mouseX <= sliderX + sliderW && mouseY >= sliderY && mouseY <= sliderY + sliderH) {
                                draggingInlineSlider = port;
                                updateInlineSlider(port, (float) mouseX, sliderX, sliderW);
                                return true;
                            }
                        } else if (port.type() == PortType.BOOL) {
                            int boxSize = (int) transform.toScreenSize(10);
                            int boxX = (int) (sx + sw - boxSize - 8);
                            int boxY = (int) (sy - boxSize / 2);

                            if (mouseX >= boxX && mouseX <= boxX + boxSize && mouseY >= boxY && mouseY <= boxY + boxSize) {
                                boolean bVal = port.getRawValue() instanceof Boolean b && b;
                                port.setLocalValue(!bVal);
                                return true;
                            }
                        }
                    }
                    portIdx++;
                }
            }

            // 端口拖曳連線
            if (portInteraction.tryStartDrag(cx, cy, graph)) {
                return true;
            }

            // 節點拖曳
            if (hit != null) {
                if (!selectedNodes.contains(hit)) {
                    if (!hasShiftDown()) selectedNodes.clear();
                    selectedNodes.add(hit);
                }
                dragNode = hit;
                dragOffsetX = cx - hit.posX();
                dragOffsetY = cy - hit.posY();
                // ★ ICReM-9: 記錄拖曳前位置（用於 move undo）
                dragStartPositions.clear();
                for (BRNode n : selectedNodes) {
                    dragStartPositions.put(n.nodeId(), new float[]{n.posX(), n.posY()});
                }
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
        if (draggingInlineSlider != null && button == 0) {
            BRNode node = draggingInlineSlider.owner();
            if (node != null) {
                int portIdx = node.inputs().indexOf(draggingInlineSlider);
                float sx = transform.toScreenX(node.posX());
                float sw = transform.toScreenSize(node.width());
                int sliderW = (int) transform.toScreenSize(40);
                int sliderX = (int) (sx + sw - sliderW - 8);
                updateInlineSlider(draggingInlineSlider, (float) mouseX, sliderX, sliderW);
            }
            return true;
        }

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

    private void updateInlineSlider(InputPort port, float mouseX, int sliderX, int sliderW) {
        float pct = (mouseX - sliderX) / (float) sliderW;
        pct = Math.max(0, Math.min(1, pct));
        float min = port.min() == Float.NEGATIVE_INFINITY ? 0 : port.min();
        float max = port.max() == Float.POSITIVE_INFINITY ? 100 : port.max();
        if (max <= min) max = min + 1;
        float val = min + pct * (max - min);

        if (port.type() == PortType.INT) {
            port.setLocalValue(Math.round(val));
        } else {
            port.setLocalValue(val);
        }
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggingInlineSlider != null && button == 0) {
            draggingInlineSlider = null;
            return true;
        }

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
            // ★ ICReM-9: 記錄 move undo（比較拖曳前後位置）
            if (!dragStartPositions.isEmpty()) {
                boolean moved = false;
                for (BRNode n : selectedNodes) {
                    float[] start = dragStartPositions.get(n.nodeId());
                    if (start != null && (Math.abs(n.posX() - start[0]) > 0.5f
                                       || Math.abs(n.posY() - start[1]) > 0.5f)) {
                        moved = true;
                        break;
                    }
                }
                if (moved) {
                    undoManager.recordMoveNodes(selectedNodes, dragStartPositions);
                }
                dragStartPositions.clear();
            }
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

        // ★ review-fix ICReM-8: Ctrl+S → 儲存節點圖
        if (keyCode == 83 && hasControlDown()) { // Ctrl+S
            saveGraph();
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

    /**
     * ★ ICReM-9: 沿 Bezier 曲線取樣檢測碰撞，替代僅檢查中點的簡化版。
     */
    private boolean isNearWire(Wire wire, float cx, float cy) {
        OutputPort from = wire.from();
        InputPort to = wire.to();
        if (from.owner() == null || to.owner() == null) return false;

        int fromIdx = from.owner().outputs().indexOf(from);
        int toIdx = to.owner().inputs().indexOf(to);
        float x1 = from.owner().posX() + from.owner().width();
        float y1 = from.owner().posY() + PortInteraction.PORT_Y_START + fromIdx * PortInteraction.PORT_SPACING;
        float x2 = to.owner().posX();
        float y2 = to.owner().posY() + PortInteraction.PORT_Y_START + toIdx * PortInteraction.PORT_SPACING;

        // Bezier 控制點（與 WireRenderer 一致）
        float hdx = Math.abs(x2 - x1);
        float vdy = Math.abs(y2 - y1);
        float tangent = Math.max(hdx * 0.5f, Math.min(vdy * 0.3f, 80.0f));
        tangent = Math.max(tangent, 30.0f);
        float bx1 = x1 + tangent, by1 = y1;
        float bx2 = x2 - tangent, by2 = y2;

        // 沿曲線取 16 個樣本點
        float hitDist = 8.0f / transform.zoom(); // zoom 自適應
        for (int i = 0; i <= 16; i++) {
            float t = i / 16.0f;
            float it = 1 - t;
            float px = it*it*it*x1 + 3*it*it*t*bx1 + 3*it*t*t*bx2 + t*t*t*x2;
            float py = it*it*it*y1 + 3*it*it*t*by1 + 3*it*t*t*by2 + t*t*t*y2;
            float dx = cx - px, dy = cy - py;
            if (dx*dx + dy*dy < hitDist * hitDist) return true;
        }
        return false;
    }

    // ─── 儲存 ───

    /**
     * ★ review-fix ICReM-8: Ctrl+S 儲存節點圖到檔案。
     * ★ SAVE-FIX: 使用 fastdesign NodeGraphIO 而非 API 層的空轉換。
     */
    private void saveGraph() {
        try {
            Path configDir = net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath()
                    .resolve("config/blockreality/node_graphs");
            java.nio.file.Files.createDirectories(configDir);
            Path target = savePath != null ? savePath
                    : configDir.resolve("active_render.json");
            NodeGraphIO.save(graph, target);
            LOGGER.info("節點圖已儲存至 {}", target);
        } catch (Exception e) {
            LOGGER.error("儲存節點圖失敗: {}", e.getMessage(), e);
        }
    }

    public void setSavePath(Path path) {
        this.savePath = path;
    }

    // ─── 關閉時自動儲存 ───

    /**
     * ★ SAVE-ON-CLOSE: 關閉節點編輯器時自動儲存圖表狀態。
     * 確保使用者不會因忘記 Ctrl+S 而遺失變更。
     */
    @Override
    public void onClose() {
        // 自動儲存
        if (graph.nodeCount() > 0) {
            saveGraph();
            LOGGER.info("節點編輯器關閉，自動儲存完成");
        }

        // 解除 LivePreviewBridge 綁定
        com.blockreality.fastdesign.client.node.binding.LivePreviewBridge.getInstance().unbind();

        super.onClose();
    }

    // ─── 屬性 ───

    @Override
    public boolean isPauseScreen() { return false; }

    public NodeGraph getGraph()            { return graph; }
    public CanvasTransform getTransform()  { return transform; }
    public List<BRNode> getSelectedNodes() { return selectedNodes; }
}
