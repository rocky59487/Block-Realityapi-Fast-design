package com.blockreality.fastdesign.client;

import com.blockreality.api.chisel.SubBlockShape;
import com.blockreality.api.item.ChiselItem;
import com.blockreality.api.network.BRNetwork;
import com.blockreality.api.network.ChiselControlPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 雕刻刀工具選單 — 長按 Alt 彈出。
 *
 * 顯示所有可用形狀的網格按鈕，點擊選擇後關閉。
 * 同時顯示當前選區大小和操作提示。
 *
 * 設計與 ControlPanelScreen 風格統一。
 */
@OnlyIn(Dist.CLIENT)
public class ChiselToolScreen extends Screen {

    private static final int PANEL_W = 320;
    private static final int PANEL_H = 240;
    private static final int BTN_W = 72;
    private static final int BTN_H = 20;
    private static final int BTN_GAP = 3;

    private static final int BG_COLOR      = 0xCC1A1A2E;
    private static final int SECTION_BG    = 0x88000000;
    private static final int TITLE_COLOR   = 0xFF00CCFF;
    private static final int SECTION_TITLE = 0xFFFFCC00;
    private static final int INFO_COLOR    = 0xFFAABBCC;
    private static final int HINT_COLOR    = 0xFF888888;

    /** 形狀按鈕排列（4 列） */
    private static final SubBlockShape[] SHAPES = {
        SubBlockShape.FULL,
        SubBlockShape.SLAB_BOTTOM,
        SubBlockShape.SLAB_TOP,
        SubBlockShape.PILLAR,
        SubBlockShape.STAIR_NORTH,
        SubBlockShape.STAIR_SOUTH,
        SubBlockShape.STAIR_EAST,
        SubBlockShape.STAIR_WEST,
        SubBlockShape.QUARTER_NE,
        SubBlockShape.QUARTER_NW,
        SubBlockShape.QUARTER_SE,
        SubBlockShape.QUARTER_SW,
        SubBlockShape.ARCH_BOTTOM,
        SubBlockShape.ARCH_TOP,
        SubBlockShape.BEAM_NS,
        SubBlockShape.BEAM_EW,
        SubBlockShape.CUSTOM,
    };

    private static final int COLS = 4;

    private int panelX, panelY;

    public ChiselToolScreen() {
        super(Component.literal("雕刻刀工具選單"));
    }

    @Override
    protected void init() {
        panelX = (this.width - PANEL_W) / 2;
        panelY = (this.height - PANEL_H) / 2;

        // ─── 形狀按鈕網格 ───
        int gridX = panelX + 10;
        int gridY = panelY + 35;

        for (int i = 0; i < SHAPES.length; i++) {
            final SubBlockShape shape = SHAPES[i];
            int col = i % COLS;
            int row = i / COLS;
            int bx = gridX + col * (BTN_W + BTN_GAP);
            int by = gridY + row * (BTN_H + BTN_GAP);

            String label = getShapeLabel(shape);
            addRenderableWidget(
                Button.builder(Component.literal(label), btn -> {
                    selectShape(shape);
                })
                .bounds(bx, by, BTN_W, BTN_H)
                .build()
            );
        }

        // ─── 關閉按鈕 ───
        addRenderableWidget(
            Button.builder(Component.literal("✕"), btn -> onClose())
                .bounds(panelX + PANEL_W - 22, panelY + 4, 18, 16)
                .build()
        );
    }

    private void selectShape(SubBlockShape shape) {
        // 更新客戶端自己的 ItemStack 狀態，確保預測渲染與 tooltip 正確
        if (this.minecraft != null && this.minecraft.player != null) {
            net.minecraft.world.item.ItemStack main = this.minecraft.player.getMainHandItem();
            net.minecraft.world.item.ItemStack off = this.minecraft.player.getOffhandItem();
            if (main.getItem() instanceof ChiselItem) {
                ChiselItem.setShapeByName(main, shape);
            } else if (off.getItem() instanceof ChiselItem) {
                ChiselItem.setShapeByName(off, shape);
            }
        }

        // 透過 ChiselControlPacket 發送形狀選擇同步給伺服器
        BRNetwork.CHANNEL.sendToServer(
            new ChiselControlPacket(ChiselControlPacket.Action.SELECT_SHAPE,
                shape.getSerializedName()));
        onClose();
    }

    private String getShapeLabel(SubBlockShape shape) {
        return switch (shape) {
            case FULL -> "完整";
            case SLAB_BOTTOM -> "半磚↓";
            case SLAB_TOP -> "半磚↑";
            case PILLAR -> "圓柱";
            case STAIR_NORTH -> "階梯 N";
            case STAIR_SOUTH -> "階梯 S";
            case STAIR_EAST -> "階梯 E";
            case STAIR_WEST -> "階梯 W";
            case QUARTER_NE -> "¼ NE";
            case QUARTER_NW -> "¼ NW";
            case QUARTER_SE -> "¼ SE";
            case QUARTER_SW -> "¼ SW";
            case ARCH_BOTTOM -> "拱底";
            case ARCH_TOP -> "拱頂";
            case BEAM_NS -> "樑 NS";
            case BEAM_EW -> "樑 EW";
            case CUSTOM -> "§b自訂";
        };
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        renderBackground(gui);

        // 面板背景
        gui.fill(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, BG_COLOR);

        // 邊框
        gui.fill(panelX, panelY, panelX + PANEL_W, panelY + 1, TITLE_COLOR);
        gui.fill(panelX, panelY + PANEL_H - 1, panelX + PANEL_W, panelY + PANEL_H, TITLE_COLOR);
        gui.fill(panelX, panelY, panelX + 1, panelY + PANEL_H, TITLE_COLOR);
        gui.fill(panelX + PANEL_W - 1, panelY, panelX + PANEL_W, panelY + PANEL_H, TITLE_COLOR);

        // 標題
        gui.drawCenteredString(this.font,
            Component.literal("§b✦ 雕刻刀形狀選單 ✦"),
            panelX + PANEL_W / 2, panelY + 8, TITLE_COLOR);

        // 形狀區塊底色
        int rows = (SHAPES.length + COLS - 1) / COLS;
        int gridH = rows * (BTN_H + BTN_GAP) + 5;
        drawSectionBox(gui, panelX + 5, panelY + 26, PANEL_W - 10, gridH);

        // ─── 底部操作提示 ───
        int hintY = panelY + PANEL_H - 38;
        gui.drawString(this.font, "§e操作提示:", panelX + 10, hintY, SECTION_TITLE);
        hintY += 12;
        gui.drawString(this.font,
            "↑↓ 調高度  ←→ 調寬度  X+右鍵 橡皮擦  H 調邊長",
            panelX + 10, hintY, HINT_COLOR);

        super.render(gui, mouseX, mouseY, partialTick);
    }

    private void drawSectionBox(GuiGraphics gui, int x, int y, int w, int h) {
        gui.fill(x, y, x + w, y + h, SECTION_BG);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
