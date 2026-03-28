package com.blockreality.api.client.render.effect;

import com.blockreality.api.client.render.optimization.BROptimizationEngine;
import com.blockreality.api.client.render.animation.BRAnimationEngine;
import com.blockreality.api.client.render.pipeline.BRRenderPipeline;
import com.blockreality.api.client.render.pipeline.RenderPassContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * UI 覆蓋層渲染器 — HUD 除錯資訊與狀態顯示。
 *
 * 顯示資訊（按 F3 風格在左上角）：
 *   - 渲染管線狀態（FPS、draw calls）
 *   - 優化引擎統計（frustum culled、cached sections）
 *   - 動畫引擎統計（活躍控制器數）
 *   - 特效統計（粒子數、碎片數）
 *
 * 僅在除錯模式啟用時顯示（可透過快捷鍵切換）。
 */
@OnlyIn(Dist.CLIENT)
public final class UIOverlayRenderer {

    private boolean debugHudEnabled = false;

    UIOverlayRenderer() {}

    /**
     * 切換除錯 HUD。
     */
    public void toggleDebugHud() {
        debugHudEnabled = !debugHudEnabled;
    }

    public boolean isDebugHudEnabled() { return debugHudEnabled; }

    /**
     * 渲染覆蓋層。
     * 注意：此方法在 3D 渲染階段被呼叫，HUD 文字需要在 RenderGuiEvent 中繪製。
     * 這裡只做數據收集，實際 HUD 繪製透過 renderHud() 方法。
     */
    void render(RenderPassContext ctx) {
        // 3D overlay pass — 目前無需渲染 3D HUD 元素
        // 2D HUD 由 renderHud() 在 GuiGraphics 上繪製
    }

    /**
     * 繪製 2D HUD（由 RenderGuiEvent.Post 呼叫）。
     */
    public void renderHud(GuiGraphics gui, float partialTick) {
        if (!debugHudEnabled) return;

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;

        int x = 4;
        int y = 40; // F3 下方留空
        int lineHeight = 10;
        int bgColor = 0x80000000; // 半透明黑底

        String[] lines = {
            "§6[Block Reality Render Pipeline]",
            String.format("§7Frame: §f%d  §7Pipeline: §a%s",
                BRRenderPipeline.getFrameCount(),
                BRRenderPipeline.isEnabled() ? "ON" : "OFF"),
            String.format("§7Frustum: §f%d visible §8/ §c%d culled",
                BROptimizationEngine.getLastCulledCount() > 0
                    ? BROptimizationEngine.getLastCulledCount() : 0,
                BROptimizationEngine.getLastCulledCount()),
            String.format("§7Draw Calls: §f%d  §7Cached Sections: §f%d",
                BROptimizationEngine.getLastDrawCallCount(),
                BROptimizationEngine.getCachedSectionCount()),
            String.format("§7Animations: §f%d active",
                BRAnimationEngine.getActiveControllerCount()),
            String.format("§7Effects: §f%d particles §f%d fragments",
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

    void cleanup() {
        debugHudEnabled = false;
    }
}
