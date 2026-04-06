package com.blockreality.api.spi;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;

/**
 * Fluid Simulation SPI — 流體模擬管理介面。
 *
 * <p>管理基於勢場的流體擴散系統（PFSF-Fluid），每個方塊儲存流體勢能、
 * 類型與壓力值，水從高勢能流向低勢能（Jacobi 迭代）。
 *
 * <p>流體系統預設關閉，由 {@code BRConfig.isFluidEnabled()} 控制。
 * 啟用後透過 {@link ModuleRegistry#setFluidManager(IFluidManager)} 註冊實作。
 *
 * @since 1.0.0
 */
@SPIVersion(major = 1, minor = 0)
public interface IFluidManager {

    /**
     * 初始化流體引擎（在 Mod 初始化階段呼叫一次）。
     *
     * @param level 主世界實例（用於存取方塊狀態）
     */
    void init(@Nonnull ServerLevel level);

    /**
     * 推進流體物理一個 tick。
     *
     * <p>此方法在 ServerTickEvent.Post 中呼叫，
     * 受 {@code BRConfig.getFluidTickBudgetMs()} 時間預算限制。
     *
     * @param level 當前世界
     * @param tickBudgetMs 本 tick 可用的計算時間（毫秒）
     */
    void tick(@Nonnull ServerLevel level, int tickBudgetMs);

    /**
     * 關閉流體引擎，釋放所有 GPU/CPU 資源。
     */
    void shutdown();

    /**
     * 查詢指定位置的流體壓力（Pa）。
     *
     * <p>由 {@code FluidPressureCoupler} 呼叫，提供給 PFSF 結構引擎
     * 作為 source term 注入。
     *
     * @param pos 查詢位置
     * @return 流體壓力（Pa），無流體時返回 0
     */
    float getFluidPressureAt(@Nonnull BlockPos pos);

    /**
     * 查詢指定位置的流體體積分率。
     *
     * @param pos 查詢位置
     * @return 體積分率 [0, 1]，0 = 空氣，1 = 完全充滿
     */
    float getFluidVolumeAt(@Nonnull BlockPos pos);

    /**
     * 通知流體引擎：指定位置的固體牆面已被移除（崩塌事件）。
     *
     * <p>由 {@code FluidBarrierBreachEvent} 監聽器呼叫，
     * 讓流體可以湧入新開放的空間。
     *
     * @param pos 被移除的固體方塊位置
     */
    void notifyBarrierBreach(@Nonnull BlockPos pos);

    /**
     * 批次通知：多個固體牆面同時被移除（結構大範圍崩塌）。
     *
     * <p>預設實作逐位置呼叫 {@link #notifyBarrierBreach}；
     * 實作類可覆寫以一次性重整拓撲，避免多次標記 dirty。
     *
     * @param positions 被移除的固體方塊位置集合
     */
    default void notifyBarrierBreachBatch(@Nonnull Collection<BlockPos> positions) {
        for (BlockPos pos : positions) {
            notifyBarrierBreach(pos);
        }
    }

    /**
     * 在指定位置設置流體源。
     *
     * @param pos 流體源位置
     * @param type 流體類型（見 {@code FluidType}）
     * @param volume 初始體積分率 [0, 1]
     */
    void setFluidSource(@Nonnull BlockPos pos, int type, float volume);

    /**
     * 移除指定位置的流體。
     *
     * @param pos 要移除流體的位置
     */
    void removeFluid(@Nonnull BlockPos pos);

    /**
     * 取得活動流體區域數量。
     *
     * @return 當前活動的流體模擬區域數
     */
    int getActiveRegionCount();

    /**
     * 取得流體體素總數。
     *
     * @return 所有活動區域中非空氣體素的總數
     */
    int getTotalFluidVoxelCount();
}
