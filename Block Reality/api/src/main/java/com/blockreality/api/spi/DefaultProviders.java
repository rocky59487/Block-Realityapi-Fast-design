package com.blockreality.api.spi;

import com.blockreality.api.material.BlockType;
import com.blockreality.api.physics.RCFusionDetector;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * 預設 SPI 提供者工廠 — 隔離 {@link ModuleRegistry} 與具體實作之間的依賴。
 */
public final class DefaultProviders {

    private DefaultProviders() {}

    /**
     * 建立預設的 {@link IFusionDetector} 實作。
     * 委託給 {@link RCFusionDetector} 的靜態方法。
     */
    public static IFusionDetector createFusionDetector() {
        return new IFusionDetector() {
            @Override
            public int checkAndFuse(ServerLevel level, BlockPos pos) {
                return RCFusionDetector.checkAndFuse(level, pos);
            }

            @Override
            public int checkAndDowngrade(ServerLevel level, BlockPos brokenPos, BlockType brokenType) {
                return RCFusionDetector.checkAndDowngrade(level, brokenPos, brokenType);
            }
        };
    }
}
