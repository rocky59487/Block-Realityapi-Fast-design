package com.blockreality.api.physics.fluid;

import com.blockreality.api.config.BRConfig;
import com.blockreality.api.event.FluidBarrierBreachEvent;
import com.blockreality.api.spi.IFluidManager;
import com.blockreality.api.spi.ModuleRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.function.Function;

/**
 * 流體-結構雙向耦合協調器。
 *
 * <h3>方向 A：流體壓力 → 結構失效</h3>
 * <p>每 tick 從 {@code FluidGPUEngine} 取得邊界壓力 map，
 * 轉換為 {@code PFSFEngine} 的 source term lookup function。
 * PFSF 在下一 tick 的 {@code PFSFDataBuilder.updateSourceAndConductivity()}
 * 中自動拾取。
 *
 * <h3>方向 B：結構崩塌 → 流體釋放</h3>
 * <p>監聽 {@code FluidBarrierBreachEvent}，通知流體引擎
 * 將崩塌的固體體素轉為 AIR。
 *
 * <h3>時序（每 tick）</h3>
 * <pre>
 * 1. PFSF 結構求解（8ms）— 使用上一 tick 的流體壓力
 * 2. FluidGPUEngine.tick()（4ms）— 計算新的流體壓力
 * 3. FluidStructureCoupler.updatePressureLookup() — 準備下一 tick 的 lookup
 * </pre>
 */
public class FluidStructureCoupler {

    private static final Logger LOGGER = LogManager.getLogger("BR-FluidCoupler");

    private static volatile Function<BlockPos, Float> pressureLookup = pos -> 0f;

    /**
     * 更新壓力 lookup function（每 tick 呼叫一次）。
     *
     * <p>從 FluidGPUEngine 取得邊界壓力 cache，包裝成 Function
     * 供 PFSFEngine.setFluidPressureLookup() 使用。
     */
    public static void updatePressureLookup() {
        if (!BRConfig.isFluidEnabled()) {
            pressureLookup = pos -> 0f;
            return;
        }

        IFluidManager manager = ModuleRegistry.getFluidManager();
        if (manager == null) {
            pressureLookup = pos -> 0f;
            return;
        }

        // 如果是 FluidGPUEngine，直接使用其快取
        if (manager instanceof FluidGPUEngine gpuEngine) {
            Map<BlockPos, Float> cache = gpuEngine.getBoundaryPressureCache();
            pressureLookup = pos -> cache.getOrDefault(pos, 0f);
        } else {
            // 通用路徑：逐位置查詢
            pressureLookup = manager::getFluidPressureAt;
        }
    }

    /**
     * 取得目前的壓力 lookup function。
     *
     * <p>由 {@code PFSFDataBuilder} 在計算 source term 時呼叫。
     *
     * @return pos → 流體壓力 (Pa) 的 function，無流體返回 0
     */
    public static Function<BlockPos, Float> getPressureLookup() {
        return pressureLookup;
    }

    // ═══════════════════════════════════════════════════════
    //  方向 B：結構崩塌 → 流體釋放
    // ═══════════════════════════════════════════════════════

    /**
     * 監聽 FluidBarrierBreachEvent，通知流體引擎。
     */
    @SubscribeEvent
    public static void onBarrierBreach(FluidBarrierBreachEvent event) {
        IFluidManager manager = ModuleRegistry.getFluidManager();
        if (manager == null) return;

        // 使用批次介面：按區域分組後一次性 markDirty，避免 N 次 GPU 上傳觸發
        manager.notifyBarrierBreachBatch(event.getBreachedPositions());

        LOGGER.debug("[BR-FluidCoupler] Barrier breach batch: {} blocks opened",
            event.getBreachCount());
    }

    /**
     * 註冊事件監聽器（在 Mod 初始化時呼叫）。
     */
    public static void registerEventListeners() {
        MinecraftForge.EVENT_BUS.register(FluidStructureCoupler.class);
        LOGGER.info("[BR-FluidCoupler] Registered event listeners");
    }
}
