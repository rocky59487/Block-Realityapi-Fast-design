package com.blockreality.api.physics.pfsf;

import com.blockreality.api.physics.StructureIslandRegistry.StructureIsland;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * PFSF Buffer 生命週期管理器 — 管理每個 island 的 GPU buffer。
 *
 * <p>C1-fix: AABB 擴展偵測 — island 長大超出已分配尺寸時自動重配置。</p>
 * <p>A4-fix: 使用 release() 引用計數保護非同步回調安全。</p>
 */
public final class PFSFBufferManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("PFSF-Buffer");

    static final ConcurrentHashMap<Integer, PFSFIslandBuffer> buffers = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<Integer, PFSFSparseUpdate> sparseTrackers = new ConcurrentHashMap<>();

    private PFSFBufferManager() {}

    /**
     * 取得或建立 island 的 GPU buffer。
     * 整合 ComputeRangePolicy：VRAM 不足時自動降級到粗網格或拒絕。
     *
     * @return island buffer，或 null 若 VRAM 嚴重不足
     */
    static PFSFIslandBuffer getOrCreateBuffer(StructureIsland island) {
        BlockPos min = island.getMinCorner();
        BlockPos max = island.getMaxCorner();
        int Lx = max.getX() - min.getX() + 1;
        int Ly = max.getY() - min.getY() + 1;
        int Lz = max.getZ() - min.getZ() + 1;

        PFSFIslandBuffer existing = buffers.get(island.getId());

        // C1-fix: AABB 擴展偵測
        if (existing != null) {
            if (existing.getLx() < Lx || existing.getLy() < Ly || existing.getLz() < Lz
                    || !existing.getOrigin().equals(min)) {
                LOGGER.debug("[PFSF] Island {} AABB expanded ({}x{}x{} -> {}x{}x{}), reallocating",
                        island.getId(), existing.getLx(), existing.getLy(), existing.getLz(), Lx, Ly, Lz);
                buffers.remove(island.getId());
                existing.release();
                existing = null;
            }
        }

        if (existing != null) return existing;

        // VRAM 感知：決定計算配置
        VramBudgetManager budgetMgr = VulkanComputeContext.getVramBudgetManager();
        int voxelCount = Lx * Ly * Lz;
        ComputeRangePolicy.ComputeConfig config = ComputeRangePolicy.decide(budgetMgr, voxelCount);

        if (config == null) {
            LOGGER.warn("[PFSF] Island {} rejected: VRAM critical (pressure={})",
                    island.getId(), budgetMgr.getPressure());
            return null;
        }

        PFSFIslandBuffer buf = new PFSFIslandBuffer(island.getId());

        if (config.gridLevel() == ComputeRangePolicy.GridLevel.L1_COARSE) {
            int cLx = ceilDiv(Lx, 2);
            int cLy = ceilDiv(Ly, 2);
            int cLz = ceilDiv(Lz, 2);
            buf.allocate(cLx, cLy, cLz, min);
            buf.setCoarseOnly(true);
            LOGGER.info("[PFSF] Island {} allocated as L1_COARSE ({}x{}x{} → {}x{}x{})",
                    island.getId(), Lx, Ly, Lz, cLx, cLy, cLz);
        } else {
            buf.allocate(Lx, Ly, Lz, min);
        }

        if (config.allocateMultigrid()) {
            buf.allocateMultigrid();
        }

        PFSFIslandBuffer prev = buffers.putIfAbsent(island.getId(), buf);
        if (prev != null) {
            buf.release();
            return prev;
        }
        return buf;
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    /**
     * 移除 island buffer（island 銷毀時）。
     * A4-fix: release() 引用計數，歸零時才真正 free。
     */
    public static void removeBuffer(int islandId) {
        PFSFIslandBuffer buf = buffers.remove(islandId);
        if (buf != null) {
            buf.release();
        }
        sparseTrackers.remove(islandId);
    }

    static void freeAll() {
        for (PFSFIslandBuffer buf : buffers.values()) {
            buf.free();
        }
        buffers.clear();
        sparseTrackers.clear();
    }
}
