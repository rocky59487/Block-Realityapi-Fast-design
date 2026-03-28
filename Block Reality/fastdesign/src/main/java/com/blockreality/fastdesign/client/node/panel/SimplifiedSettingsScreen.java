package com.blockreality.fastdesign.client.node.panel;

import com.blockreality.fastdesign.client.node.binding.MutableRenderConfig;
import com.blockreality.fastdesign.client.node.canvas.NodeCanvasScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 簡化設定面板 — 設計報告 §11.2, §12.1 N5-1
 *
 * 取代 Options > Video Settings，提供初學者友好的設定介面。
 * 底部有「進階設定（節點編輯器）」按鈕進入完整畫布。
 */
@OnlyIn(Dist.CLIENT)
public class SimplifiedSettingsScreen extends Screen {

    private static final int PANEL_W = 320;
    private static final int PANEL_H = 400;
    private static final int BG_COLOR = 0xEE1A1A2E;
    private static final int SECTION_COLOR = 0xFF2A2A4A;
    private static final int TEXT_COLOR = 0xFFDDDDDD;
    private static final int LABEL_COLOR = 0xFF888888;

    private final Screen parent;
    private final MutableRenderConfig config = MutableRenderConfig.getInstance();
    private final GPUAutoDetector gpu = new GPUAutoDetector();

    private String selectedPreset = "HIGH";

    public SimplifiedSettingsScreen(Screen parent) {
        super(Component.literal("Block Reality 視訊設定"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        gpu.detect();

        int cx = width / 2;
        int baseY = (height - PANEL_H) / 2;

        // 品質預設
        addRenderableWidget(Button.builder(Component.literal("▼ " + selectedPreset), btn -> {
            cyclePreset();
            btn.setMessage(Component.literal("▼ " + selectedPreset));
        }).bounds(cx - 60, baseY + 30, 120, 20).build());

        // 進階設定按鈕
        addRenderableWidget(Button.builder(
                Component.literal("進階設定（節點編輯器）→"),
                btn -> Minecraft.getInstance().setScreen(new NodeCanvasScreen())
        ).bounds(cx - 100, baseY + PANEL_H - 60, 200, 20).build());

        // 材質調配按鈕
        addRenderableWidget(Button.builder(
                Component.literal("材質調配工作台 →"),
                btn -> Minecraft.getInstance().setScreen(new NodeCanvasScreen())
        ).bounds(cx - 100, baseY + PANEL_H - 36, 200, 20).build());

        // 返回
        addRenderableWidget(Button.builder(
                Component.literal("完成"),
                btn -> onClose()
        ).bounds(cx - 50, baseY + PANEL_H - 4, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        renderBackground(gui);

        int cx = width / 2;
        int x = cx - PANEL_W / 2;
        int y = (height - PANEL_H) / 2;

        // 面板背景
        gui.fill(x, y, x + PANEL_W, y + PANEL_H, BG_COLOR);

        // 標題
        gui.drawCenteredString(font, "⚙ Block Reality 視訊設定", cx, y + 8, TEXT_COLOR);

        // 品質預設標籤
        gui.drawString(font, "品質預設:", x + 10, y + 35, LABEL_COLOR);

        // ─── 基本設定 ───
        int secY = y + 60;
        gui.fill(x + 4, secY, x + PANEL_W - 4, secY + 1, SECTION_COLOR);
        gui.drawString(font, "━━ 基本 ━━━━━━━━━━━━━", x + 8, secY + 4, LABEL_COLOR);
        gui.drawString(font, "視距: " + (int) config.lodMaxDistance, x + 12, secY + 18, TEXT_COLOR);
        gui.drawString(font, "解析度縮放: 1.0x", x + 12, secY + 32, TEXT_COLOR);

        // ─── 光影 ───
        secY += 52;
        gui.fill(x + 4, secY, x + PANEL_W - 4, secY + 1, SECTION_COLOR);
        gui.drawString(font, "━━ 光影 ━━━━━━━━━━━━━", x + 8, secY + 4, LABEL_COLOR);
        gui.drawString(font, "陰影品質: " + config.shadowMapResolution, x + 12, secY + 18, TEXT_COLOR);
        gui.drawString(font, "環境遮蔽: " + (config.gtaoEnabled ? "✓ GTAO" : "✗"), x + 12, secY + 32, TEXT_COLOR);
        gui.drawString(font, "反射: " + (config.ssrEnabled ? "✓ SSR" : "✗"), x + 12, secY + 46, TEXT_COLOR);
        gui.drawString(font, "抗鋸齒: " + (config.taaEnabled ? "✓ TAA" : "✗"), x + 12, secY + 60, TEXT_COLOR);

        // ─── 效果 ───
        secY += 80;
        gui.fill(x + 4, secY, x + PANEL_W - 4, secY + 1, SECTION_COLOR);
        gui.drawString(font, "━━ 效果 ━━━━━━━━━━━━━", x + 8, secY + 4, LABEL_COLOR);
        gui.drawString(font, "泛光: " + (config.bloomIntensity > 0 ? "✓" : "✗"), x + 12, secY + 18, TEXT_COLOR);
        gui.drawString(font, "體積光: " + (config.volumetricEnabled ? "✓" : "✗"), x + 12, secY + 32, TEXT_COLOR);
        gui.drawString(font, "SSGI: " + (config.ssgiEnabled ? "✓" : "✗"), x + 12, secY + 46, TEXT_COLOR);

        // ─── GPU 資訊 ───
        int bottomY = y + PANEL_H - 76;
        gui.fill(x + 4, bottomY, x + PANEL_W - 4, bottomY + 1, SECTION_COLOR);
        String gpuInfo = "GPU: " + abbreviate(gpu.gpuRenderer(), 30)
                + " | VRAM: " + gpu.estimatedVramMB() + "MB";
        gui.drawString(font, gpuInfo, x + 8, bottomY + 4, LABEL_COLOR);

        super.render(gui, mouseX, mouseY, partialTick);
    }

    private void cyclePreset() {
        String[] presets = {"POTATO", "LOW", "MEDIUM", "HIGH", "ULTRA"};
        for (int i = 0; i < presets.length; i++) {
            if (presets[i].equals(selectedPreset)) {
                selectedPreset = presets[(i + 1) % presets.length];
                applyPreset(selectedPreset);
                return;
            }
        }
        selectedPreset = "HIGH";
    }

    private void applyPreset(String preset) {
        switch (preset) {
            case "POTATO" -> { config.shadowMapResolution = 512; config.ssaoEnabled = false; config.ssrEnabled = false; config.taaEnabled = false; config.volumetricEnabled = false; config.lodMaxDistance = 128; }
            case "LOW" -> { config.shadowMapResolution = 1024; config.ssaoEnabled = true; config.ssrEnabled = false; config.taaEnabled = false; config.volumetricEnabled = false; config.lodMaxDistance = 256; }
            case "MEDIUM" -> { config.shadowMapResolution = 2048; config.ssaoEnabled = true; config.ssrEnabled = false; config.taaEnabled = true; config.volumetricEnabled = false; config.lodMaxDistance = 512; }
            case "HIGH" -> { config.shadowMapResolution = 2048; config.ssaoEnabled = true; config.ssrEnabled = true; config.taaEnabled = true; config.volumetricEnabled = true; config.lodMaxDistance = 768; }
            case "ULTRA" -> { config.shadowMapResolution = 4096; config.ssaoEnabled = true; config.ssrEnabled = true; config.taaEnabled = true; config.volumetricEnabled = true; config.lodMaxDistance = 1024; }
        }
        config.setOverrideActive(true);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return true; }

    private static String abbreviate(String s, int max) {
        if (s == null) return "Unknown";
        return s.length() <= max ? s : s.substring(0, max - 2) + "..";
    }
}
