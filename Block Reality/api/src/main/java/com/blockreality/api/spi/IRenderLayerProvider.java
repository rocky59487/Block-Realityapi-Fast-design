package com.blockreality.api.spi;

import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Render Layer Provider SPI — Module interface for registering custom render layers.
 *
 * Modules implementing this interface can hook into the client render pipeline
 * and render custom geometry, overlays, or effects. The ModuleRegistry will
 * invoke onRenderLevelStage() during each frame if isEnabled() returns true.
 *
 * This SPI is CLIENT-ONLY and must be guarded with @OnlyIn(Dist.CLIENT).
 *
 * Example implementation (Fast Design visualization):
 * <pre>
 * @OnlyIn(Dist.CLIENT)
 * public class FDVisualizationRenderer implements IRenderLayerProvider {
 *     @Override
 *     public void onRenderLevelStage(RenderLevelStageEvent event) {
 *         if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
 *         // Render custom geometry
 *     }
 *
 *     @Override
 *     public boolean isEnabled() {
 *         return FDVisualizationState.isVisualizationActive();
 *     }
 *
 *     @Override
 *     public String getRenderLayerId() {
 *         return "fd_visualization";
 *     }
 * }
 * </pre>
 *
 * @since 1.0.0
 */
@OnlyIn(Dist.CLIENT)
@SPIVersion(major = 1, minor = 0)
public interface IRenderLayerProvider {

    /**
     * Called each frame during RenderLevelStageEvent if isEnabled() returns true.
     *
     * Modules can use the event's RenderLevelStageEvent.Stage to determine
     * when to render (before/after terrain, etc.).
     *
     * @param event The RenderLevelStageEvent containing PoseStack, MultiBufferSource, etc.
     */
    void onRenderLevelStage(RenderLevelStageEvent event);

    /**
     * Returns whether this render layer is currently enabled.
     *
     * This method is called each frame, allowing dynamic enable/disable
     * based on user settings, key presses, or mod state.
     *
     * @return true if rendering should be active, false otherwise
     */
    boolean isEnabled();

    /**
     * Returns a unique ID for this render layer.
     * Used for logging and debugging.
     *
     * @return A unique, lowercase identifier (e.g., "fd_load_visualization")
     */
    String getRenderLayerId();
}
