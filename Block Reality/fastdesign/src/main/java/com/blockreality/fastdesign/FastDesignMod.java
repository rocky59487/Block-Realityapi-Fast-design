package com.blockreality.fastdesign;

import com.blockreality.fastdesign.command.BlueprintCommand;
import com.blockreality.fastdesign.command.ConstructionCommand;
import com.blockreality.fastdesign.command.FdCommandRegistry;
import com.blockreality.fastdesign.command.HologramCommand;
import com.blockreality.fastdesign.command.UndoManager;
import com.blockreality.fastdesign.config.FastDesignConfig;
import com.blockreality.fastdesign.network.FdNetwork;
import com.blockreality.fastdesign.registry.FdCreativeTab;
import com.blockreality.fastdesign.registry.FdItems;
import com.blockreality.fastdesign.startup.StartupScanPipeline;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Fast Design 獨立模組入口 — 開發手冊 §1.3
 *
 * 負責擴充與互動層：CLI 指令、CAD 介面、Hologram 渲染。
 * 基礎設施（Blueprint、Construction Zone、PlayerSelection）由 Block Reality API 提供。
 */
@Mod(FastDesignMod.MOD_ID)
public class FastDesignMod {

    public static final String MOD_ID = "fastdesign";
    private static final Logger LOGGER = LogManager.getLogger("FastDesign");

    public FastDesignMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        // ─── 註冊 Deferred Registers ───
        FdItems.ITEMS.register(modBus);
        FdCreativeTab.TABS.register(modBus);

        // ─── 註冊 Config ───
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, FastDesignConfig.COMMON_SPEC);

        modBus.addListener(this::commonSetup);
        modBus.addListener(this::clientSetup);

        MinecraftForge.EVENT_BUS.register(this);

        // ★ 修復：註冊 LivePreviewBridge 到 FORGE event bus（客戶端限定）
        //   此橋接器負責將節點圖的即時變更推送到渲染管線。
        //   原先僅在 JavaDoc 中提及需要註冊，但實際上從未執行。
        DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT, () -> () -> {
            MinecraftForge.EVENT_BUS.register(
                com.blockreality.fastdesign.client.node.binding.LivePreviewBridge.getInstance()
            );
            LOGGER.info("[FastDesign] LivePreviewBridge 已註冊到 FORGE event bus");
        });

        // ★ 啟動掃描 UI 覆蓋層（客戶端限定）
        DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT, () -> () -> {
            MinecraftForge.EVENT_BUS.register(
                com.blockreality.fastdesign.client.StartupOverlayScreen.class
            );
            LOGGER.info("[FastDesign] StartupOverlayScreen 已註冊到 FORGE event bus");
        });

        LOGGER.info("[FastDesign] 模組初始化完成 — v1.1.0-alpha");
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            FdNetwork.register();
            LOGGER.info("[FastDesign] Network channel registered");
        });

        // Launch 7-phase startup scan on a dedicated daemon thread
        boolean isClient = net.minecraftforge.fml.loading.FMLLoader.getDist()
            == net.minecraftforge.api.distmarker.Dist.CLIENT;
        StartupScanPipeline.getInstance().start(isClient);
        LOGGER.info("[FastDesign] Startup scan pipeline launched");
    }

    /**
     * Client-only setup — registers node types, presets, and binders early
     * so they are available before any UI screen opens.
     *
     * P1-6: NodeRegistry.registerAll() moved here from NodeCanvasScreen.init()
     * P1-5: NodeGraphIO presets registered so BRRenderSettings.init() doesn't get null graphs
     */
    private void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            // P1-6: Register all 149 node types at mod load time (not at UI-open time).
            //        NodeRegistry.registerAll() has an internal guard so double-calls are safe.
            com.blockreality.fastdesign.client.node.NodeRegistry.registerAll();
            LOGGER.info("[FastDesign] NodeRegistry: {} types registered",
                    com.blockreality.fastdesign.client.node.NodeRegistry.registeredCount());

            // P1-5: Register quality-preset factories in the API-layer NodeGraphIO.
            //        BRRenderSettings.createPresetGraph() calls NodeGraphIO.loadPreset(),
            //        which previously returned null because nothing was registered.
            //        We register minimal (empty) graphs here; real preset wiring can
            //        be added incrementally via NodeGraphIO.registerNodeType() factories.
            com.blockreality.api.node.NodeGraphIO.registerPreset("potato",
                    () -> new com.blockreality.api.node.NodeGraph("preset_potato"));
            com.blockreality.api.node.NodeGraphIO.registerPreset("low",
                    () -> new com.blockreality.api.node.NodeGraph("preset_low"));
            com.blockreality.api.node.NodeGraphIO.registerPreset("medium",
                    () -> new com.blockreality.api.node.NodeGraph("preset_medium"));
            com.blockreality.api.node.NodeGraphIO.registerPreset("high",
                    () -> new com.blockreality.api.node.NodeGraph("preset_high"));
            com.blockreality.api.node.NodeGraphIO.registerPreset("ultra",
                    () -> new com.blockreality.api.node.NodeGraph("preset_ultra"));
            LOGGER.info("[FastDesign] NodeGraphIO: 5 quality presets registered");
        });
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        FdCommandRegistry.register(event.getDispatcher());
        BlueprintCommand.register(event.getDispatcher());
        ConstructionCommand.register(event.getDispatcher());
        HologramCommand.register(event.getDispatcher());
        LOGGER.info("[FastDesign] 已註冊指令: /fd, /br_blueprint, /br_zone");
    }

    /**
     * 玩家斷線清理 — 釋放 UndoManager 記憶體，防止長期洩漏。
     */
    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() != null) {
            UndoManager.onPlayerDisconnect(event.getEntity().getUUID());
        }
    }
}
