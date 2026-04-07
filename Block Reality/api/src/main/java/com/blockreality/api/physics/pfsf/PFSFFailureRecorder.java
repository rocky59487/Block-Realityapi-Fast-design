package com.blockreality.api.physics.pfsf;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;

import static com.blockreality.api.physics.pfsf.PFSFConstants.*;
import static com.blockreality.api.physics.pfsf.PFSFPipelineFactory.*;
import static com.blockreality.api.physics.pfsf.PFSFVCycleRecorder.ceilDiv;
import static org.lwjgl.vulkan.VK10.*;

/**
 * PFSF 失效偵測管線 — failure scan / sparse scatter / failure compact / phi reduce。
 *
 * <p>管理 4 種 GPU dispatch：</p>
 * <ul>
 *   <li>Failure Scan — 4 模式斷裂偵測 (cantilever, crushing, no_support, tension)</li>
 *   <li>Sparse Scatter — 增量更新（37MB → ~200 bytes）</li>
 *   <li>Failure Compact — 壓縮 readback（1MB → ~100 bytes）</li>
 *   <li>Phi Max Reduction — max φ 兩階段 GPU 歸約（N → ceil(N/512) → 1）</li>
 * </ul>
 */
public final class PFSFFailureRecorder {

    private PFSFFailureRecorder() {}

    // ─── Failure Scan ───

    static void recordFailureScan(VkCommandBuffer cmdBuf, PFSFIslandBuffer buf, long descriptorPool) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, failurePipeline);

            long ds = VulkanComputeContext.allocateDescriptorSet(descriptorPool, failureDSLayout);
            VulkanComputeContext.bindBufferToDescriptor(ds, 0, buf.getPhiBuf(), buf.getPhiOffset(), buf.getPhiSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 1, buf.getConductivityBuf(), buf.getConductivityOffset(), buf.getConductivitySize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 2, buf.getMaxPhiBuf(), buf.getMaxPhiOffset(), buf.getPhiSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 3, buf.getRcompBuf(), buf.getRcompOffset(), buf.getPhiSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 4, buf.getTypeBuf(), buf.getTypeOffset(), buf.getTypeSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 5, buf.getFailFlagsBuf(), buf.getFailFlagsOffset(), buf.getN());
            VulkanComputeContext.bindBufferToDescriptor(ds, 6, buf.getRtensBuf(), buf.getRtensOffset(), buf.getPhiSize());

            vkCmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE,
                    failurePipelineLayout, 0, stack.longs(ds), null);

            ByteBuffer pc = stack.malloc(16);
            pc.putInt(buf.getLx()).putInt(buf.getLy()).putInt(buf.getLz());
            pc.putFloat(PHI_ORPHAN_THRESHOLD);
            pc.flip();
            vkCmdPushConstants(cmdBuf, failurePipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);

            vkCmdDispatch(cmdBuf, ceilDiv(buf.getN(), WG_SCAN), 1, 1);
            VulkanComputeContext.computeBarrier(cmdBuf);
        }
    }

    // ─── Sparse Scatter ───

    static void recordSparseScatter(VkCommandBuffer cmdBuf, PFSFIslandBuffer buf,
                                     PFSFSparseUpdate sparse, int updateCount, long descriptorPool) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, scatterPipeline);

            long ds = VulkanComputeContext.allocateDescriptorSet(descriptorPool, scatterDSLayout);
            VulkanComputeContext.bindBufferToDescriptor(ds, 0, sparse.getUploadBuffer(), 0, sparse.getUploadSize(updateCount));
            VulkanComputeContext.bindBufferToDescriptor(ds, 1, buf.getSourceBuf(), buf.getSourceOffset(), buf.getPhiSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 2, buf.getConductivityBuf(), buf.getConductivityOffset(), buf.getConductivitySize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 3, buf.getTypeBuf(), buf.getTypeOffset(), buf.getTypeSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 4, buf.getMaxPhiBuf(), buf.getMaxPhiOffset(), buf.getPhiSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 5, buf.getRcompBuf(), buf.getRcompOffset(), buf.getPhiSize());

            vkCmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE,
                    scatterPipelineLayout, 0, stack.longs(ds), null);

            ByteBuffer pc = stack.malloc(8);
            pc.putInt(updateCount).putInt(buf.getN());
            pc.flip();
            vkCmdPushConstants(cmdBuf, scatterPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);

            vkCmdDispatch(cmdBuf, ceilDiv(updateCount, 64), 1, 1);
            VulkanComputeContext.computeBarrier(cmdBuf);
        }
    }

    // ─── Failure Compact ───

    static void recordFailureCompact(VkCommandBuffer cmdBuf, PFSFIslandBuffer buf,
                                      PFSFAsyncCompute.ComputeFrame frame, long descriptorPool) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long compactSize = (long) (MAX_FAILURE_PER_TICK + 2) * 4;
            long[] compactBuf = VulkanComputeContext.allocateDeviceBuffer(compactSize,
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT);

            vkCmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, compactPipeline);

            long ds = VulkanComputeContext.allocateDescriptorSet(descriptorPool, compactDSLayout);
            VulkanComputeContext.bindBufferToDescriptor(ds, 0, buf.getFailFlagsBuf(), buf.getFailFlagsOffset(), buf.getTypeSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 1, compactBuf[0], 0, compactSize);

            vkCmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE,
                    compactPipelineLayout, 0, stack.longs(ds), null);

            ByteBuffer pc = stack.malloc(8);
            pc.putInt(buf.getN()).putInt(MAX_FAILURE_PER_TICK);
            pc.flip();
            vkCmdPushConstants(cmdBuf, compactPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);

            vkCmdDispatch(cmdBuf, ceilDiv(buf.getN(), WG_SCAN), 1, 1);
            VulkanComputeContext.computeBarrier(cmdBuf);

            frame.readbackStagingBuf = PFSFAsyncCompute.recordReadback(frame, compactBuf[0], compactSize);
            frame.readbackN = buf.getN();
            // A3-fix: 延遲釋放
            frame.deferredFreeBuffers = compactBuf;
        }
    }

    // ─── Phi Max Reduction (two-pass GPU reduction) ───

    /**
     * GPU 端 phi 最大值歸約：N → ceil(N/512) → 1，只讀回 1 個 float。
     * 替代讀回整個 phi[]（4MB → 4 bytes）。
     *
     * <p>兩階段歸約：</p>
     * <ol>
     *   <li>Pass 1: phi[N] → partial[ceil(N/512)]（每 workgroup 256 threads，每 thread 處理 2 元素）</li>
     *   <li>Pass 2: partial[ceil(N/512)] → final[1]</li>
     * </ol>
     */
    static void recordPhiMaxReduction(VkCommandBuffer cmdBuf, PFSFIslandBuffer buf,
                                       PFSFAsyncCompute.ComputeFrame frame) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int N = buf.getN();
            // 每 workgroup 256 threads × 2 元素 = 512 元素
            int numGroups1 = ceilDiv(N, 512);

            // Pass 1 output buffer: numGroups1 floats
            long partialSize = (long) numGroups1 * Float.BYTES;
            long[] partialBuf = VulkanComputeContext.allocateDeviceBuffer(partialSize,
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT);

            // ─── Pass 1: phi[N] → partial[numGroups1] ───
            vkCmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, reduceMaxPipeline);

            long ds1 = VulkanComputeContext.allocateDescriptorSet(
                    PFSFEngine.getDescriptorPool(), reduceMaxDSLayout);
            VulkanComputeContext.bindBufferToDescriptor(ds1, 0, buf.getPhiBuf(), buf.getPhiOffset(), buf.getPhiSize());
            VulkanComputeContext.bindBufferToDescriptor(ds1, 1, partialBuf[0], 0, partialSize);

            vkCmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE,
                    reduceMaxPipelineLayout, 0, stack.longs(ds1), null);

            ByteBuffer pc1 = stack.malloc(8);
            pc1.putInt(N);       // N
            pc1.putInt(0);       // isPass2 = 0
            pc1.flip();
            vkCmdPushConstants(cmdBuf, reduceMaxPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc1);

            vkCmdDispatch(cmdBuf, numGroups1, 1, 1);
            VulkanComputeContext.computeBarrier(cmdBuf);

            // ─── Pass 2: partial[numGroups1] → final[1] ───
            // Reuse partialBuf as input, output to a 1-float buffer
            long[] finalBuf = VulkanComputeContext.allocateDeviceBuffer(Float.BYTES,
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT);

            long ds2 = VulkanComputeContext.allocateDescriptorSet(
                    PFSFEngine.getDescriptorPool(), reduceMaxDSLayout);
            VulkanComputeContext.bindBufferToDescriptor(ds2, 0, partialBuf[0], 0, partialSize);
            VulkanComputeContext.bindBufferToDescriptor(ds2, 1, finalBuf[0], 0, Float.BYTES);

            vkCmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE,
                    reduceMaxPipelineLayout, 0, stack.longs(ds2), null);

            ByteBuffer pc2 = stack.malloc(8);
            pc2.putInt(numGroups1); // N = numGroups1
            pc2.putInt(1);          // isPass2 = 1
            pc2.flip();
            vkCmdPushConstants(cmdBuf, reduceMaxPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc2);

            int numGroups2 = ceilDiv(numGroups1, 512);
            vkCmdDispatch(cmdBuf, Math.max(numGroups2, 1), 1, 1);
            VulkanComputeContext.computeBarrier(cmdBuf);

            // ─── Readback: final → staging (4 bytes) ───
            org.lwjgl.vulkan.VkBufferCopy.Buffer region = org.lwjgl.vulkan.VkBufferCopy.calloc(1)
                    .srcOffset(0).dstOffset(0).size(Float.BYTES);
            vkCmdCopyBuffer(cmdBuf, finalBuf[0], frame.phiMaxStagingBuf[0], region);
            region.free();
            VulkanComputeContext.computeBarrier(cmdBuf);

            // Deferred free: partialBuf + finalBuf
            frame.phiMaxPartialBuf = new long[]{partialBuf[0], partialBuf[1], finalBuf[0], finalBuf[1]};
        }
    }
}
