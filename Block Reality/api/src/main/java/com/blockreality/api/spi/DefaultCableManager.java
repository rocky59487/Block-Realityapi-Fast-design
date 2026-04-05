package com.blockreality.api.spi;

import com.blockreality.api.material.RMaterial;
import net.minecraft.core.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;

/**
 * 預設纜索管理器 — 佔位實作。
 *
 * <p>舊版纜索物理（CableElement/CableNode/CableState）已移除。
 * 此類別保留作為 {@link ICableManager} 的預設空實作，
 * 未來由 PFSF 原生纜索模組取代。
 */
public final class DefaultCableManager implements ICableManager {

    private static final Logger LOGGER = LogManager.getLogger("BR-CableMgr");

    @Override
    public void attachCable(@Nonnull BlockPos from, @Nonnull BlockPos to, @Nonnull RMaterial cableMaterial) {
        LOGGER.debug("[Cable] attachCable not implemented (old cable physics removed)");
    }

    @Override
    public void detachCable(@Nonnull BlockPos from, @Nonnull BlockPos to) {
        // no-op
    }

    @Override
    public int getCableCountAt(@Nonnull BlockPos pos) {
        return 0;
    }

    @Override
    public int tickCables() {
        return 0;
    }

    @Override
    public int removeChunkCables(@Nonnull net.minecraft.world.level.ChunkPos chunkPos) {
        return 0;
    }

    @Override
    public int getCableCount() {
        return 0;
    }
}
