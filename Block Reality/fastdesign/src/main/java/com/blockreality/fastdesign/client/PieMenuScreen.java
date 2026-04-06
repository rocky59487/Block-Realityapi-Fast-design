package com.blockreality.fastdesign.client;

import com.blockreality.fastdesign.network.FdActionPacket;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
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
@OnlyIn(Dist.CLIENT)
public class PieMenuScreen extends Screen {

    // ─── 選單項目定義 ───
    private static final PieMenuItem[] ITEMS = {
        new PieMenuItem("複製區塊", "copy",     0xFF4CAF50, "✂"),  // 上
        new PieMenuItem("開啟節點", "nodes",    0xFF00BCD4, "⚙"),  // 右上
        new PieMenuItem("貼上選取", "paste",    0xFF2196F3, "📋"),  // 右
        new PieMenuItem("全域重製", "redo",     0xFFE91E63, "↪"),  // 右下
        new PieMenuItem("撤銷操作", "undo",     0xFF9C27B0, "↩"),  // 下
        new PieMenuItem("清除選擇", "deselect", 0xFF607D8B, "✖"),  // 左下
        new PieMenuItem("填充材質", "fill",     0xFFFF9800, "⬛"),  // 左
        new PieMenuItem("開啟面板", "settings", 0xFFFF5722, "🛠"),  // 左上
    };

    private record PieMenuItem(String label, String action, int color, String icon) {}

    // 渲染參數
    private static final float INNER_RADIUS = 30f;
    private static final float OUTER_RADIUS = 110f;
    private static final float ICON_RADIUS = 75f;

    private int selectedIndex = -1;
    private int centerX, centerY;

    // 動畫參數
    private float animProgress = 0.0f;
    private long openTime;
    private final float[] sliceScales = new float[ITEMS.length];

    public PieMenuScreen() {
        super(Component.literal("Fast Design Pie Menu"));
        this.openTime = System.currentTimeMillis();
        for (int i = 0; i < ITEMS.length; i++) sliceScales[i] = 1.0f;
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

    private void tickLerp() {
        long now = System.currentTimeMillis();
        // 開啟展開動畫 (0 -> 1，持續約 150ms)
        float targetAnim = Math.min(1.0f, (now - openTime) / 150.0f);
        // 使用 Ease-Out 曲線讓彈出更順滑
        animProgress = 1.0f - (float) Math.pow(1.0f - targetAnim, 3);

        // 扇區 Hover 縮放動畫
        for (int i = 0; i < ITEMS.length; i++) {
            float targetScale = (i == selectedIndex) ? 1.08f : 1.0f;
            sliceScales[i] += (targetScale - sliceScales[i]) * 0.3f;
        }
    }

    // ─── 渲染 ───

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        tickLerp();

        // ★ UX: 創造背景暗化，但使用更中性的深色
        int bgAlpha = (int)(0x90 * animProgress);
        graphics.fill(0, 0, width, height, (bgAlpha << 24));

        PoseStack pose = graphics.pose();
        pose.pushPose();

        double sectorSize = 360.0 / ITEMS.length;

        // 渲染每個扇區
        for (int i = 0; i < ITEMS.length; i++) {
            PieMenuItem item = ITEMS[i];
            boolean isSelected = (i == selectedIndex);

            double startAngle = i * sectorSize - 90; // 從正上方開始
            double midAngle = Math.toRadians(startAngle + sectorSize / 2);

            // 動畫縮放影響外半徑
            float scale = sliceScales[i] * animProgress;
            float currentOuterRadius = OUTER_RADIUS * scale;
            float currentInnerRadius = INNER_RADIUS * animProgress;

            // ★ UI/UX: 統一色調，Create/Grasshopper 風格
            // 未選中：深灰色 / 選中：帶有原圖示顏色的亮黃色或強調色
            int baseColor = 0xFF2B2B2B; // Grasshopper 深灰
            int highlightColor = 0xFFFFF1A5; // 草蜢亮黃

            // 扇區填充色
            int bgColor = isSelected
                ? highlightColor
                : (baseColor & 0x00FFFFFF) | 0xDD000000;

            // 選中項可以帶點原色彩提示
            if (isSelected) {
                int mixColor = brighten(item.color, 1.2f);
                bgColor = mixColor | 0xFF000000;
            }

            renderPieSector(graphics, centerX, centerY, currentInnerRadius, currentOuterRadius,
                    startAngle, startAngle + sectorSize - 1.5, bgColor); // -1.5 創造扇區間的間隙

            // 圖示文字 (套用動畫位移)
            float iconR = ICON_RADIUS * scale;
            float iconX = (float)(centerX + Math.cos(midAngle) * iconR);
            float iconY = (float)(centerY + Math.sin(midAngle) * iconR);

            int textColor = isSelected ? 0xFFFFFFFF : 0xFFB0B0B0;
            if (animProgress > 0.5f) { // 避免動畫初期文字重疊
                graphics.drawCenteredString(font, item.icon + " " + item.label,
                        (int) iconX, (int) iconY - 4, textColor);
            }
        }

        // 中心圓形裝飾 (空心+點綴)
        float currentInnerRadius = INNER_RADIUS * animProgress;
        renderCircle(graphics, centerX, centerY, currentInnerRadius - 4, 0xEE18181A);
        if (animProgress > 0.8f) {
            graphics.drawCenteredString(font, "§l⚙", centerX, centerY - 4, 0xFFFFF1A5);
        }

        // 底部提示
        if (selectedIndex >= 0 && animProgress > 0.8f) {
            String hint = "放開以執行: " + ITEMS[selectedIndex].label;
            graphics.drawCenteredString(font, hint, centerX, height - 30, 0xAAFFFFFF);
        }

        pose.popPose();
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private static int brighten(int color, float factor) {
        int r = Math.min(255, (int) (((color >> 16) & 0xFF) * factor));
        int g = Math.min(255, (int) (((color >> 8) & 0xFF) * factor));
        int b = Math.min(255, (int) ((color & 0xFF) * factor));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
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
            case "undo" -> FdNetwork.CHANNEL.sendToServer(new FdActionPacket(FdActionPacket.Action.UNDO));
            case "redo" -> FdNetwork.CHANNEL.sendToServer(new FdActionPacket(FdActionPacket.Action.REDO));
            case "deselect" -> FdNetwork.CHANNEL.sendToServer(new FdActionPacket(FdActionPacket.Action.DESELECT));
            case "nodes" -> {
                // 打開節點系統介面
                net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT, () -> () -> {
                    com.blockreality.fastdesign.client.node.NodeGraph graph = new com.blockreality.fastdesign.client.node.NodeGraph();
                    // 可以加上讀取現有 node graph 配置的邏輯
                    mc.setScreen(new com.blockreality.fastdesign.client.node.canvas.NodeCanvasScreen(graph));
                });
            }
            case "settings" -> {
                mc.setScreen(new FastDesignScreen(null));
            }
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
