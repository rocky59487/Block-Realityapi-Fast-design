package com.blockreality.api.client.rendering.bridge;

import com.blockreality.api.client.render.pipeline.BRRenderTier;
import com.blockreality.api.client.rendering.lod.LODRenderDispatcher;
import com.blockreality.api.client.rendering.vulkan.RTCompositePass;
import com.blockreality.api.client.rendering.vulkan.VkSwapchain;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forge RenderLevelStageEvent → RT/LOD 渲染橋接器（Phase 3 更新）。
 *
 * 渲染流程：
 *   AFTER_SOLID_BLOCKS:
 *     1. LOD terrain OpenGL 渲染（Phase 1）
 *     2. Vulkan RT dispatch（Phase 3）：camera UBO 更新 → TLAS 重建 → traceRays 提交
 *
 *   AFTER_TRANSLUCENT_BLOCKS:
 *     3. GL composite pass（Phase 3）：RT 輸出 texture（RGBA16F）50% 透明度疊加
 *
 * @see LODRenderDispatcher
 * @see RTCompositePass
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = "blockreality", value = Dist.CLIENT)
public class ForgeRenderEventBridge {

    private static final Logger LOG = LoggerFactory.getLogger("BR-RenderBridge");

    private static LODRenderDispatcher lodDispatcher;
    private static boolean lodEnabled = false;
    private static boolean rtEnabled  = false;

    /**
     * 初始化渲染橋接（模組客戶端初始化時呼叫）。
     *
     * Phase 3 新增：初始化 RTCompositePass GL 程式。
     */
    public static void init(LODRenderDispatcher dispatcher) {
        lodDispatcher = dispatcher;
        lodEnabled = BRRenderTier.isFeatureEnabled("voxy_lod");
        rtEnabled  = BRRenderTier.isFeatureEnabled("ray_tracing");

        // Phase 3：初始化 GL composite pass（需要在 GL context 建立後呼叫）
        if (rtEnabled) {
            boolean ok = RTCompositePass.init();
            if (!ok) {
                LOG.warn("RTCompositePass init failed — composite overlay disabled");
                rtEnabled = false;
            }
        }

        LOG.info("ForgeRenderEventBridge init — LOD: {}, RT: {}", lodEnabled, rtEnabled);
    }

    /**
     * 清理（關閉/斷線時呼叫）。
     */
    public static void cleanup() {
        if (RTCompositePass.isInitialized()) {
            RTCompositePass.cleanup();
        }
        lodDispatcher = null;
        lodEnabled    = false;
        rtEnabled     = false;
        LOG.info("ForgeRenderEventBridge cleanup");
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        // 優先使用注入的 dispatcher，否則使用單例
        LODRenderDispatcher disp = lodDispatcher != null
            ? lodDispatcher
            : LODRenderDispatcher.getInstance();

        // ─── AFTER_SOLID_BLOCKS：LOD 地形渲染 + Vulkan RT dispatch ───
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) {
            if (lodEnabled && disp.isInitialized()) {
                // render() 內部會執行：
                //   LOD OpenGL terrain render（Phase 1）
                //   VulkanRT：camera UBO 更新 → TLAS 重建 → recordAndSubmitRT（Phase 3）
                disp.render(event.getPartialTick());
            }
        }

        // ─── AFTER_TRANSLUCENT_BLOCKS：GL composite pass ───
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            if (rtEnabled && disp.isRTEnabled() && disp.isRTDispatchedThisFrame()) {
                VkSwapchain swapchain = disp.getVkSwapchain();
                if (swapchain != null && swapchain.isReady()) {
                    // Phase 3 GL-Vulkan 同步：等待本幀 RT shader 執行完畢後再取用 texture。
                    // Phase 4 升級為 VK_KHR_external_semaphore + GL_EXT_semaphore（零 CPU stall）。
                    swapchain.waitForCurrentFrameRT();

                    int texId = swapchain.getGLTextureId();
                    if (texId != 0) {
                        RTCompositePass.render(texId);
                    }
                }
            }
        }
    }
}
