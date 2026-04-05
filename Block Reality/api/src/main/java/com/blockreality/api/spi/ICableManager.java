package com.blockreality.api.spi;

import com.blockreality.api.material.RMaterial;
import net.minecraft.core.BlockPos;

import javax.annotation.Nonnull;
import java.util.Set;

/**
 * Cable Management SPI — 纜索張力物理管理。
 *
 * <p>舊版纜索物理系統已移除（CableElement/CableNode/CableState），
 * 保留此介面作為 SPI 擴展點，未來可由 PFSF 原生纜索模組實作。
 *
 * @since 1.0.0
 */
@SPIVersion(major = 1, minor = 0)
public interface ICableManager {

    /** 在兩個方塊之間附加纜索（暫不支援，拋出 UnsupportedOperationException）。 */
    void attachCable(@Nonnull BlockPos from, @Nonnull BlockPos to, @Nonnull RMaterial cableMaterial);

    /** 移除纜索。 */
    void detachCable(@Nonnull BlockPos from, @Nonnull BlockPos to);

    /** 獲得與給定位置連接的纜索數量。 */
    int getCableCountAt(@Nonnull BlockPos pos);

    /** 推進纜索物理一個 tick。回傳斷裂的纜索端點對數量。 */
    int tickCables();

    /** 移除指定區塊內的纜索。 */
    int removeChunkCables(@Nonnull net.minecraft.world.level.ChunkPos chunkPos);

    /** 活躍纜索總數。 */
    int getCableCount();
}
