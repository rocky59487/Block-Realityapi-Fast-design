package com.blockreality.api.physics.fluid;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 流體 GPU 緩衝生命週期管理器。
 *
 * <p>追蹤所有流體區域的 VRAM 使用量，在超過預算時
 * 休眠遠距區域。遵循 {@code PFSFBufferManager} 的
 * 分配/釋放/預算追蹤模式。
 */
public class FluidBufferManager {

    private static final Logger LOGGER = LogManager.getLogger("BR-FluidBufMgr");

    /** 流體 VRAM 預算（bytes），與 PFSF 共享 512MB 上限 */
    private static final long FLUID_VRAM_BUDGET = 128L * 1024 * 1024; // 128 MB

    private static final Map<Integer, FluidRegionBuffer> buffers = new ConcurrentHashMap<>();
    private static final AtomicLong allocatedBytes = new AtomicLong(0);

    /**
     * 取得或建立指定區域的 GPU 緩衝。
     *
     * @param region CPU 端流體區域
     * @return GPU 緩衝，或 null（VRAM 不足）
     */
    public static FluidRegionBuffer getOrCreate(FluidRegion region) {
        return buffers.computeIfAbsent(region.getRegionId(), id -> {
            FluidRegionBuffer buf = new FluidRegionBuffer(id);
            long estimatedBytes = estimateBufferSize(
                region.getSizeX(), region.getSizeY(), region.getSizeZ());

            if (allocatedBytes.get() + estimatedBytes > FLUID_VRAM_BUDGET) {
                LOGGER.warn("[BR-FluidBufMgr] VRAM budget exceeded ({}/{} bytes), " +
                    "cannot allocate region #{}", allocatedBytes.get(), FLUID_VRAM_BUDGET, id);
                return null;
            }

            buf.allocate(region.getSizeX(), region.getSizeY(), region.getSizeZ(),
                new net.minecraft.core.BlockPos(
                    region.getOriginX(), region.getOriginY(), region.getOriginZ()));
            allocatedBytes.addAndGet(estimatedBytes);
            return buf;
        });
    }

    /**
     * 釋放指定區域的 GPU 緩衝。
     */
    public static void release(int regionId) {
        FluidRegionBuffer buf = buffers.remove(regionId);
        if (buf != null) {
            long freedBytes = buf.estimateVRAMBytes();
            buf.free();
            allocatedBytes.addAndGet(-freedBytes);
            LOGGER.debug("[BR-FluidBufMgr] Released region #{}, freed {} bytes", regionId, freedBytes);
        }
    }

    /**
     * 釋放所有 GPU 緩衝。
     */
    public static void releaseAll() {
        buffers.values().forEach(FluidRegionBuffer::free);
        buffers.clear();
        allocatedBytes.set(0);
        LOGGER.info("[BR-FluidBufMgr] Released all fluid buffers");
    }

    /** 當前 VRAM 使用量（bytes） */
    public static long getAllocatedBytes() { return allocatedBytes.get(); }

    /** VRAM 預算（bytes） */
    public static long getVRAMBudget() { return FLUID_VRAM_BUDGET; }

    /** 已分配的緩衝數量 */
    public static int getBufferCount() { return buffers.size(); }

    private static long estimateBufferSize(int lx, int ly, int lz) {
        long n = (long) lx * ly * lz;
        // phi×2 + density + volume + pressure + boundary = 6N×4
        // type = N, velocity = 3N×4, staging = N×4
        return n * (6 * 4 + 1 + 3 * 4 + 4);
    }
}
