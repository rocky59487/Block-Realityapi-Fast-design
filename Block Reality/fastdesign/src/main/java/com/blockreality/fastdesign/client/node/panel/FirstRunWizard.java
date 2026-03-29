package com.blockreality.fastdesign.client.node.panel;

import com.blockreality.fastdesign.client.node.binding.MutableRenderConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 首次啟動引導 — 設計報告 §12.1 N5-5
 *
 * 在沒有 .brgraph 存檔時自動觸發。
 * 執行 GPU 偵測，推薦最佳品質，顯示簡要教學。
 */
@OnlyIn(Dist.CLIENT)
public class FirstRunWizard extends Screen {

    private static final int WIZARD_W = 280;
    private static final int WIZARD_H = 240;
    private static final int BG_COLOR = 0xEE1A1A2E;
    private static final int ACCENT_COLOR = 0xFF2196F3;

    private final GPUAutoDetector gpu = new GPUAutoDetector();
    private int step = 0; // 0=偵測, 1=推薦, 2=完成

    public FirstRunWizard() {
        super(Component.literal("Block Reality 首次設定"));
    }

    /**
     * 檢查是否需要顯示首次引導。
     */
    public static boolean shouldShow() {
        Path graphFile = Path.of("config", "blockreality", "nodegraph.brgraph");
        return !Files.exists(graphFile);
    }

    @Override
    protected void init() {
        super.init();
        gpu.detect();
        step = 1;

        int cx = width / 2;
        int by = (height + WIZARD_H) / 2 - 30;

        addRenderableWidget(Button.builder(
                Component.literal("套用推薦設定 ✓"),
                btn -> {
                    applyRecommended();
                    onClose();
                }
        ).bounds(cx - 80, by, 160, 20).build());

        addRenderableWidget(Button.builder(
                Component.literal("自訂設定..."),
                btn -> {
                    Minecraft.getInstance().setScreen(new SimplifiedSettingsScreen(null));
                }
        ).bounds(cx - 60, by + 24, 120, 20).build());
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        renderBackground(gui);

        int cx = width / 2;
        int x = cx - WIZARD_W / 2;
        int y = (height - WIZARD_H) / 2;

        gui.fill(x, y, x + WIZARD_W, y + WIZARD_H, BG_COLOR);

        // 標題
        gui.fill(x, y, x + WIZARD_W, y + 28, ACCENT_COLOR);
        gui.drawCenteredString(font, "Block Reality 首次設定", cx, y + 8, 0xFFFFFFFF);

        // GPU 資訊
        int ty = y + 36;
        gui.drawString(font, "偵測到的 GPU:", x + 12, ty, 0xFFAAAAAA);
        gui.drawString(font, abbreviate(gpu.gpuRenderer(), 36), x + 12, ty + 14, 0xFFDDDDDD);
        gui.drawString(font, "VRAM: ~" + gpu.estimatedVramMB() + " MB", x + 12, ty + 28, 0xFFDDDDDD);
        gui.drawString(font, "OpenGL: " + gpu.glMajor() + "." + gpu.glMinor(), x + 12, ty + 42, 0xFFDDDDDD);

        // 推薦
        ty += 64;
        gui.fill(x + 8, ty, x + WIZARD_W - 8, ty + 1, 0xFF3A3A5A);
        gui.drawString(font, "推薦品質:", x + 12, ty + 8, 0xFFAAAAAA);

        String tierName = gpu.recommended().name();
        int tierColor = switch (gpu.recommended()) {
            case POTATO -> 0xFF888888;
            case LOW -> 0xFF88BB44;
            case MEDIUM -> 0xFF44AAFF;
            case HIGH -> 0xFFFFAA00;
            case ULTRA -> 0xFFFF4444;
        };
        gui.drawString(font, "★ " + tierName, x + 80, ty + 8, tierColor);

        super.render(gui, mouseX, mouseY, partialTick);
    }

    private void applyRecommended() {
        MutableRenderConfig config = MutableRenderConfig.getInstance();
        switch (gpu.recommended()) {
            case POTATO -> { config.shadowMapResolution = 512; config.ssaoEnabled = false; config.taaEnabled = false; config.ssrEnabled = false; config.volumetricEnabled = false; config.lodMaxDistance = 128; }
            case LOW -> { config.shadowMapResolution = 1024; config.ssaoEnabled = true; config.taaEnabled = false; config.ssrEnabled = false; config.volumetricEnabled = false; config.lodMaxDistance = 256; }
            case MEDIUM -> { config.shadowMapResolution = 2048; config.ssaoEnabled = true; config.taaEnabled = true; config.ssrEnabled = false; config.volumetricEnabled = false; config.lodMaxDistance = 512; }
            case HIGH -> { config.shadowMapResolution = 2048; config.ssaoEnabled = true; config.taaEnabled = true; config.ssrEnabled = true; config.volumetricEnabled = true; config.lodMaxDistance = 768; }
            case ULTRA -> { config.shadowMapResolution = 4096; config.ssaoEnabled = true; config.taaEnabled = true; config.ssrEnabled = true; config.volumetricEnabled = true; config.lodMaxDistance = 1024; }
        }
        config.setOverrideActive(true);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "Unknown";
        return s.length() <= max ? s : s.substring(0, max - 2) + "..";
    }
}
