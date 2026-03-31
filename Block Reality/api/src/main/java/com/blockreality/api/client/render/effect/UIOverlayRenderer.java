package com.blockreality.api.client.render.effect;

import com.blockreality.api.client.render.animation.BRAnimationEngine;
import com.blockreality.api.client.render.optimization.BRLODEngine;
import com.blockreality.api.client.render.optimization.BROptimizationEngine;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

/**
 * Block Reality 渲染管線除錯 HUD 覆蓋層。
 * 在 F3 下方顯示管線狀態資訊。
 */
@OnlyIn(Dist.CLIENT)
public final class UIOverlayRenderer {

    private boolean debugHudEnabled = false;

    public UIOverlayRenderer() {}

    public void init() { debugHudEnabled = false; }

    public boolean isDebugHudEnabled() { return debugHudEnabled; }
    public void setDebugHudEnabled(boolean v) { debugHudEnabled = v; }

    /**
     * 繪製除錯 HUD。在 RenderGameOverlayEvent 中呼叫。
     *
     * @param gui  GuiGraphics 上下文
     * @param font 字型
     * @param x    起始 X 座標
     */
    public void renderDebugHUD(GuiGraphics gui, Font font, int x) {
        if (!debugHudEnabled) return;

        int y = 40; // F3 下方留空
        int lineHeight = 10;
        int bgColor = 0x80000000; // 半透明黑底

        String[] lines = {
            "\u00a76[Block Reality Render Pipeline]",
            String.format("\u00a77Pipeline: \u00a7a%s",
                BRLODEngine.isInitialized() ? "ON" : "OFF"),
            String.format("\u00a77Frustum: \u00a7f%d visible \u00a78/ \u00a7c%d culled",
                BROptimizationEngine.getLastCulledCount() > 0
                    ? BROptimizationEngine.getLastCulledCount() : 0,
                BROptimizationEngine.getLastCulledCount()),
            String.format("\u00a77Draw Calls: \u00a7f%d  \u00a77Cached Sections: \u00a7f%d",
                BROptimizationEngine.getLastDrawCallCount(),
                BROptimizationEngine.getCachedSectionCount()),
            String.format("\u00a77Animations: \u00a7f%d active",
                BRAnimationEngine.getActiveControllerCount()),
            String.format("\u00a77Effects: \u00a7f%d particles \u00a7f%d fragments",
                BREffectRenderer.getPlacementRenderer() != null
                    ? BREffectRenderer.getPlacementRenderer().getActiveParticleCount() : 0,
                BREffectRenderer.getStructuralRenderer() != null
                    ? BREffectRenderer.getStructuralRenderer().getActiveFragmentCount() : 0)
        };

        for (String line : lines) {
            int width = font.width(line);
            gui.fill(x - 1, y - 1, x + width + 1, y + lineHeight - 1, bgColor);
            gui.drawString(font, line, x, y, 0xFFFFFF, false);
            y += lineHeight;
        }
    }

    /**
     * 渲染覆蓋層入口（由 BREffectRenderer 在 overlay pass 呼叫）。
     * 實際 HUD 繪製由事件系統觸發，此處為空佔位符。
     */
    public void render(Matrix4f projMatrix, Matrix4f viewMatrix) {
        // HUD 繪製由 RenderGameOverlayEvent 觸發，無需在此執行 GL 呼叫
    }

    void cleanup() {
        debugHudEnabled = false;
    }
}
