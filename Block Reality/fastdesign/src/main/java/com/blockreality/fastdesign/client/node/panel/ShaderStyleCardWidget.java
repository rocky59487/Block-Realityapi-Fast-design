package com.blockreality.fastdesign.client.node.panel;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 光影風格選擇卡片，顯示縮圖、名稱、一行描述與效能評級標籤。
 *
 * <p>卡片尺寸固定為 108 × 90 像素（含 2px 邊框）。
 * 選中狀態顯示亮藍色邊框；Hover 狀態顯示亮白色邊框並上移 2px 製造浮起感。
 *
 * <p>若預設沒有縮圖路徑，則以 40% 透明的色塊作為占位符，
 * 色塊顏色由效能評級決定。
 */
@OnlyIn(Dist.CLIENT)
public final class ShaderStyleCardWidget extends AbstractWidget {

    // -------------------------------------------------------------------------
    // 常數
    // -------------------------------------------------------------------------

    /** 卡片外寬（含邊框） */
    public static final int CARD_W = 108;
    /** 卡片外高（含邊框） */
    public static final int CARD_H = 90;

    /** 縮圖區高度 */
    private static final int THUMB_H = 60;
    /** 底部資訊區高度 */
    private static final int INFO_H  = CARD_H - THUMB_H;  // 30

    // 顏色常數
    private static final int COL_BG          = 0xCC101820; // 半透明深藍底
    private static final int COL_BORDER_IDLE = 0xFF2A3A4A; // 未選中邊框
    private static final int COL_BORDER_HOVER= 0xFFCCDDEE; // Hover 邊框
    private static final int COL_BORDER_SEL  = 0xFF4499FF; // 選中邊框（亮藍）
    private static final int COL_TEXT_NAME   = 0xFFEEEEEE;
    private static final int COL_TEXT_DESC   = 0xFF8899AA;

    // -------------------------------------------------------------------------
    // 欄位
    // -------------------------------------------------------------------------

    private final StylePreset preset;
    private boolean selected;
    private Runnable onSelect;

    // 選取時的動畫（0.0 → 1.0）
    private float selectAnim = 0f;
    private float hoverAnim  = 0f;

    // -------------------------------------------------------------------------
    // 建構子
    // -------------------------------------------------------------------------

    public ShaderStyleCardWidget(int x, int y, StylePreset preset, boolean selected, Runnable onSelect) {
        super(x, y, CARD_W, CARD_H, Component.literal(preset.getDisplayName()));
        this.preset   = preset;
        this.selected = selected;
        this.onSelect = onSelect;
        if (selected) { selectAnim = 1f; }
    }

    // -------------------------------------------------------------------------
    // 渲染
    // -------------------------------------------------------------------------

    @Override
    public void renderWidget(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        // 動畫推進
        boolean hovered = isHovered();
        hoverAnim  = lerp(hoverAnim,  hovered  ? 1f : 0f, partialTick * 0.3f);
        selectAnim = lerp(selectAnim, selected ? 1f : 0f, partialTick * 0.3f);

        // Hover 浮起偏移（最多 2px）
        int dy = (int) (hoverAnim * -2);

        int x = getX();
        int y = getY() + dy;
        int w = CARD_W;
        int h = CARD_H;

        // 邊框顏色插值
        int borderColor = lerpColor(
            lerpColor(COL_BORDER_IDLE, COL_BORDER_HOVER, hoverAnim),
            COL_BORDER_SEL,
            selectAnim
        );

        // 邊框
        gfx.fill(x - 1, y - 1, x + w + 1, y + h + 1, borderColor);
        // 背景
        gfx.fill(x, y, x + w, y + h, COL_BG);

        // 縮圖 or 色塊占位
        renderThumbnail(gfx, x, y, w);

        // 效能評級標籤（右上角）
        renderTierBadge(gfx, x, y);

        // 底部資訊區
        renderInfoBar(gfx, x, y + THUMB_H, w);

        // 選中時：底部亮線裝飾
        if (selectAnim > 0.01f) {
            int alpha = (int) (selectAnim * 0xFF) << 24;
            gfx.fill(x, y + h - 2, x + w, y + h, (alpha | 0x4499FF));
        }
    }

    // -------------------------------------------------------------------------
    // 縮圖
    // -------------------------------------------------------------------------

    private void renderThumbnail(GuiGraphics gfx, int x, int y, int w) {
        String path = preset.getThumbnailPath();
        if (path != null && !path.isBlank()) {
            try {
                ResourceLocation rl = new ResourceLocation(path);
                RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
                gfx.blit(rl, x, y, 0, 0, w, THUMB_H, w, THUMB_H);
                return;
            } catch (Exception ignored) { /* 回退到色塊 */ }
        }
        // 色塊占位 — 以評級色填充
        int tileColor = 0x66000000 | (preset.getTier().labelColor & 0x00FFFFFF);
        gfx.fill(x, y, x + w, y + THUMB_H, tileColor);

        // 中央顯示預設首字
        String initial = preset.getDisplayName().substring(0, 1);
        int textX = x + w / 2 - Minecraft.getInstance().font.width(initial) / 2;
        int textY = y + THUMB_H / 2 - 4;
        gfx.drawString(Minecraft.getInstance().font, initial, textX, textY, 0xAAFFFFFF, false);
    }

    // -------------------------------------------------------------------------
    // 評級標籤
    // -------------------------------------------------------------------------

    private void renderTierBadge(GuiGraphics gfx, int x, int y) {
        StylePreset.PerformanceTier tier = preset.getTier();
        String label = tier.label;
        int labelW = Minecraft.getInstance().font.width(label) + 6;
        int bx = x + CARD_W - labelW - 2;
        int by = y + 2;
        // 半透明背景
        gfx.fill(bx, by, bx + labelW, by + 10, 0xBB000000);
        gfx.drawString(Minecraft.getInstance().font, label, bx + 3, by + 1, tier.labelColor, false);
    }

    // -------------------------------------------------------------------------
    // 底部資訊條
    // -------------------------------------------------------------------------

    private void renderInfoBar(GuiGraphics gfx, int x, int y, int w) {
        // 背景（稍深）
        gfx.fill(x, y, x + w, y + INFO_H, 0xCC0A1018);

        var font = Minecraft.getInstance().font;

        // 名稱（粗體截斷）
        String name = preset.getDisplayName();
        int nameW = font.width(name);
        if (nameW > w - 6) {
            // 截斷到最大寬度
            name = font.plainSubstrByWidth(name, w - 12) + "…";
        }
        gfx.drawString(font, name, x + 4, y + 4, COL_TEXT_NAME, false);

        // 描述（小字，截斷）
        String desc = preset.getDescription();
        int descW = font.width(desc);
        if (descW > w - 6) {
            desc = font.plainSubstrByWidth(desc, w - 12) + "…";
        }
        gfx.drawString(font, desc, x + 4, y + 15, COL_TEXT_DESC, false);
    }

    // -------------------------------------------------------------------------
    // 互動
    // -------------------------------------------------------------------------

    @Override
    public void onClick(double mouseX, double mouseY) {
        if (onSelect != null) onSelect.run();
    }

    // -------------------------------------------------------------------------
    // 狀態更新
    // -------------------------------------------------------------------------

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public boolean isSelected() {
        return selected;
    }

    public StylePreset getPreset() {
        return preset;
    }

    public void setOnSelect(Runnable r) {
        this.onSelect = r;
    }

    // -------------------------------------------------------------------------
    // Narration（無障礙）
    // -------------------------------------------------------------------------

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        defaultButtonNarrationText(out);
    }

    // -------------------------------------------------------------------------
    // 工具方法
    // -------------------------------------------------------------------------

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * Math.min(t, 1f);
    }

    /**
     * 線性插值兩個 ARGB 顏色。t 範圍 [0,1]。
     */
    private static int lerpColor(int c0, int c1, float t) {
        if (t <= 0f) return c0;
        if (t >= 1f) return c1;
        int a0 = (c0 >> 24) & 0xFF, r0 = (c0 >> 16) & 0xFF, g0 = (c0 >> 8) & 0xFF, b0 = c0 & 0xFF;
        int a1 = (c1 >> 24) & 0xFF, r1 = (c1 >> 16) & 0xFF, g1 = (c1 >> 8) & 0xFF, b1 = c1 & 0xFF;
        int a = (int) (a0 + (a1 - a0) * t);
        int r = (int) (r0 + (r1 - r0) * t);
        int g = (int) (g0 + (g1 - g0) * t);
        int b = (int) (b0 + (b1 - b0) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
