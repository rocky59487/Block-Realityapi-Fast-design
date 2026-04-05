package com.blockreality.api;

import com.blockreality.api.command.LoadPathCommand;
import com.blockreality.api.command.MaterialInfoCommand;
import com.blockreality.api.command.PhysicsTestCommand;
import com.blockreality.api.command.PhysicsToggleCommand;
import com.blockreality.api.command.RenderToggleCommand;
import com.blockreality.api.command.SnapshotTestCommand;
import com.blockreality.api.command.StressAnalysisCommand;
import com.blockreality.api.client.ClientSetup;
import com.blockreality.api.collapse.CollapseManager;
import com.blockreality.api.config.BRConfig;
import com.blockreality.api.material.VanillaMaterialMap;
import com.blockreality.api.network.BRNetwork;
import com.blockreality.api.physics.AnchorContinuityChecker;
import com.blockreality.api.physics.PhysicsExecutor;
import com.blockreality.api.physics.BFSConnectivityAnalyzer;
import com.blockreality.api.physics.pfsf.PFSFEngine;
import com.blockreality.api.registry.BRBlockEntities;
import com.blockreality.api.registry.BRBlocks;
import com.blockreality.api.sidecar.SidecarBridge;
import com.blockreality.api.sph.SPHStressEngine;
import com.blockreality.api.spi.ModuleRegistry;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.Registries;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(BlockRealityMod.MOD_ID)
public class BlockRealityMod {
    public static final String MOD_ID = "blockreality";
    private static final Logger LOGGER = LogManager.getLogger("BlockReality");

    // ─── Creative Tab ───
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
        DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID);

    public static final RegistryObject<CreativeModeTab> BR_TAB = CREATIVE_TABS.register("br_tab",
        () -> CreativeModeTab.builder()
            .icon(() -> new ItemStack(BRBlocks.R_CONCRETE.get()))
            .title(Component.literal("Block Reality"))
            .displayItems((params, output) -> {
                output.accept(BRBlocks.R_CONCRETE_ITEM.get());
                output.accept(BRBlocks.R_REBAR_ITEM.get());
                output.accept(BRBlocks.R_STEEL_ITEM.get());
                output.accept(BRBlocks.R_TIMBER_ITEM.get());
                output.accept(BRBlocks.CHISEL.get());
            })
            .build()
    );

    public BlockRealityMod() {
        IEventBus modBus = net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus();

        // ─── 註冊 Deferred Registers ───
        BRBlocks.BLOCKS.register(modBus);
        BRBlocks.ITEMS.register(modBus);
        BRBlockEntities.BLOCK_ENTITIES.register(modBus);
        CREATIVE_TABS.register(modBus);

        // ─── 註冊 Config ───
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, BRConfig.SPEC);

        // ─── Lifecycle events ───
        modBus.addListener(this::commonSetup);

        // ─── 客戶端初始化（安全分離，伺服器不載入 client 類）───
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            ClientSetup.initForgeEvents();
        });

        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("[BlockReality] Mod 初始化完成 — v0.2.0-alpha (R-unit system)");
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            BRNetwork.register();
            VanillaMaterialMap.getInstance().init();
            LOGGER.info("[BlockReality] Network channel registered, VanillaMaterialMap loaded ({} entries)",
                VanillaMaterialMap.getInstance().size());
        });
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        SnapshotTestCommand.register(event.getDispatcher());
        PhysicsTestCommand.register(event.getDispatcher());
        MaterialInfoCommand.register(event.getDispatcher());
        LoadPathCommand.register(event.getDispatcher());
        StressAnalysisCommand.register(event.getDispatcher());
        RenderToggleCommand.register(event.getDispatcher());
        PhysicsToggleCommand.register(event.getDispatcher());
        LOGGER.info("[BlockReality] 已註冊指令: /br_snapshot, /br_physics, /br_material, /br_loadpath, /br_stress, /br_render, /br_physics_toggle");

        // ★ Register commands from all modules
        for (var provider : ModuleRegistry.getCommandProviders()) {
            try {
                provider.registerCommands(event.getDispatcher());
                LOGGER.debug("[BlockReality] Registered commands from module: {}", provider.getModuleId());
            } catch (RuntimeException e) {
                LOGGER.error("[BlockReality] Error registering commands from module {}: {}",
                    provider.getModuleId(), e.getMessage(), e);
            }
        }
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // 啟動 Sidecar IPC
        try {
            SidecarBridge.getInstance().startAsync();
            LOGGER.info("[BlockReality] Sidecar 已觸發非同步啟動流程");
        } catch (Exception e) {
            LOGGER.error("[BlockReality] Sidecar 啟動失敗，CAD 功能將不可用", e);
        }

        // 啟動物理運算執行緒池
        PhysicsExecutor.start();

        // ─── B1-fix: 初始化 PFSF GPU 物理引擎 ───
        try {
            com.blockreality.api.physics.pfsf.VulkanComputeContext.init();
            PFSFEngine.init();
            if (PFSFEngine.isAvailable()) {
                // 設定材料/錨點/填充率查詢函數
                PFSFEngine.setMaterialLookup(pos -> {
                    net.minecraft.server.MinecraftServer srv =
                            net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
                    if (srv == null) return null;
                    net.minecraft.server.level.ServerLevel level = srv.overworld();
                    if (level == null) return null;
                    net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
                    String blockId = net.minecraftforge.registries.ForgeRegistries.BLOCKS
                            .getKey(state.getBlock()).toString();
                    return VanillaMaterialMap.getInstance().getMaterial(blockId);
                });
                PFSFEngine.setAnchorLookup(pos -> {
                    net.minecraft.server.MinecraftServer srv =
                            net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
                    if (srv == null) return false;
                    net.minecraft.server.level.ServerLevel level = srv.overworld();
                    if (level == null) return false;
                    return AnchorContinuityChecker.getInstance().isAnchored(level, pos);
                });
                PFSFEngine.setFillRatioLookup(pos -> 1.0f); // 預設滿填充
                LOGGER.info("[BlockReality] PFSF GPU 物理引擎已啟動");
            } else {
                LOGGER.info("[BlockReality] PFSF 不可用（無 Vulkan），使用 CPU 物理引擎");
            }
        } catch (Exception e) {
            LOGGER.warn("[BlockReality] PFSF 初始化失敗（非致命），使用 CPU 物理引擎: {}",
                    e.getMessage());
        }
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        // Sidecar ping 測試
        try {
            JsonObject result = SidecarBridge.getInstance().call("ping", new JsonObject(), 3000);
            LOGGER.info("[BlockReality] Sidecar ping 測試成功: {}", result);
        } catch (Exception e) {
            LOGGER.warn("[BlockReality] Sidecar ping 測試失敗（非致命）: {}", e.getMessage());
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        PhysicsExecutor.shutdown();
        SPHStressEngine.shutdown();
        PFSFEngine.shutdown();  // B1-fix: PFSF 清理
        SidecarBridge.getInstance().stop();

        // 清理快取（避免跨世界洩漏）
        AnchorContinuityChecker.getInstance().clearCache();
        BFSConnectivityAnalyzer.clearCache();
        CollapseManager.clearQueue();

        LOGGER.info("[BlockReality] All engines & Sidecar stopped, caches cleared");
    }
}
