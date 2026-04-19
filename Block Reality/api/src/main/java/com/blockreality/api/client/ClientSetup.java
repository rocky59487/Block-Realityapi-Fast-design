package com.blockreality.api.client;

import com.blockreality.api.BlockRealityMod;
import com.blockreality.api.client.render.pipeline.BRRenderTier;
import com.blockreality.api.client.render.shader.BRShaderEngine;
import com.blockreality.api.spi.ModuleRegistry;
import com.mojang.blaze3d.platform.InputConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import com.blockreality.api.client.render.RBlockEntityRenderer;
import com.blockreality.api.client.render.StructureFragmentRenderer;
import com.blockreality.api.client.render.rt.NativeRenderRuntime;
import com.blockreality.api.registry.BRBlockEntities;
import com.blockreality.api.registry.BREntities;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 客戶端初始化 — v3fix §1.8
 *
 * MOD event bus 註冊：
 *   - R 鍵 KeyMapping（切換應力熱圖）
 *   - RenderLevelStageEvent 渲染掛接
 *
 * 重要：此類僅在 CLIENT 端載入（@OnlyIn + @Dist.CLIENT）
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = BlockRealityMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientSetup {

    private static final Logger LOGGER = LogManager.getLogger("BlockReality/ClientSetup");
    private static boolean pipelineInitFailed = false;
    private static boolean diagnosticSent = false;  // ★ 一次性診斷訊息

    /** 應力熱圖切換鍵 — 預設 R */
    public static final KeyMapping STRESS_OVERLAY_KEY = new KeyMapping(
        "key.blockreality.stress_overlay",   // 翻譯 key
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_R,
        "key.categories.blockreality"        // 分類
    );

    /**
     * MOD bus: 註冊按鍵映射。
     */
    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(STRESS_OVERLAY_KEY);
    }

    /**
     * MOD bus: 註冊 BlockEntityRenderers
     */
    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(BRBlockEntities.R_BLOCK_ENTITY.get(), RBlockEntityRenderer::new);
        event.registerEntityRenderer(BREntities.STRUCTURE_FRAGMENT.get(), StructureFragmentRenderer::new);
    }

    /**
     * 初始化 FORGE bus 的客戶端事件監聽。
     * 應在 FMLClientSetupEvent 中呼叫。
     */
    public static void initForgeEvents() {
        MinecraftForge.EVENT_BUS.register(ClientForgeEvents.class);

        // PR#187 capy-ai R18: register a JVM shutdown hook so the native
        // render runtime tears down before the process exits even when
        // Minecraft does not fire a clean client-stopping event (crash,
        // hard-kill, etc.). Default-OFF unless -Dblockreality.native.render=true,
        // so the hook is a no-op for every shipping client today.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                NativeRenderRuntime.shutdown();
            } catch (Throwable t) {
                LOGGER.warn("NativeRenderRuntime.shutdown (shutdown-hook) threw: {}", t.toString());
            }
        }, "BR-NativeRenderRuntime-shutdown"));
    }

    /**
     * FORGE event bus 的客戶端事件處理。
     * 分離到內部類，避免 MOD bus / FORGE bus 混用。
     */
    @OnlyIn(Dist.CLIENT)
    public static class ClientForgeEvents {

        // PR#187 capy-ai R18: one-shot sentinel so we only dispatch
        // NativeRenderRuntime.init() once per client session. The runtime
        // itself also guards with INIT_ATTEMPTED, but checking here avoids
        // the synchronized(lock) + property read every frame.
        private static final AtomicBoolean NATIVE_RENDER_INIT_DISPATCHED = new AtomicBoolean(false);

        /**
         * 渲染掛接 — 轉發到 StressHeatmapRenderer、HologramRenderer、AnchorPathRenderer、
         * 以及所有註冊的模組渲染層。
         *
         * <p><b>Phase 4-F 後的渲染路徑</b>：
         * BRRenderPipeline 已移除；BR 覆蓋渲染器直接掛接，主渲染由 Minecraft 原版管線負責。
         * RT 管線已停用，渲染系統改為單一開/關切換。
         */
        @SubscribeEvent
        public static void onRenderLevel(RenderLevelStageEvent event) {
            // ★ BUG-FIX: Ensure rendering tier and pipeline are initialized on the main render thread
            if (!BRRenderTier.isInitialized()) {
                BRRenderTier.init();
            }
            if (!BRShaderEngine.isInitialized()) {
                BRShaderEngine.init();
            }

            // PR#187 capy-ai R18: bootstrap the native RT runtime lazily on the
            // first render event. The window is guaranteed to exist by the time
            // RenderLevelStageEvent fires, so its dimensions are always valid.
            // The runtime is default-OFF; unless -Dblockreality.native.render=true
            // the call returns immediately without touching native code.
            if (NATIVE_RENDER_INIT_DISPATCHED.compareAndSet(false, true)) {
                try {
                    Minecraft mc = Minecraft.getInstance();
                    int w = Math.max(1, mc.getWindow().getWidth());
                    int h = Math.max(1, mc.getWindow().getHeight());
                    NativeRenderRuntime.init(w, h);
                    LOGGER.info("[BlockReality] {}", NativeRenderRuntime.getStatus());
                } catch (Throwable t) {
                    LOGGER.warn("NativeRenderRuntime.init threw (non-fatal): {}", t.toString());
                }
            }

            // ── GL 路徑（Phase 4-F：BRRenderPipeline 已移除，RT 管線已停用）────
            // BR 覆蓋渲染器直接掛接；主渲染由 Minecraft 原版管線負責。
            if (!diagnosticSent) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    diagnosticSent = true;
                    int ok   = BRShaderEngine.getCompiledCount();
                    int fail = BRShaderEngine.getFailedCount();
                    String tierName = BRRenderTier.getCurrentTier().name;
                    if (fail == 0) {
                        mc.player.displayClientMessage(
                            Component.literal("§a[BR] Shader 就緒 — " + ok +
                                " 個編譯成功 | Tier: " + tierName),
                            false);
                    } else {
                        mc.player.displayClientMessage(
                            Component.literal("§e[BR] Shader 部分就緒 — 成功 " + ok +
                                " / 失敗 " + fail + " | Tier: " + tierName +
                                " | 最後失敗: " + BRShaderEngine.getLastFailedShader()),
                            false);
                    }
                }
            }
            // ── 電影級攝影機震動（在所有渲染前套用偏移） ──
            com.blockreality.api.client.render.effect.CameraShakeManager.tick();
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
                com.blockreality.api.client.render.effect.CameraShakeManager.applyShake(
                        event.getPoseStack());
            }

            StressHeatmapRenderer.onRenderLevelStage(event);
            AnchorPathRenderer.render(event);
            GhostBlockRenderer.onRenderLevel(event);
            ModuleRegistry.fireRenderEvent(event);
        }

        /**
         * 客戶端 tick — 檢測按鍵切換。
         * 僅在非 GUI 狀態下響應（避免打字時誤觸）。
         */
        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;

            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;

            // 檢查 R 鍵是否被按下
            while (STRESS_OVERLAY_KEY.consumeClick()) {
                StressHeatmapRenderer.toggleOverlay();

                // HUD 提示訊息
                String state = StressHeatmapRenderer.isOverlayEnabled() ? "ON" : "OFF";
                mc.player.displayClientMessage(
                    Component.literal("§6[BR] §fStress Heatmap: §" +
                        (StressHeatmapRenderer.isOverlayEnabled() ? "a" : "c") + state),
                    true // actionbar
                );
            }
        }

}
}
