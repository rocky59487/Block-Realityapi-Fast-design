package com.blockreality.fastdesign.client;

import com.blockreality.fastdesign.client.node.canvas.NodeCanvasScreen;
import com.blockreality.fastdesign.client.node.panel.SimplifiedSettingsScreen;
import com.blockreality.fastdesign.network.FdActionPacket;
import com.blockreality.fastdesign.network.FdNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Control Panel 主面板 — Level 3
 *
 * 400×310 置中面板，分四個區塊:
 * - 建築操作 (實心、牆壁、拱門、斜撐、樓板、鋼筋網)
 * - 材質選擇 (混凝土、鋼筋、鋼材、木材、自訂)
 * - 編輯工具 (複製、粘貼、鏡像、旋轉、填充、替換、清除、還原)
 * - 進階功能 (儲存、載入、匯出、全息、CAD)
 *
 * 所有操作透過 FdActionPacket 發送到伺服器端處理。
 */
@OnlyIn(Dist.CLIENT)
public class ControlPanelScreen extends Screen {

    // ─── 面板尺寸 ───
    private static final int PANEL_W = 400;
    private static final int PANEL_H = 310;

    // ─── 按鈕尺寸 ───
    private static final int BTN_W = 80;
    private static final int BTN_H = 20;
    private static final int BTN_GAP = 4;

    // ─── 顏色 ───
    private static final int BG_COLOR      = 0xCC1A1A2E;  // 深藍紫半透明
    private static final int SECTION_BG    = 0x88000000;   // 區塊底色
    private static final int TITLE_COLOR   = 0xFFFF9900;   // 橙色標題
    private static final int SECTION_TITLE = 0xFFFFCC00;   // 區塊標題
    private static final int INFO_COLOR    = 0xFFAABBCC;   // 狀態資訊

    // ─── 面板原點 (init 時計算) ───
    private int panelX;
    private int panelY;

    // ─── 輸入框 ───
    private EditBox blueprintNameBox;
    private EditBox customBlockBox;

    // ─── 材質選擇按鈕（用於高亮當前選中） ───
    private Button[] materialButtons;

    public ControlPanelScreen() {
        super(Component.literal("Fast Design Control Panel"));
    }

    @Override
    protected void init() {
        panelX = (this.width - PANEL_W) / 2;
        panelY = (this.height - PANEL_H) / 2;

        // ════════════════════════════════════════
        // Section 1: 建築操作 (左上)
        // ════════════════════════════════════════
        int sec1X = panelX + 10;
        int sec1Y = panelY + 30;

        addBuildButton(sec1X,               sec1Y,      "實心方塊", FdActionPacket.Action.BUILD_SOLID, "填滿實心方塊");
        addBuildButton(sec1X + BTN_W + BTN_GAP, sec1Y,  "空心牆壁", FdActionPacket.Action.BUILD_WALLS, "建造空心牆壁");
        addBuildButton(sec1X,               sec1Y + BTN_H + BTN_GAP, "拱門",   FdActionPacket.Action.BUILD_ARCH, "建造拱門形狀");
        addBuildButton(sec1X + BTN_W + BTN_GAP, sec1Y + BTN_H + BTN_GAP, "斜撐",   FdActionPacket.Action.BUILD_BRACE, "建造斜撐結構");
        addBuildButton(sec1X,               sec1Y + (BTN_H + BTN_GAP) * 2, "樓板",   FdActionPacket.Action.BUILD_SLAB, "建造樓板結構");
        addBuildButton(sec1X + BTN_W + BTN_GAP, sec1Y + (BTN_H + BTN_GAP) * 2, "鋼筋網", FdActionPacket.Action.BUILD_REBAR, "配置鋼筋網");

        // ════════════════════════════════════════
        // Section 2: 材質選擇 (右上)
        // ════════════════════════════════════════
        int sec2X = panelX + PANEL_W / 2 + 10;
        int sec2Y = panelY + 30;

        ControlPanelState.MaterialChoice[] choices = ControlPanelState.MaterialChoice.values();
        materialButtons = new Button[choices.length];

        for (int i = 0; i < choices.length; i++) {
            final ControlPanelState.MaterialChoice mat = choices[i];
            int bx = sec2X + (i % 2) * (BTN_W + BTN_GAP);
            int by = sec2Y + (i / 2) * (BTN_H + BTN_GAP);

            materialButtons[i] = addRenderableWidget(
                Button.builder(Component.literal(mat.getLabel()), btn -> {
                    ControlPanelState.setSelectedMaterial(mat);
                    refreshMaterialButtons();
                })
                .bounds(bx, by, BTN_W, BTN_H)
                .build()
            );
        }
        refreshMaterialButtons();

        // 自訂方塊 ID 輸入框
        int customBoxY = sec2Y + ((choices.length + 1) / 2) * (BTN_H + BTN_GAP) + 4;
        customBlockBox = new EditBox(this.font, sec2X, customBoxY,
            BTN_W * 2 + BTN_GAP, BTN_H, Component.literal("自訂方塊 ID"));
        customBlockBox.setMaxLength(64);
        customBlockBox.setValue(ControlPanelState.getCustomBlockId());
        customBlockBox.setResponder(val -> ControlPanelState.setCustomBlockId(val));
        customBlockBox.setVisible(
            ControlPanelState.getSelectedMaterial() == ControlPanelState.MaterialChoice.CUSTOM);
        addRenderableWidget(customBlockBox);

        // ════════════════════════════════════════
        // Section 3: 編輯工具 (左下)
        // ════════════════════════════════════════
        int sec3X = panelX + 10;
        int sec3Y = panelY + 140;

        addActionButton(sec3X,               sec3Y,                         "複製", FdActionPacket.Action.COPY, "複製選取的方塊");
        addActionButton(sec3X + BTN_W + BTN_GAP, sec3Y,                    "粘貼預覽", FdActionPacket.Action.PASTE, "預覽剪貼簿內容");
        // 鏡像/旋轉帶預設 payload
        addActionButton(sec3X,               sec3Y + BTN_H + BTN_GAP,      "鏡像 X",
            FdActionPacket.Action.MIRROR, () -> "x", "沿 X 軸鏡像");
        addActionButton(sec3X + BTN_W + BTN_GAP, sec3Y + BTN_H + BTN_GAP,  "旋轉 90°",
            FdActionPacket.Action.ROTATE, () -> "90", "順時針旋轉 90 度");
        // 填充/替換帶材質 payload
        addBuildButton(sec3X,               sec3Y + (BTN_H + BTN_GAP) * 2, "填充", FdActionPacket.Action.FILL, "填滿選取區域");
        addBuildButton(sec3X + BTN_W + BTN_GAP, sec3Y + (BTN_H + BTN_GAP) * 2, "替換", FdActionPacket.Action.REPLACE, "替換選取區域內的方塊");
        addActionButton(sec3X,               sec3Y + (BTN_H + BTN_GAP) * 3, "清除", FdActionPacket.Action.CLEAR, "清除選取區域內的方塊");
        addActionButton(sec3X + BTN_W + BTN_GAP, sec3Y + (BTN_H + BTN_GAP) * 3, "還原", FdActionPacket.Action.UNDO, "復原上一步操作");

        // 粘貼確認/取消按鈕
        addActionButton(sec3X,               sec3Y + (BTN_H + BTN_GAP) * 4, "✓ 確認放置", FdActionPacket.Action.PASTE_CONFIRM, "確認並放置預覽的方塊");
        addActionButton(sec3X + BTN_W + BTN_GAP, sec3Y + (BTN_H + BTN_GAP) * 4, "✗ 取消貼上", FdActionPacket.Action.PASTE_CANCEL, "取消貼上操作");

        // ════════════════════════════════════════
        // Section 4: 進階功能 (右下)
        // ════════════════════════════════════════
        int sec4X = panelX + PANEL_W / 2 + 10;
        int sec4Y = panelY + 140;

        // 藍圖名稱輸入框
        blueprintNameBox = new EditBox(this.font, sec4X, sec4Y,
            BTN_W * 2 + BTN_GAP, BTN_H, Component.literal("藍圖名稱"));
        blueprintNameBox.setMaxLength(64);
        blueprintNameBox.setValue("my_blueprint");
        addRenderableWidget(blueprintNameBox);

        int advBtnY = sec4Y + BTN_H + BTN_GAP + 2;
        addRenderableWidget(
            Button.builder(Component.literal("儲存藍圖"), btn -> {
                String name = blueprintNameBox.getValue();
                if (name == null || name.trim().isEmpty()) {
                    // Feedback UI enhancement: change color or provide hint if empty
                    blueprintNameBox.setFocused(true);
                } else {
                    FdNetwork.CHANNEL.sendToServer(new FdActionPacket(FdActionPacket.Action.SAVE, name));
                }
            }).tooltip(Tooltip.create(Component.literal("將目前選取範圍儲存為藍圖"))).bounds(sec4X, advBtnY, BTN_W, BTN_H).build()
        );

        addActionButton(sec4X + BTN_W + BTN_GAP,  advBtnY, "載入藍圖",
            FdActionPacket.Action.LOAD, () -> blueprintNameBox.getValue(), "從藍圖檔案載入結構");

        advBtnY += BTN_H + BTN_GAP;
        addActionButton(sec4X,                     advBtnY, "NURBS 匯出", FdActionPacket.Action.EXPORT, "匯出為 NURBS 格式");
        // 全息切換是客戶端操作，直接切換 HologramState
        addRenderableWidget(
            Button.builder(Component.literal("全息切換"), btn -> {
                HologramState.toggleVisible();
            }).tooltip(Tooltip.create(Component.literal("切換全息投影顯示")))
            .bounds(sec4X + BTN_W + BTN_GAP, advBtnY, BTN_W, BTN_H)
            .build()
        );

        advBtnY += BTN_H + BTN_GAP;
        addActionButton(sec4X,                     advBtnY, "CAD 檢視",
            FdActionPacket.Action.OPEN_CAD, "開啟 CAD 檢視畫面");

        // ═══ 節點化設定入口 ═══
        advBtnY += BTN_H + BTN_GAP;
        addRenderableWidget(
            Button.builder(Component.literal("§a節點編輯器"), btn -> {
                Minecraft.getInstance().setScreen(new NodeCanvasScreen());
            }).tooltip(Tooltip.create(Component.literal("開啟節點編輯器")))
            .bounds(sec4X, advBtnY, BTN_W, BTN_H)
            .build()
        );
        addRenderableWidget(
            Button.builder(Component.literal("§b視訊設定"), btn -> {
                Minecraft.getInstance().setScreen(new SimplifiedSettingsScreen(this));
            }).tooltip(Tooltip.create(Component.literal("開啟視訊設定")))
            .bounds(sec4X + BTN_W + BTN_GAP, advBtnY, BTN_W, BTN_H)
            .build()
        );

        // ═══ 關閉按鈕 ═══
        addRenderableWidget(
            Button.builder(Component.literal("✕ 關閉"), btn -> onClose()).tooltip(Tooltip.create(Component.literal("關閉控制面板")))
                .bounds(panelX + PANEL_W - 65, panelY + PANEL_H - 26, 55, 18)
                .build()
        );
    }

    // ─── 建築按鈕 (帶材質 payload) ───
    private void addBuildButton(int x, int y, String label, FdActionPacket.Action action, String tooltipText) {
        addRenderableWidget(
            Button.builder(Component.literal(label), btn -> {
                String payload = ControlPanelState.encodePayload();
                FdNetwork.CHANNEL.sendToServer(new FdActionPacket(action, payload));
            }).tooltip(Tooltip.create(Component.literal(tooltipText)))
            .bounds(x, y, BTN_W, BTN_H)
            .build()
        );
    }

    // ─── 操作按鈕 (無 payload) ───
    private void addActionButton(int x, int y, String label, FdActionPacket.Action action, String tooltipText) {
        addRenderableWidget(
            Button.builder(Component.literal(label), btn -> {
                FdNetwork.CHANNEL.sendToServer(new FdActionPacket(action));
            }).tooltip(Tooltip.create(Component.literal(tooltipText)))
            .bounds(x, y, BTN_W, BTN_H)
            .build()
        );
    }

    // ─── 操作按鈕 (動態 payload) ───
    private void addActionButton(int x, int y, String label,
                                  FdActionPacket.Action action,
                                  java.util.function.Supplier<String> payloadSupplier,
                                  String tooltipText) {
        addRenderableWidget(
            Button.builder(Component.literal(label), btn -> {
                FdNetwork.CHANNEL.sendToServer(
                    new FdActionPacket(action, payloadSupplier.get()));
            }).tooltip(Tooltip.create(Component.literal(tooltipText)))
            .bounds(x, y, BTN_W, BTN_H)
            .build()
        );
    }

    // ─── 刷新材質按鈕外觀 ───
    private void refreshMaterialButtons() {
        ControlPanelState.MaterialChoice current = ControlPanelState.getSelectedMaterial();
        ControlPanelState.MaterialChoice[] choices = ControlPanelState.MaterialChoice.values();

        for (int i = 0; i < materialButtons.length; i++) {
            boolean selected = choices[i] == current;
            String prefix = selected ? "▶ " : "  ";
            materialButtons[i].setMessage(
                Component.literal(prefix + choices[i].getLabel()));
        }

        // 只在 CUSTOM 時顯示自訂方塊 ID 輸入框
        if (customBlockBox != null) {
            customBlockBox.setVisible(current == ControlPanelState.MaterialChoice.CUSTOM);
        }
    }

    // ════════════════════════════════════════
    // 渲染
    // ════════════════════════════════════════

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        // 全屏半透明遮罩
        renderBackground(gui);

        // 主面板背景
        gui.fill(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, BG_COLOR);

        // 面板邊框
        gui.fill(panelX, panelY, panelX + PANEL_W, panelY + 1, TITLE_COLOR);        // top
        gui.fill(panelX, panelY + PANEL_H - 1, panelX + PANEL_W, panelY + PANEL_H, TITLE_COLOR); // bottom
        gui.fill(panelX, panelY, panelX + 1, panelY + PANEL_H, TITLE_COLOR);        // left
        gui.fill(panelX + PANEL_W - 1, panelY, panelX + PANEL_W, panelY + PANEL_H, TITLE_COLOR); // right

        // ─── 標題 ───
        gui.drawCenteredString(this.font,
            Component.literal("§6✦ Fast Design 控制面板 ✦"),
            panelX + PANEL_W / 2, panelY + 8, TITLE_COLOR);

        // ─── 區塊背景 & 標題 ───
        int halfW = PANEL_W / 2 - 5;

        // Section 1: 建築操作
        drawSectionBox(gui, panelX + 5, panelY + 22, halfW, 92);
        gui.drawString(this.font, "§e【建築操作】", panelX + 10, panelY + 22, SECTION_TITLE);

        // Section 2: 材質選擇
        drawSectionBox(gui, panelX + PANEL_W / 2 + 5, panelY + 22, halfW, 110);
        gui.drawString(this.font, "§e【材質選擇】", panelX + PANEL_W / 2 + 10, panelY + 22, SECTION_TITLE);

        // Section 3: 編輯工具
        drawSectionBox(gui, panelX + 5, panelY + 125, halfW, 115);
        gui.drawString(this.font, "§e【編輯工具】", panelX + 10, panelY + 128, SECTION_TITLE);

        // Section 4: 進階功能
        drawSectionBox(gui, panelX + PANEL_W / 2 + 5, panelY + 140 - 15, halfW, 130);
        gui.drawString(this.font, "§e【進階功能】", panelX + PANEL_W / 2 + 10, panelY + 128, SECTION_TITLE);

        // ─── 選取狀態資訊 ───
        renderSelectionInfo(gui);

        // 渲染所有子元件（按鈕、輸入框）
        super.render(gui, mouseX, mouseY, partialTick);
    }

    /**
     * 在面板底部顯示目前選取狀態
     */
    private void renderSelectionInfo(GuiGraphics gui) {
        int infoY = panelY + PANEL_H - 24;

        ClientSelectionHolder.SelectionData sel = ClientSelectionHolder.get();
        if (sel != null) {
            String info = String.format("選取: %d×%d×%d = %d 方塊  |  材質: %s",
                sel.sizeX(), sel.sizeY(), sel.sizeZ(), sel.volume(),
                ControlPanelState.getSelectedMaterial().getLabel());
            gui.drawString(this.font, info, panelX + 10, infoY, INFO_COLOR);
        } else {
            // 優化: 當處於空狀態時，加入警告色的提示邊框與更醒目的訊息
            gui.drawString(this.font, "⚠ 尚未選取區域 — 請使用游標左鍵/右鍵設定 pos1/pos2",
                panelX + 10, infoY, 0xFFFFAA00);
        }
    }

    /**
     * 繪製區塊底色方框
     */
    private void drawSectionBox(GuiGraphics gui, int x, int y, int w, int h) {
        gui.fill(x, y, x + w, y + h, SECTION_BG);
    }

    // ════════════════════════════════════════
    // 互動
    // ════════════════════════════════════════

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // ESC 關閉面板
        if (keyCode == 256) {
            onClose();
            return true;
        }
        // 如果輸入框有焦點，讓它處理按鍵
        if (blueprintNameBox != null && blueprintNameBox.isFocused()) {
            return blueprintNameBox.keyPressed(keyCode, scanCode, modifiers);
        }
        if (customBlockBox != null && customBlockBox.isFocused()) {
            return customBlockBox.keyPressed(keyCode, scanCode, modifiers);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
