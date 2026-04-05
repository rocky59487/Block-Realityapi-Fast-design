package com.blockreality.api.physics.pfsf;

import com.blockreality.api.physics.StressField;
import net.minecraft.core.BlockPos;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.*;

import static org.lwjgl.vulkan.VK10.*;

/**
 * PFSF 應力場提取器 — 從 GPU 讀回 phi/maxPhi 並產生 CPU 端 StressField。
 *
 * <p>Server-safe：不依賴 @OnlyIn(CLIENT) 類別。</p>
 */
public final class PFSFStressExtractor {

    private static final Logger LOGGER = LoggerFactory.getLogger("PFSF-Stress");

    private PFSFStressExtractor() {}

    /**
     * 從 GPU 讀回 phi[] 並產生 StressField。
     *
     * @param buf island buffer
     * @return StressField（stress 0~2 normalized），或空 StressField
     */
    static StressField extractStressField(PFSFIslandBuffer buf) {
        if (buf == null || !buf.isAllocated()) {
            return new StressField(Map.of(), Set.of());
        }

        int N = buf.getN();
        Map<BlockPos, Float> stressValues = new HashMap<>();
        Set<BlockPos> damagedBlocks = new HashSet<>();

        float[] phiData = readFloatBuffer(buf.getPhiBuf(), N);
        float[] maxPhiData = readFloatBuffer(buf.getMaxPhiBuf(), N);

        if (phiData == null || maxPhiData == null) {
            return new StressField(Map.of(), Set.of());
        }

        for (int i = 0; i < N; i++) {
            if (phiData[i] <= 0) continue;
            float maxPhi = Math.max(maxPhiData[i], 1.0f);
            float stress = phiData[i] / maxPhi;

            BlockPos pos = buf.fromFlatIndex(i);
            stressValues.put(pos, Math.min(stress, 2.0f));

            if (stress >= 1.0f) {
                damagedBlocks.add(pos);
            }
        }

        return new StressField(stressValues, damagedBlocks);
    }

    /**
     * 讀回 GPU float buffer 到 CPU 陣列。
     */
    static float[] readFloatBuffer(long gpuBuffer, int count) {
        try {
            long size = (long) count * Float.BYTES;
            long[] staging = VulkanComputeContext.allocateStagingBuffer(size);

            VkCommandBuffer cmdBuf = VulkanComputeContext.beginSingleTimeCommands();
            org.lwjgl.vulkan.VkBufferCopy.Buffer region = org.lwjgl.vulkan.VkBufferCopy.calloc(1)
                    .srcOffset(0).dstOffset(0).size(size);
            vkCmdCopyBuffer(cmdBuf, gpuBuffer, staging[0], region);
            region.free();
            VulkanComputeContext.endSingleTimeCommands(cmdBuf);

            ByteBuffer mapped = VulkanComputeContext.mapBuffer(staging[1], size);
            float[] result = new float[count];
            mapped.asFloatBuffer().get(result);
            VulkanComputeContext.unmapBuffer(staging[1]);

            VulkanComputeContext.freeBuffer(staging[0], staging[1]);
            return result;
        } catch (Throwable e) {
            LOGGER.error("[PFSF] Failed to read GPU buffer: {}", e.getMessage());
            return null;
        }
    }
}
