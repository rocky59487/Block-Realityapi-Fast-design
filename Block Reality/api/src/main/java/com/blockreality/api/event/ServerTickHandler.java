package com.blockreality.api.event;

import com.blockreality.api.BlockRealityMod;
import com.blockreality.api.collapse.CollapseManager;
import com.blockreality.api.fragment.StructureFragmentManager;
import com.blockreality.api.config.BRConfig;
import com.blockreality.api.construction.ConstructionZoneManager;
import com.blockreality.api.network.BRNetwork;
import com.blockreality.api.physics.ConnectivityCache;
import com.blockreality.api.physics.StructureIslandRegistry;
import com.blockreality.api.physics.pfsf.PFSFEngine;
import com.blockreality.api.spi.ModuleRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Server tick 事件處理器 — v3fix §3.2 + §3.4
 *
 * 負責驅動每 tick 的佇列消費：
 *   - CollapseManager.processQueue()：處理分批坍方
 *   - ConstructionZoneManager.tickCuring()：養護進度檢查
 *
 * 世界卸載時清空佇列，避免跨世界洩漏。
 */
@Mod.EventBusSubscriber(modid = BlockRealityMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ServerTickHandler {

    private static final Logger LOGGER = LogManager.getLogger("BR-Tick");

    /** 養護檢查頻率：每 20 ticks (1 秒) 檢查一次，減少開銷 */
    private static final int CURING_CHECK_INTERVAL = 20;

    /** AD-7 快取驅逐頻率：每 200 ticks (10 秒) 清理一次過期快取 */
    private static final int CACHE_EVICTION_INTERVAL = 200;

    /**
     * 每 server tick 結束時驅動坍方佇列及養護管理。
     * ★ F-2 fix: 移除無用的 curingTickCounter（養護已由 onLevelTick 驅動）。
     * ★ v3fix §3.4: 驅動 CuringManager.tickCuring() 以推進混凝土養護進度。
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        // ★ Physics enabled check: skip physics processing if disabled
        // CollapseManager queue is always processed regardless of physics state
        boolean physicsEnabled = BRConfig.isPhysicsEnabled();

        // 驅動坍方佇列（始終運作，與物理引擎狀態無關）
        if (CollapseManager.hasPending()) {
            CollapseManager.processQueue();
        }

        // Early return if physics is disabled - skip all physics-related processing
        if (!physicsEnabled) {
            return;
        }

        // ★ v3fix §3.4: 推進所有活躍中的混凝土養護
        java.util.Set<BlockPos> curedBlocks = ModuleRegistry.getCuringManager().tickCuring();

        // ★ L-5 fix: 對已完成養護的方塊觸發 CuringProgressEvent
        if (!curedBlocks.isEmpty()) {
            MinecraftServer srv = ServerLifecycleHooks.getCurrentServer();
            if (srv != null) {
                ServerLevel overworld = srv.overworld();
                for (BlockPos pos : curedBlocks) {
                    MinecraftForge.EVENT_BUS.post(
                        new CuringProgressEvent(overworld, pos, 1.0f, true));
                }
            }
        }

        // ═══ PFSF GPU 物理引擎 ═══
        if (BRConfig.isPFSFEnabled() && PFSFEngine.isAvailable()) {
            MinecraftServer srvPfsf = ServerLifecycleHooks.getCurrentServer();
            if (srvPfsf != null) {
                java.util.List<net.minecraft.server.level.ServerPlayer> players =
                        srvPfsf.getPlayerList().getPlayers();
                long epoch = ConnectivityCache.getStructureEpoch();
                ServerLevel overworld = srvPfsf.overworld();
                PFSFEngine.onServerTick(overworld, players, epoch);
            }
        }

        // ═══ PFSF-Fluid 流體引擎 ═══
        if (BRConfig.isFluidEnabled()) {
            com.blockreality.api.spi.IFluidManager fluidMgr = ModuleRegistry.getFluidManager();
            if (fluidMgr != null) {
                MinecraftServer srvFluid = ServerLifecycleHooks.getCurrentServer();
                if (srvFluid != null) {
                    ServerLevel overworld = srvFluid.overworld();
                    fluidMgr.tick(overworld, BRConfig.getFluidTickBudgetMs());
                }
                // 更新流體→結構壓力 lookup（供下一 tick PFSF 使用）
                com.blockreality.api.physics.fluid.FluidStructureCoupler.updatePressureLookup();
            }
        }

        // ═══ PFSF-Thermal 熱傳導引擎 ═══
        if (BRConfig.isThermalEnabled()) {
            com.blockreality.api.spi.IThermalManager thermalMgr = ModuleRegistry.getThermalManager();
            if (thermalMgr != null) {
                MinecraftServer srvThermal = ServerLifecycleHooks.getCurrentServer();
                if (srvThermal != null) {
                    thermalMgr.tick(srvThermal.overworld(), BRConfig.getThermalTickBudgetMs());
                }
            }
        }

        // ═══ PFSF-Wind 風場引擎 ═══
        if (BRConfig.isWindEnabled()) {
            var windMgr = ModuleRegistry.getWindManager();
            if (windMgr != null) {
                MinecraftServer srvW = ServerLifecycleHooks.getCurrentServer();
                if (srvW != null) windMgr.tick(srvW.overworld(), BRConfig.getWindTickBudgetMs());
            }
        }

        // ═══ PFSF-EM 電磁場引擎 ═══
        if (BRConfig.isEmEnabled()) {
            var emMgr = ModuleRegistry.getEmManager();
            if (emMgr != null) {
                MinecraftServer srvE = ServerLifecycleHooks.getCurrentServer();
                if (srvE != null) emMgr.tick(srvE.overworld(), BRConfig.getEmTickBudgetMs());
            }
        }

        // ═══ 跨域耦合（Wind↔Thermal, EM↔Thermal） ═══
        if (BRConfig.isThermalEnabled() && (BRConfig.isWindEnabled() || BRConfig.isEmEnabled())) {
            com.blockreality.api.physics.coupling.MultiDomainCoupler.tick();
        }

        // H6-fix revised: 每 tick 結束重置崩塌抑制旗標
        // （創造模式的 suppress 只在事件觸發的當 tick 有效）
        CollapseManager.setSuppressCollapse(false);

        // ★ AD-7: 定期驅逐過期快取條目，防止記憶體洩漏
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null && server.getTickCount() % CACHE_EVICTION_INTERVAL == 0) {
            ConnectivityCache.evictStaleEntries();
        }
    }

    /**
     * 每 level tick 結束時驅動養護檢查。
     * 養護系統在所有維度運作（現實混凝土不因維度而改變固化速度）。
     */
    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel level)) return;

        // ─── Fragment manager: spawn pending fragments every tick ───
        StructureFragmentManager.get(level).tick();

        if (level.getServer().getTickCount() % CURING_CHECK_INTERVAL != 0) return;

        ConstructionZoneManager manager = ConstructionZoneManager.get(level);
        if (manager.getZoneCount() > 0) {
            manager.tickCuring(level, level.getServer().getTickCount());
        }
    }

    /**
     * ★ P6-fix: 玩家離線時清理封包頻率限制快取。
     * 防止 lastPacketTime Map 隨時間無限膨脹。
     */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp) {
            BRNetwork.cleanupPlayer(sp.getUUID());
        }
    }

    /**
     * 世界卸載時清空坍方佇列。
     */
    @SubscribeEvent
    public static void onWorldUnload(LevelEvent.Unload event) {
        CollapseManager.clearQueue();
        // 僅在 Overworld 卸載（= 伺服器關閉）時清除全域狀態
        if (event.getLevel() instanceof ServerLevel sl && sl.dimension() == net.minecraft.world.level.Level.OVERWORLD) {
            StructureIslandRegistry.clear();
            LOGGER.debug("[BR-Tick] Island registry cleared on overworld unload (server shutdown)");
        }
        LOGGER.debug("[BR-Tick] Collapse queue cleared on world unload");
    }
}
