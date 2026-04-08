package com.blockreality.api.spi;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nonnull;

/**
 * Wind Simulation SPI — 風場模擬管理介面。
 *
 * <p>管理基於通用擴散求解器（壓力投射）+ 獨立 advection 步的風場模擬。
 * 風壓透過 q=½ρv²Cd 耦合到 PFSF 結構引擎。
 *
 * @since 1.1.0
 */
@SPIVersion(major = 1, minor = 0)
public interface IWindManager {

    void init(@Nonnull ServerLevel level);
    void tick(@Nonnull ServerLevel level, int tickBudgetMs);
    void shutdown();

    /** 查詢風速大小 (m/s) */
    float getWindSpeedAt(@Nonnull BlockPos pos);

    /** 查詢風壓 (Pa) = ½ρv² */
    float getWindPressureAt(@Nonnull BlockPos pos);

    /** 設置風源（風扇/開口） */
    void setWindSource(@Nonnull BlockPos pos, float speed, float dirX, float dirY, float dirZ);

    /** 移除風源 */
    void removeWindSource(@Nonnull BlockPos pos);

    int getActiveRegionCount();
}
