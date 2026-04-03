package com.blockreality.fastdesign.client;

import com.blockreality.api.client.render.BRRenderSettings;
import com.blockreality.api.client.render.pipeline.BRRenderTier;
import com.blockreality.api.client.render.rt.BRRTSettings;
import com.blockreality.fastdesign.client.node.canvas.NodeCanvasScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

/**
 * Block Reality 圖形設定介面 — UI-1（Iris 風格）。
 *
 * 三個頁籤：
 *   - General（基礎渲染）: 視距、解析度縮放
 *   - Lighting & Shadows（光影與細節）: 太陽角度、SSAO、Bloom、DOF
 *   - Vulkan Ray Tracing（極致光追）: RT 效果設定（RT 管線已移除，此頁籤為保留佔位）
 *
 * 入口：「選項 > 視訊設定」注入「Block Reality 設定」按鈕。
 * 所有控制項直接呼叫 BRRenderSettings / BRRTSettings，即時生效，無需重啟。
 *
 * @see com.blockreality.api.client.render.BRRenderSettings
 * @see com.blockreality.api.client.render.rt.BRRTSettings
 */
@OnlyIn(Dist.CLIENT)
public class BRGraphicsSettingsScreen extends Screen {

    // ─── 頁籤枚舉 ───────────────────────────────────────────────────────────
    private enum Tab {
        GENERAL("基礎渲染"),
        LIGHTING("光影與細節"),
        RT("極致光追");

        final String label;
        Tab(String label) { this.label = label; }
    }

    // ─── 版面常數 ───────────────────────────────────────────────────────────
    private static final int PANEL_W      = 420;
    private static final int PANEL_H      = 280;
    private static final int TAB_H        = 22;
    private static final int CTRL_W       = 180;
    private static final int CTRL_H       = 20;
    private static final int LABEL_GAP    = 4;
    private static final int ROW_STEP     = 28;
    private static final int COL_GAP      = 16;

    // 顏色（ARGB）
    private static final int COL_BG       = 0xCC101820;
    private static final int COL_PANEL    = 0xCC1C2A38;
    private static final int COL_TAB_ACT  = 0xFF2A5FAF;
    private static final int COL_TAB_INF  = 0xFF1A3050;
    private static final int COL_TITLE    = 0xFFE0F0FF;
    private static final int COL_LABEL    = 0xFFB0C8E0;
    private static final int COL_ACCENT   = 0xFF4090D0;
    private static final int COL_BORDER   = 0xFF304060;

    // ─── 狀態 ───────────────────────────────────────────────────────────────
    private Tab activeTab = Tab.GENERAL;
    private final Screen parent;

    // 動態控制項列表（每次切換頁籤時重建）
    private final List<Button>   tabButtons = new ArrayList<>();
    private       Button         btnNodeEditor;

    // ─── 建構子 ─────────────────────────────────────────────────────────────
    public BRGraphicsSettingsScreen(Screen parent) {
        super(Component.literal("Block Reality 圖形設定"));
        this.parent = parent;
    }

    // ─── 初始化 ─────────────────────────────────────────────────────────────
    @Override
    protected void init() {
        super.init();
        rebuildControls();
    }

    /** 每次切換頁籤時清除並重建所有控制項。 */
    private void rebuildControls() {
        clearWidgets();

        int panelX = (width  - PANEL_W) / 2;
        int panelY = (height - PANEL_H) / 2;

        // ── 頁籤按鈕 ──
        tabButtons.clear();
        Tab[] tabs = Tab.values();
        int tabW = PANEL_W / tabs.length;
        for (int i = 0; i < tabs.length; i++) {
            final Tab t = tabs[i];
            int tx = panelX + i * tabW;
            int ty = panelY;
            Button btn = Button.builder(Component.literal(t.label), b -> {
                activeTab = t;
                rebuildControls();
            }).pos(tx, ty).size(tabW - 2, TAB_H).build();
            tabButtons.add(btn);
            addRenderableWidget(btn);
        }

        // ── 控制項區域起點 ──
        int contentY = panelY + TAB_H + 10;
        int leftX    = panelX + 12;
        int rightX   = panelX + PANEL_W / 2 + 8;

        switch (activeTab) {
            case GENERAL   -> buildGeneralTab(leftX, rightX, contentY);
            case LIGHTING  -> buildLightingTab(leftX, rightX, contentY);
            case RT        -> buildRTTab(leftX, rightX, contentY);
        }

        // ── 底部按鈕 ──
        int btmY = panelY + PANEL_H - 26;

        // 進入節點編輯器（左下角）
        btnNodeEditor = Button.builder(
            Component.literal("⬡ 節點編輯器"),
            b -> minecraft.setScreen(new NodeCanvasScreen())
        ).pos(panelX + 8, btmY).size(130, 20).build();
        addRenderableWidget(btnNodeEditor);

        // 完成（右下角）
        addRenderableWidget(Button.builder(
            Component.literal("完成"),
            b -> onClose()
        ).pos(panelX + PANEL_W - 80, btmY).size(72, 20).build());
    }

    // ─── General 頁籤 ───────────────────────────────────────────────────────
    private void buildGeneralTab(int leftX, int rightX, int contentY) {
        // 視距滑桿（呼叫 MC 內建視距設定）
        int renderDist = minecraft.options.renderDistance().get();
        addRenderableWidget(new LabeledSlider(
            leftX, contentY, CTRL_W, CTRL_H,
            "視距", 2, 32, renderDist,
            v -> minecraft.options.renderDistance().set((int) v)
        ));

        // 解析度縮放
        float scale = BRRenderSettings.getRenderScale();
        addRenderableWidget(new LabeledSlider(
            leftX, contentY + ROW_STEP, CTRL_W, CTRL_H,
            "解析度縮放", 50, 200, (int)(scale * 100),
            v -> BRRenderSettings.setRenderScale((float) v / 100f)
        ));

        // SSAO 取樣數
        int ssaoSamples = BRRenderSettings.getSSAOSamples();
        addRenderableWidget(new LabeledSlider(
            rightX, contentY, CTRL_W, CTRL_H,
            "SSAO 取樣數", 8, 64, ssaoSamples,
            v -> BRRenderSettings.setSSAOSamples((int) v)
        ));

        // 陰影解析度
        int shadowRes = BRRenderSettings.getShadowResolution();
        addRenderableWidget(new LabeledSlider(
            rightX, contentY + ROW_STEP, CTRL_W, CTRL_H,
            "陰影解析度", 512, 4096, shadowRes,
            v -> BRRenderSettings.setShadowResolution(snapToPow2((int) v))
        ));
    }

    // ─── Lighting 頁籤 ──────────────────────────────────────────────────────
    private void buildLightingTab(int leftX, int rightX, int contentY) {
        // SSAO 開關
        addRenderableWidget(new ToggleButton(
            leftX, contentY, CTRL_W, CTRL_H,
            "環境光遮蔽 (SSAO)",
            BRRenderSettings.isSSAOEnabled(),
            v -> BRRenderSettings.setEffect("ssao", v)
        ));

        // Bloom 開關
        addRenderableWidget(new ToggleButton(
            leftX, contentY + ROW_STEP, CTRL_W, CTRL_H,
            "泛光 (Bloom)",
            BRRenderSettings.isBloomEnabled(),
            v -> BRRenderSettings.setEffect("bloom", v)
        ));

        // DOF 強度（以開關方式呈現）
        addRenderableWidget(new ToggleButton(
            leftX, contentY + ROW_STEP * 2, CTRL_W, CTRL_H,
            "景深 (DOF)",
            BRRenderSettings.isDOFEnabled(),
            v -> BRRenderSettings.setEffect("dof", v)
        ));

        // 體積光
        addRenderableWidget(new ToggleButton(
            rightX, contentY, CTRL_W, CTRL_H,
            "體積光 (Volumetric)",
            BRRenderSettings.isVolumetricEnabled(),
            v -> BRRenderSettings.setEffect("volumetric", v)
        ));

        // 接觸陰影
        addRenderableWidget(new ToggleButton(
            rightX, contentY + ROW_STEP, CTRL_W, CTRL_H,
            "接觸陰影",
            BRRenderSettings.isContactShadowEnabled(),
            v -> BRRenderSettings.setEffect("contact_shadow", v)
        ));

        // TAA 抗鋸齒
        addRenderableWidget(new ToggleButton(
            rightX, contentY + ROW_STEP * 2, CTRL_W, CTRL_H,
            "時序抗鋸齒 (TAA)",
            BRRenderSettings.isTAAEnabled(),
            v -> BRRenderSettings.setEffect("taa", v)
        ));
    }

    // ─── RT 頁籤 ────────────────────────────────────────────────────────────
    private void buildRTTab(int leftX, int rightX, int contentY) {
        BRRTSettings rt = BRRTSettings.getInstance();

        // TIER_3 總開關（藉由切換 rt_shadow / rt_reflection 等間接呈現整體開關狀態）
        boolean tier3Active = BRRenderSettings.isRTShadowsEnabled()
                           || BRRenderSettings.isRTReflectionsEnabled()
                           || BRRenderSettings.isRTGIEnabled();
        addRenderableWidget(new ToggleButton(
            leftX, contentY, CTRL_W, CTRL_H,
            "Vulkan RT 總開關",
            tier3Active,
            enabled -> {
                BRRenderSettings.setEffect("rt_shadow",     enabled);
                BRRenderSettings.setEffect("rt_reflection",  enabled);
                BRRenderSettings.setEffect("rt_ao",          enabled);
                rt.setEnableRTShadows(enabled);
                rt.setEnableRTReflections(enabled);
                rt.setEnableRTAO(enabled);
                rebuildControls(); // 重建以同步子開關顯示
            }
        ));

        // RT 陰影
        addRenderableWidget(new ToggleButton(
            leftX, contentY + ROW_STEP, CTRL_W, CTRL_H,
            "RT 陰影",
            rt.isEnableRTShadows(),
            rt::setEnableRTShadows
        ));

        // RT 反射
        addRenderableWidget(new ToggleButton(
            leftX, contentY + ROW_STEP * 2, CTRL_W, CTRL_H,
            "RT 反射",
            rt.isEnableRTReflections(),
            rt::setEnableRTReflections
        ));

        // RT AO
        addRenderableWidget(new ToggleButton(
            leftX, contentY + ROW_STEP * 3, CTRL_W, CTRL_H,
            "RT 環境光遮蔽",
            rt.isEnableRTAO(),
            rt::setEnableRTAO
        ));

        // RT GI
        addRenderableWidget(new ToggleButton(
            rightX, contentY, CTRL_W, CTRL_H,
            "RT 全域光照 (GI)",
            rt.isEnableRTGI(),
            rt::setEnableRTGI
        ));

        // 最大彈射次數
        addRenderableWidget(new LabeledSlider(
            rightX, contentY + ROW_STEP, CTRL_W, CTRL_H,
            "最大彈射次數", 1, 8, rt.getMaxBounces(),
            v -> rt.setMaxBounces((int) v)
        ));

        // 降噪器選擇
        String[] denoiserNames = {"無降噪", "SVGF", "NRD"};
        int currentAlgo = rt.getDenoiserAlgo();
        addRenderableWidget(new CycleButton(
            rightX, contentY + ROW_STEP * 2, CTRL_W, CTRL_H,
            "降噪器", denoiserNames, currentAlgo,
            v -> rt.setDenoiserAlgo(v)
        ));
    }

    // ─── 渲染 ────────────────────────────────────────────────────────────────
    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        // 模糊背景
        renderBackground(gfx);

        int panelX = (width  - PANEL_W) / 2;
        int panelY = (height - PANEL_H) / 2;

        // 外框
        gfx.fill(panelX - 2, panelY - 2, panelX + PANEL_W + 2, panelY + PANEL_H + 2, COL_BORDER);
        // 面板背景
        gfx.fill(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, COL_PANEL);

        // 頁籤高亮
        Tab[] tabs = Tab.values();
        int tabW = PANEL_W / tabs.length;
        for (int i = 0; i < tabs.length; i++) {
            int tx = panelX + i * tabW;
            int tabColor = (tabs[i] == activeTab) ? COL_TAB_ACT : COL_TAB_INF;
            gfx.fill(tx, panelY, tx + tabW - 2, panelY + TAB_H, tabColor);
        }

        // 標題
        gfx.drawString(font,
            "§bBlock Reality §f— 圖形設定",
            panelX + 8, panelY - 14, COL_TITLE, false);

        // 版本資訊（右上）
        String tierStr = "§7渲染: §f" + BRRenderTier.getCurrentTier().name;
        gfx.drawString(font, tierStr,
            panelX + PANEL_W - font.width(tierStr) - 8, panelY - 14,
            COL_LABEL, false);

        // 繪製子控件
        super.render(gfx, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    // ─── 工具方法 ─────────────────────────────────────────────────────────────
    private static int snapToPow2(int v) {
        int p = 512;
        while (p < v && p < 4096) p <<= 1;
        return p;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  內嵌控制項：滑桿、切換按鈕、循環按鈕
    // ═════════════════════════════════════════════════════════════════════════

    /** 帶標籤的數值滑桿。 */
    private class LabeledSlider extends AbstractSliderButton {
        private final String label;
        private final double min;
        private final double max;
        private final java.util.function.DoubleConsumer onChange;

        LabeledSlider(int x, int y, int w, int h,
                      String label, double min, double max, double current,
                      java.util.function.DoubleConsumer onChange) {
            super(x, y, w, h,
                Component.literal(label + ": " + (int) current),
                (current - min) / Math.max(1, max - min));
            this.label = label;
            this.min   = min;
            this.max   = max;
            this.onChange = onChange;
        }

        @Override
        protected void updateMessage() {
            int val = (int)(min + value * (max - min));
            setMessage(Component.literal(label + ": " + val));
        }

        @Override
        protected void applyValue() {
            double val = min + value * (max - min);
            onChange.accept(val);
        }
    }

    /** 開關按鈕（On/Off 切換）。 */
    private static class ToggleButton extends Button {
        private final String label;
        private boolean state;
        private final java.util.function.Consumer<Boolean> onChange;

        ToggleButton(int x, int y, int w, int h,
                     String label, boolean initial,
                     java.util.function.Consumer<Boolean> onChange) {
            super(x, y, w, h,
                Component.literal(label + ": " + (initial ? "§a開" : "§c關")),
                b -> {}, DEFAULT_NARRATION);
            this.label    = label;
            this.state    = initial;
            this.onChange = onChange;
            // 覆寫點擊行為
            this.setMessage(Component.literal(label + ": " + (state ? "§a開" : "§c關")));
        }

        @Override
        public void onPress() {
            state = !state;
            setMessage(Component.literal(label + ": " + (state ? "§a開" : "§c關")));
            onChange.accept(state);
        }
    }

    /** 循環切換按鈕（用於多選項如降噪器）。 */
    private static class CycleButton extends Button {
        private final String label;
        private final String[] options;
        private int index;
        private final java.util.function.IntConsumer onChange;

        CycleButton(int x, int y, int w, int h,
                    String label, String[] options, int initial,
                    java.util.function.IntConsumer onChange) {
            super(x, y, w, h,
                Component.literal(label + ": " + options[Math.max(0, Math.min(initial, options.length - 1))]),
                b -> {}, DEFAULT_NARRATION);
               this.label = label;
            this.options = options;
            this.index = Math.max(0, Math.min(initial, options.length - 1));
            this.onChange = onChange;
        }

        @Override
        public void onPress() {
            index = (index + 1) % options.length;
            setMessage(Component.literal(label + ": " + options[index]));
            onChange.accept(index);
        }
    }
}
