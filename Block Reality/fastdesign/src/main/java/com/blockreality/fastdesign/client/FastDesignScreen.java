package com.blockreality.fastdesign.client;

import com.blockreality.api.blueprint.Blueprint;
import com.blockreality.fastdesign.network.FdActionPacket;
import com.blockreality.fastdesign.network.FdNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

/**
 * Fast Design 三視角 CAD 介面 — v3fix §2.2 + UI 增強版
 *
 * 左側面板：正交投影（TOP / FRONT / SIDE）
 * 右側面板：藍圖資訊 + 工具列按鈕
 * 底部工具列：快捷操作按鈕（Undo / Copy / Paste / Export / Save）
 * 快捷鍵：Tab 切換視角, Z undo, C copy, V paste, E export
 */
@OnlyIn(Dist.CLIENT)
public class FastDesignScreen extends Screen {

    private static final Logger LOGGER = LogManager.getLogger("FastDesign");

    // ── Toolbar button dimensions ──
    private static final int BTN_W = 56;
    private static final int BTN_H = 18;
    private static final int BTN_GAP = 4;
    private static final int TOOLBAR_H = BTN_H + 12;

    public enum OrthoMode {
        TOP("Top (XZ)", 0, 2),
        FRONT("Front (XY)", 0, 1),
        SIDE("Side (ZY)", 2, 1);

        final String label;
        final int axisH;
        final int axisV;

        OrthoMode(String label, int axisH, int axisV) {
            this.label = label;
            this.axisH = axisH;
            this.axisV = axisV;
        }

        public OrthoMode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private OrthoMode currentMode = OrthoMode.TOP;
    private Blueprint blueprint;
    private List<Blueprint.BlueprintBlock> blocks;

    private boolean dragging = false;
    private int dragStartX, dragStartY;
    private int dragEndX, dragEndY;

    private int leftPanelX, leftPanelY, leftPanelW, leftPanelH;
    private int rightPanelX, rightPanelY, rightPanelW, rightPanelH;

    // ── Ortho grid transform（由 renderOrthoProjection 每幀更新，供 drag→block 反算使用） ──
    private int gridOffsetX, gridOffsetY;
    private int gridCellSize;
    private int gridMinH, gridMinV, gridMaxV;
    private java.util.List<Blueprint.BlueprintBlock> selectedBlocks = new java.util.ArrayList<>();

    public FastDesignScreen(Blueprint blueprint) {
        super(Component.literal("Fast Design CAD"));
        this.blueprint = blueprint;
        this.blocks = blueprint != null ? blueprint.getBlocks() : List.of();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        Preview3DRenderer.setCadModeActive(false);
        super.onClose();
    }

    @Override
    protected void init() {
        super.init();
        Preview3DRenderer.setCadModeActive(true);

        int margin = 8;

        // Layout: left 60%, right 40%, bottom toolbar
        leftPanelX = margin;
        leftPanelY = margin + 16;
        leftPanelW = (int) (width * 0.6) - margin * 2;
        leftPanelH = height - margin * 2 - 16 - TOOLBAR_H;

        rightPanelX = leftPanelX + leftPanelW + margin;
        rightPanelY = leftPanelY;
        rightPanelW = width - rightPanelX - margin;
        rightPanelH = leftPanelH;

        // ── Toolbar buttons (bottom bar) ──
        int toolbarY = height - TOOLBAR_H + 4;
        int startX = margin;

        addToolbarButton(startX, toolbarY, "Undo [Z]", 0xFFCC8800,
            btn -> sendAction(FdActionPacket.Action.UNDO, ""));
        startX += BTN_W + BTN_GAP;

        addToolbarButton(startX, toolbarY, "Copy [C]", 0xFF0088CC,
            btn -> sendAction(FdActionPacket.Action.COPY, ""));
        startX += BTN_W + BTN_GAP;

        addToolbarButton(startX, toolbarY, "Paste [V]", 0xFF00AA44,
            btn -> sendAction(FdActionPacket.Action.PASTE, ""));
        startX += BTN_W + BTN_GAP;

        addToolbarButton(startX, toolbarY, "Clear", 0xFFCC2222,
            btn -> sendAction(FdActionPacket.Action.CLEAR, ""));
        startX += BTN_W + BTN_GAP;

        addToolbarButton(startX, toolbarY, "Export [E]", 0xFF8844CC,
            btn -> sendAction(FdActionPacket.Action.EXPORT, ""));
        startX += BTN_W + BTN_GAP;

        addToolbarButton(startX, toolbarY, "Pos1", 0xFF44AA88,
            btn -> sendAction(FdActionPacket.Action.SET_POS1, ""));
        startX += BTN_W + BTN_GAP;

        addToolbarButton(startX, toolbarY, "Pos2", 0xFF44AA88,
            btn -> sendAction(FdActionPacket.Action.SET_POS2, ""));

        // ── Right panel buttons ──
        int rpBtnY = rightPanelY + rightPanelH - BTN_H * 3 - BTN_GAP * 3;
        int rpBtnW = rightPanelW - 16;

        addRenderableWidget(Button.builder(Component.literal("§eTop View"), btn -> {
            currentMode = OrthoMode.TOP;
        }).bounds(rightPanelX + 8, rpBtnY, rpBtnW, BTN_H).build());
        rpBtnY += BTN_H + BTN_GAP;

        addRenderableWidget(Button.builder(Component.literal("§eFront View"), btn -> {
            currentMode = OrthoMode.FRONT;
        }).bounds(rightPanelX + 8, rpBtnY, rpBtnW, BTN_H).build());
        rpBtnY += BTN_H + BTN_GAP;

        addRenderableWidget(Button.builder(Component.literal("§eSide View"), btn -> {
            currentMode = OrthoMode.SIDE;
        }).bounds(rightPanelX + 8, rpBtnY, rpBtnW, BTN_H).build());
    }

    private void addToolbarButton(int x, int y, String label, int color,
                                   Button.OnPress action) {
        addRenderableWidget(Button.builder(Component.literal(label), action)
            .bounds(x, y, BTN_W, BTN_H).build());
    }

    private void sendAction(FdActionPacket.Action action, String payload) {
        FdNetwork.CHANNEL.sendToServer(new FdActionPacket(action, payload));
    }

    // ════════════════════════════════════════════════════════════════
    // Rendering
    // ════════════════════════════════════════════════════════════════

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        renderBackground(gui);

        // ── Header ──
        gui.drawString(font, "§6Fast Design CAD §7— " + currentMode.label + "  §8[Tab] Switch  [Z/C/V/E] Actions",
            leftPanelX, leftPanelY - 12, 0xFFFFFFFF);

        // ── Left panel background ──
        gui.fill(leftPanelX, leftPanelY,
            leftPanelX + leftPanelW, leftPanelY + leftPanelH,
            0xFF1A1A2E);

        renderOrthoProjection(gui);

        // ── Drag selection overlay ──
        if (dragging) {
            int sx = Math.min(dragStartX, dragEndX);
            int sy = Math.min(dragStartY, dragEndY);
            int ex = Math.max(dragStartX, dragEndX);
            int ey = Math.max(dragStartY, dragEndY);
            gui.fill(sx, sy, ex, ey, 0x4000FF00);
            gui.fill(sx, sy, ex, sy + 1, 0xFF00FF00);
            gui.fill(sx, ey - 1, ex, ey, 0xFF00FF00);
            gui.fill(sx, sy, sx + 1, ey, 0xFF00FF00);
            gui.fill(ex - 1, sy, ex, ey, 0xFF00FF00);
        }

        // ── Right panel background ──
        gui.fill(rightPanelX, rightPanelY,
            rightPanelX + rightPanelW, rightPanelY + rightPanelH,
            0xFF16213E);

        renderStats(gui);

        // ── Toolbar background ──
        gui.fill(0, height - TOOLBAR_H, width, height, 0xD0101020);

        super.render(gui, mouseX, mouseY, partialTick);
    }

    private void renderOrthoProjection(GuiGraphics gui) {
        if (blocks.isEmpty()) {
            gui.drawString(font, "§7No blocks loaded",
                leftPanelX + 10, leftPanelY + 10, 0xFFAAAAAA);
            gui.drawString(font, "§8Use /fd cad or /fd cad <name>",
                leftPanelX + 10, leftPanelY + 24, 0xFF666666);
            return;
        }

        int minH = Integer.MAX_VALUE, maxH = Integer.MIN_VALUE;
        int minV = Integer.MAX_VALUE, maxV = Integer.MIN_VALUE;

        for (Blueprint.BlueprintBlock b : blocks) {
            int h = getCoord(b, currentMode.axisH);
            int v = getCoord(b, currentMode.axisV);
            minH = Math.min(minH, h);
            maxH = Math.max(maxH, h);
            minV = Math.min(minV, v);
            maxV = Math.max(maxV, v);
        }

        int rangeH = maxH - minH + 1;
        int rangeV = maxV - minV + 1;
        if (rangeH <= 0 || rangeV <= 0) return;

        int cellSize = Math.max(2, Math.min(
            (leftPanelW - 8) / rangeH,
            (leftPanelH - 8) / rangeV
        ));

        int offsetX = leftPanelX + (leftPanelW - rangeH * cellSize) / 2;
        int offsetY = leftPanelY + (leftPanelH - rangeV * cellSize) / 2;

        // 儲存 grid transform 供 drag→block 反算使用
        this.gridOffsetX = offsetX;
        this.gridOffsetY = offsetY;
        this.gridCellSize = cellSize;
        this.gridMinH = minH;
        this.gridMinV = minV;
        this.gridMaxV = maxV;

        // ── Grid lines ──
        for (int i = 0; i <= rangeH; i++) {
            int px = offsetX + i * cellSize;
            gui.fill(px, offsetY, px + 1, offsetY + rangeV * cellSize, 0x20FFFFFF);
        }
        for (int i = 0; i <= rangeV; i++) {
            int py = offsetY + i * cellSize;
            gui.fill(offsetX, py, offsetX + rangeH * cellSize, py + 1, 0x20FFFFFF);
        }

        // ── Blocks ──
        for (Blueprint.BlueprintBlock b : blocks) {
            int h = getCoord(b, currentMode.axisH) - minH;
            int v = getCoord(b, currentMode.axisV) - minV;

            if (currentMode != OrthoMode.TOP) {
                v = (maxV - minV) - v;
            }

            int px = offsetX + h * cellSize;
            int py = offsetY + v * cellSize;

            int color = getBlockColor(b.getBlockState());
            gui.fill(px + 1, py + 1, px + cellSize - 1, py + cellSize - 1, color);

            // 選取高亮（黃色邊框）
            if (selectedBlocks.contains(b)) {
                gui.fill(px, py, px + cellSize, py + 1, 0xFFFFFF00);
                gui.fill(px, py + cellSize - 1, px + cellSize, py + cellSize, 0xFFFFFF00);
                gui.fill(px, py, px + 1, py + cellSize, 0xFFFFFF00);
                gui.fill(px + cellSize - 1, py, px + cellSize, py + cellSize, 0xFFFFFF00);
            }
        }

        // ── Axis labels ──
        String hLabel = axisLabel(currentMode.axisH);
        String vLabel = axisLabel(currentMode.axisV);
        gui.drawString(font, hLabel + " \u2192", offsetX, leftPanelY + leftPanelH - 10, 0xFF888888);
        gui.drawString(font, vLabel + " \u2193", leftPanelX + 2, offsetY, 0xFF888888);

        // ── Block count on panel ──
        String blockInfo = selectedBlocks.isEmpty()
            ? String.format("§8%d blocks", blocks.size())
            : String.format("§e%d§8/%d selected", selectedBlocks.size(), blocks.size());
        gui.drawString(font, blockInfo,
            leftPanelX + leftPanelW - 80, leftPanelY + leftPanelH - 10, 0xFF666666);
    }

    private void renderStats(GuiGraphics gui) {
        int y = rightPanelY + 8;
        int x = rightPanelX + 8;
        int lineH = 12;

        gui.drawString(font, "§e\u2550\u2550\u2550 Blueprint Info \u2550\u2550\u2550", x, y, 0xFFFFFF00);
        y += lineH + 4;

        if (blueprint != null) {
            String bpName = blueprint.getName() != null ? blueprint.getName() : "(unnamed)";
            String bpAuthor = blueprint.getAuthor() != null ? blueprint.getAuthor() : "(unknown)";
            String bpVersion = String.valueOf(blueprint.getVersion());
            gui.drawString(font, "§fName: §a" + bpName, x, y, 0xFFCCCCCC);
            y += lineH;
            gui.drawString(font, String.format("§fSize: §a%dx%dx%d",
                blueprint.getSizeX(), blueprint.getSizeY(), blueprint.getSizeZ()), x, y, 0xFFCCCCCC);
            y += lineH;
            gui.drawString(font, "§fBlocks: §a" + blocks.size(), x, y, 0xFFCCCCCC);
            y += lineH;
            gui.drawString(font, "§fAuthor: §7" + bpAuthor, x, y, 0xFFCCCCCC);
            y += lineH;
            gui.drawString(font, "§fVersion: §7" + bpVersion, x, y, 0xFFCCCCCC);
            y += lineH * 2;
        } else {
            gui.drawString(font, "§7No blueprint loaded", x, y, 0xFF888888);
            y += lineH * 2;
        }

        // ── Keybindings help ──
        gui.drawString(font, "§e\u2550\u2550\u2550 Shortcuts \u2550\u2550\u2550", x, y, 0xFFFFFF00);
        y += lineH + 2;
        gui.drawString(font, "§7[Tab]  §fSwitch view", x, y, 0xFFAAAAAA); y += lineH;
        gui.drawString(font, "§7[Z]    §fUndo", x, y, 0xFFAAAAAA); y += lineH;
        gui.drawString(font, "§7[C]    §fCopy", x, y, 0xFFAAAAAA); y += lineH;
        gui.drawString(font, "§7[V]    §fPaste", x, y, 0xFFAAAAAA); y += lineH;
        gui.drawString(font, "§7[E]    §fExport NURBS", x, y, 0xFFAAAAAA); y += lineH;
        gui.drawString(font, "§7[Esc]  §fClose", x, y, 0xFFAAAAAA);
    }

    // ════════════════════════════════════════════════════════════════
    // Input handling
    // ════════════════════════════════════════════════════════════════

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Tab = switch view
        if (keyCode == 258) {
            currentMode = currentMode.next();
            return true;
        }
        // Z = undo
        if (keyCode == 90) {
            sendAction(FdActionPacket.Action.UNDO, "");
            return true;
        }
        // C = copy
        if (keyCode == 67) {
            sendAction(FdActionPacket.Action.COPY, "");
            return true;
        }
        // V = paste
        if (keyCode == 86) {
            sendAction(FdActionPacket.Action.PASTE, "");
            return true;
        }
        // E = export
        if (keyCode == 69) {
            sendAction(FdActionPacket.Action.EXPORT, "");
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isInLeftPanel((int) mouseX, (int) mouseY)) {
            dragging = true;
            dragStartX = (int) mouseX;
            dragStartY = (int) mouseY;
            dragEndX = dragStartX;
            dragEndY = dragStartY;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button,
                                 double dragX, double dragY) {
        if (dragging && button == 0) {
            dragEndX = (int) mouseX;
            dragEndY = (int) mouseY;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging && button == 0) {
            dragging = false;
            resolveBlockSelection();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    // ════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════

    /**
     * 將螢幕空間拖曳框反算回 ortho grid 座標，篩選落入範圍的方塊。
     */
    private void resolveBlockSelection() {
        selectedBlocks.clear();
        if (blocks.isEmpty() || gridCellSize <= 0) return;

        // 螢幕拖曳框 → grid cell 範圍
        int sx = Math.min(dragStartX, dragEndX);
        int sy = Math.min(dragStartY, dragEndY);
        int ex = Math.max(dragStartX, dragEndX);
        int ey = Math.max(dragStartY, dragEndY);

        int cellMinH = (sx - gridOffsetX) / gridCellSize;
        int cellMaxH = (ex - gridOffsetX) / gridCellSize;
        int cellMinV = (sy - gridOffsetY) / gridCellSize;
        int cellMaxV = (ey - gridOffsetY) / gridCellSize;

        // Clamp grid coordinates to valid bounds
        int gridWidth = gridMaxV - gridMinV + 1;
        int gridHeight = gridMaxV - gridMinV + 1;
        cellMinH = Math.max(0, Math.min(cellMinH, gridWidth - 1));
        cellMaxH = Math.max(0, Math.min(cellMaxH, gridWidth - 1));
        cellMinV = Math.max(0, Math.min(cellMinV, gridHeight - 1));
        cellMaxV = Math.max(0, Math.min(cellMaxV, gridHeight - 1));

        for (Blueprint.BlueprintBlock b : blocks) {
            int h = getCoord(b, currentMode.axisH) - gridMinH;
            int v = getCoord(b, currentMode.axisV) - gridMinV;
            if (currentMode != OrthoMode.TOP) {
                v = (gridMaxV - gridMinV) - v;
            }
            if (h >= cellMinH && h <= cellMaxH && v >= cellMinV && v <= cellMaxV) {
                selectedBlocks.add(b);
            }
        }

        LOGGER.debug("[FD-CAD] Drag selection: {} blocks selected", selectedBlocks.size());
    }

    private boolean isInLeftPanel(int x, int y) {
        return x >= leftPanelX && x <= leftPanelX + leftPanelW
            && y >= leftPanelY && y <= leftPanelY + leftPanelH;
    }

    private static int getCoord(Blueprint.BlueprintBlock b, int axis) {
        return switch (axis) {
            case 0 -> b.getRelX();
            case 1 -> b.getRelY();
            case 2 -> b.getRelZ();
            default -> 0;
        };
    }

    private static String axisLabel(int axis) {
        return switch (axis) {
            case 0 -> "X";
            case 1 -> "Y";
            case 2 -> "Z";
            default -> "?";
        };
    }

    private static int getBlockColor(BlockState state) {
        if (state == null || state.isAir()) return 0xFF333333;
        try {
            int mapColor = state.getMapColor(null, null).col;
            return 0xFF000000 | mapColor;
        } catch (RuntimeException e) {
            return 0xFF888888;
        }
    }
}
