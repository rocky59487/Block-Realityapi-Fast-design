package com.blockreality.api.spi;

import com.blockreality.api.material.RMaterial;
import com.blockreality.api.physics.CableElement;
import net.minecraft.core.BlockPos;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Set;

/**
 * Cable Management SPI — Manages rope/cable tension physics in the game world.
 *
 * 纜索管理 SPI — 管理遊戲世界中的繩索/纜索張力物理。
 *
 * The cable manager maintains a collection of cable elements between blocks,
 * handles cable creation/removal, and advances cable physics each tick.
 *
 * Responsibilities:
 * - Create and remove cable connections between blocks
 * - Query active cables and their properties
 * - Advance cable physics (update tension based on block positions)
 * - Detect broken cables and fire events
 * - Thread-safe access from game and physics threads
 *
 * Modules implementing this SPI should:
 * - Support concurrent read/write operations
 * - Fire CableTensionEvent when tension changes significantly
 * - Return broken cables from tickCables() for removal by caller
 *
 * @since 1.0.0
 */
@SPIVersion(major = 1, minor = 0)
public interface ICableManager {

    /**
     * Attach a cable between two blocks.
     *
     * 在兩個方塊之間附加纜索。
     *
     * Creates a new cable element with the given material and connects the two positions.
     * If a cable already exists between these positions, this may replace it (implementation-dependent).
     *
     * @param from            Starting block position
     * @param to              Ending block position
     * @param cableMaterial   Material for the cable (provides strength, density)
     * @return The newly created CableElement
     * @throws NullPointerException if any parameter is null
     */
    @Nonnull CableElement attachCable(@Nonnull BlockPos from, @Nonnull BlockPos to, @Nonnull RMaterial cableMaterial);

    /**
     * Detach (remove) a cable between two blocks.
     *
     * 移除兩個方塊之間的纜索。
     *
     * If no cable exists at this location, this call is a no-op (does not throw).
     *
     * @param from Starting block position
     * @param to   Ending block position
     */
    void detachCable(@Nonnull BlockPos from, @Nonnull BlockPos to);

    /**
     * Retrieve a specific cable element.
     *
     * 檢索特定的纜索元素。
     *
     * Returns the cable connecting the given endpoints, or null if no cable exists.
     * Note: The cable is identified by position pair, order may or may not matter
     * (implementation-dependent; typically bidirectional).
     *
     * @param from Starting block position
     * @param to   Ending block position
     * @return The CableElement, or null if not found
     */
    @Nullable CableElement getCable(@Nonnull BlockPos from, @Nonnull BlockPos to);

    /**
     * Get all cables connected to a given position.
     *
     * 獲得與給定位置連接的所有纜索。
     *
     * Returns a set of all cable elements where one endpoint is at the given position.
     * The returned set is a snapshot (safe to iterate); the underlying structure
     * may change between calls.
     *
     * @param pos The block position to query
     * @return A Set of CableElement (possibly empty, never null)
     */
    @Nonnull Set<CableElement> getCablesAt(@Nonnull BlockPos pos);

    /**
     * Advance cable physics by one tick.
     *
     * 將纜索物理推進一個刻。
     *
     * This method should be called once per server tick to:
     * - Recalculate tension based on current endpoint distances
     * - Check for broken cables (utilization > 1.0)
     * - Fire CableTensionEvent for significant changes
     * - Detect and accumulate broken cables
     *
     * Broken cables are returned in the set and should be removed by the caller
     * (or this method may auto-remove them, implementation-dependent).
     *
     * Thread-safe: Can be called from any thread, but best called from server tick.
     *
     * @return Set of CableElement that broke this tick (empty if none)
     */
    @Nonnull Set<CableElement> tickCables();

    /**
     * Remove all cables that have both endpoints inside the given chunk.
     *
     * 移除兩端都在指定區塊內的所有纜索。
     *
     * Called during chunk unload to prevent stale cable references.
     * Cables spanning chunk boundaries (one end in, one end out) are NOT removed;
     * they are left in a "frozen" state until the chunk reloads.
     *
     * @param chunkPos The chunk being unloaded
     * @return The number of cables removed
     */
    int removeChunkCables(@Nonnull net.minecraft.world.level.ChunkPos chunkPos);

    /**
     * Get the total number of active cables.
     *
     * 獲得活躍纜索的總數。
     *
     * Useful for diagnostics and load monitoring.
     *
     * @return The count of cables currently managed (≥ 0)
     */
    int getCableCount();
}
