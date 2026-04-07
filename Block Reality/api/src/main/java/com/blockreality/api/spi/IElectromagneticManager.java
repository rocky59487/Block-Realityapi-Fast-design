package com.blockreality.api.spi;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * Electromagnetic Simulation SPI — 電磁場模擬管理介面。
 *
 * <p>管理基於通用擴散求解器的電位場模擬。
 * 透過 ∇²φ = -ρ_charge/ε 求解電位，E = -∇φ 計算電場，
 * J = σE 計算電流密度，P = J²/σ 計算 Joule 加熱（耦合到 Thermal）。
 *
 * @since 1.1.0
 */
@SPIVersion(major = 1, minor = 0)
public interface IElectromagneticManager {

    void init(@Nonnull ServerLevel level);
    void tick(@Nonnull ServerLevel level, int tickBudgetMs);
    void shutdown();

    /** 查詢電位 (V) */
    float getElectricPotentialAt(@Nonnull BlockPos pos);

    /** 查詢電流密度大小 (A/m²) */
    float getCurrentDensityAt(@Nonnull BlockPos pos);

    /** 設置電荷源 */
    void setChargeSource(@Nonnull BlockPos pos, float chargeDensity);

    /** 設置接地點 (Dirichlet BC: φ=0) */
    void setGroundPoint(@Nonnull BlockPos pos);

    /** 計算閃電路徑：從最高電位到接地點的梯度下降路徑 */
    @Nonnull
    List<BlockPos> computeLightningPath(@Nonnull BlockPos start);

    int getActiveRegionCount();
}
