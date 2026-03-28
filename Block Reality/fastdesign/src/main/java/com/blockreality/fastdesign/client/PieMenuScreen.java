package com.blockreality.fastdesign.client;

import com.blockreality.fastdesign.network.FdActionPacket;
import com.blockreality.fastdesign.network.FdNetwork;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 輻射狀快捷輪盤 — Fast Design v2.0 Feature 3
 *
 * 按住 Alt 鍵呼出環狀 GUI，利用滑鼠甩動方向快速選擇操作。
 * 取代全螢幕的 ControlPanelScreen，大幅提升盲操效率。
 *
 * 設計參考：DOOM Eternal 武器輪盤 + 原神元素切換輪。
 *
 * 操作方式：
 * - 按住 Alt → 彈出輪盤
 * - 移動滑鼠到扇區 → 高亮
 * - 放開 Alt → 執行選中操作
 */
public class PieMenuScreen extends Screen {

    // ─── 選單項目定義 ───
    private static final PieMenuItem[] ITEMS = {
        new PieMenuItem("複製", "copy",     0xFF4CAF50, "✂"),  // 上
        new PieMenuItem("貼上", "paste",    0xFF2196F3, "📋"),  // 右上
        new PieMenuItem("填充", "fill",     0xFFFF9800, "⬛"),  // 右
        new PieMenuItem("替換", "replace",  0xFFE91E63, "🔄"),  // 右下
        new PieMenuItem("撤銷", "undo",     0xFF9C27B0, "↩"),  // 下
        new PieMenuItem("重做", "redo",     0xFF00BCD4, "↪"),  // 左下
        new PieMenuItem("旋轉", "rotate",   0xFFFF5722, "🔁"),  // 左
        new PieMenuItem("取消選取", "deselect", 0xFF607D8B, "✖"),  // 左上
    };

    private record PieMenuItem(String label, String action, int color, String icon) {}

    // 渲染參數
    private static final float INNER_RADIUS = 40f;
    private static final float OUTER_RADIUS = 110f;
    private static final float ICON_RADIUS = 80f;

    private int selectedIndex = -1;
    private int centerX, centerY;

    public PieMenuScreen() {
        super(Component.literal("Fast Design Pie Menu"));
    }

    @Override
    protected void init() {
        centerX = width / 2;
        centerY = height / 2;
    }

    @Override
    public boolean isPauseScreen() {
        return false; // 不暫停遊戲
    }

    // ─── 滑鼠追蹤 → 扇區選中 ───

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        double dx = mouseX - centerX;
        double dy = mouseY - centerY;
        double dist = Math.sqrt(dx * dx + dy * dy);

        if (dist < INNER_RADIUS) {
            selectedIndex = -1;
            return;
        }

        // 計算角度 (0° = 上方, 順時針)
        double angle = Math.toDegrees(Math.atan2(dx, -dy));
        if (angle < 0) angle += 360;

        double sectorSize = 360.0 / ITEMS.length;
        selectedIndex = (int)(angle / sectorSize) % ITEMS.length;
    }

    // ─── 渲染 ───

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 半透明黑色背景
        graphics.fill(0, 0, width, height, 0x80000000);

        PoseStack pose = graphics.pose();
        pose.pushPose();

        // 渲染每個扇區
        double sectorSize = 360.0 / ITEMS.length;
        for (int i = 0; i < ITEMS.length; i++) {
            PieMenuItem item = ITEMS[i];
            boolean isSelected = (i == selectedIndex);

            double startAngle = i * sectorSize - 90; // 從正上方開始
            double midAngle = Math.toRadians(startAngle + sectorSize / 2);

            // 扇區填充色
            int bgColor = isSelected
                ? (item.color | 0xCC000000)  // 選中：高不透明度
                : (item.color & 0x00FFFFFF) | 0x60000000; // 未選中：半透明

            float radius = isSelected ? OUTER_RADIUS + 8 : OUTER_RADIUS;
            renderPieSector(graphics, centerX, centerY, INNER_RADIUS, radius,
                    startAngle, startAngle + sectorSize, bgColor);

            // 圖示文字
            float iconX = (float)(centerX + Math.cos(midAngle) * ICON_RADIUS);
            float iconY = (float)(centerY + Math.sin(midAngle) * ICON_RADIUS);

            int textColor = isSelected ? 0xFFFFFFFF : 0xCCFFFFFF;
            graphics.drawCenteredString(font, item.icon + " " + item.label,
                    (int) iconX, (int) iconY - 4, textColor);
        }

        // 中心圓形裝飾
        renderCircle(graphics, centerX, centerY, INNER_RADIUS - 2, 0xDD1A1A2E);
        graphics.drawCenteredString(font, "§l⚡ FD", centerX, centerY - 4, 0xFFFFAA00);

        // 底部提示
        if (selectedIndex >= 0) {
            String hint = "放開以執行: " + ITEMS[selectedIndex].label;
            graphics.drawCenteredString(font, hint, centerX, height - 30, 0xAAFFFFFF);
        }

        pose.popPose();
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    // ─── 釋放時執行操作 ───

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        // We only trigger on release if it wasn't already triggered by mouseClicked,
        // but since mouseClicked closes the screen, this is just a fallback for drag-releases.
        if (selectedIndex >= 0 && selectedIndex < ITEMS.length) {
            executeAction(ITEMS[selectedIndex]);
        }
        onClose();
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Prevent double clicks passing through to the game world
        if (selectedIndex >= 0 && selectedIndex < ITEMS.length) {
            executeAction(ITEMS[selectedIndex]);
            onClose();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        // Alt 鍵釋放 = 確認選擇
        if (keyCode == 342 || keyCode == 346) { // GLFW ALT keys
            if (selectedIndex >= 0 && selectedIndex < ITEMS.length) {
                executeAction(ITEMS[selectedIndex]);
            }
            onClose();
            return true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    private void executeAction(PieMenuItem item) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // 取得手持方塊，若無則預設為石頭
        ItemStack held = mc.player.getMainHandItem();
        if (!(held.getItem() instanceof BlockItem)) {
            held = mc.player.getOffhandItem();
        }
        String blockId = "minecraft:stone";
        if (held.getItem() instanceof BlockItem bi) {
            blockId = ForgeRegistries.BLOCKS.getKey(bi.getBlock()).toString();
        }

        switch (item.action) {
            case "copy" -> FdNetwork.CHANNEL.sendToServer(new FdActionPacket(FdActionPacket.Action.COPY));
            case "paste" -> FdNetwork.CHANNEL.sendToServer(new FdActionPacket(FdActionPacket.Action.PASTE));
            case "fill" -> FdNetwork.CHANNEL.sendToServer(new FdActionPacket(FdActionPacket.Action.FILL, "material=custom,block=" + blockId));
            case "replace" -> FdNetwork.CHANNEL.sendToServer(new FdActionPacket(FdActionPacket.Action.REPLACE, "material=custom,block=" + blockId));
            case "undo" -> FdNetwork.CHANNEL.sendToServer(new FdActionPacket(FdActionPacket.Action.UNDO));
            case "redo" -> FdNetwork.CHANNEL.sendToServer(new FdActionPacket(FdActionPacket.Action.REDO));
            case "rotate" -> FdNetwork.CHANNEL.sendToServer(new FdActionPacket(FdActionPacket.Action.ROTATE, "90"));
            case "deselect" -> FdNetwork.CHANNEL.sendToServer(new FdActionPacket(FdActionPacket.Action.DESELECT));
        }

        String msg = "§6[FD] §f執行: §a" + item.label;
        mc.player.displayClientMessage(Component.literal(msg), true);
    }

    // ─── 扇區渲染工具 ───

    private void renderPieSector(GuiGraphics graphics, int cx, int cy,
                                  float innerR, float outerR,
                                  double startDeg, double endDeg, int color) {
        int segments = 24;
        double step = (endDeg - startDeg) / segments;

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.getBuilder();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();

        buf.begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);

        int a = (color >> 24) & 0xFF;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;

        for (int i = 0; i <= segments; i++) {
            double angle = Math.toRadians(startDeg + step * i);
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);

            buf.vertex(cx + cos * outerR, cy + sin * outerR, 0).color(r, g, b, a).endVertex();
            buf.vertex(cx + cos * innerR, cy + sin * innerR, 0).color(r, g, b, a).endVertex();
        }

        tess.end();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private void renderCircle(GuiGraphics graphics, int cx, int cy, float radius, int color) {
        renderPieSector(graphics, cx, cy, 0, radius, 0, 360, color);
    }
}
