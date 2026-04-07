package com.blockreality.api.spi;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nonnull;

/**
 * Thermal Simulation SPI — 熱傳導模擬管理介面。
 *
 * <p>管理基於通用擴散求解器的熱傳導系統。溫度場透過 Jacobi/RBGS 迭代擴散，
 * 並與結構引擎耦合（熱應力 → PFSF source term）。
 *
 * <p>系統預設關閉，由 {@code BRConfig.isThermalEnabled()} 控制。
 *
 * @since 1.1.0
 */
@SPIVersion(major = 1, minor = 0)
public interface IThermalManager {

    void init(@Nonnull ServerLevel level);
    void tick(@Nonnull ServerLevel level, int tickBudgetMs);
    void shutdown();

    /** 查詢溫度 (°C)，環境溫度返回 20.0 */
    float getTemperatureAt(@Nonnull BlockPos pos);

    /** 設置持續熱源 */
    void setHeatSource(@Nonnull BlockPos pos, float temperature);

    /** 移除熱源 */
    void removeHeatSource(@Nonnull BlockPos pos);

    int getActiveRegionCount();
}
