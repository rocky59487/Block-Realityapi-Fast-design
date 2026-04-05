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
 *   <li>Phi Max Reduction — max φ 歸約（stub，用 failCount proxy）</li>
 * </ul>
 */
public final class PFSFFailureRecorder {

    private PFSFFailureRecorder() {}

    // ─── Failure Scan ───

    static void recordFailureScan(VkCommandBuffer cmdBuf, PFSFIslandBuffer buf, long descriptorPool) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, failurePipeline);

            long ds = VulkanComputeContext.allocateDescriptorSet(descriptorPool, failureDSLayout);
            VulkanComputeContext.bindBufferToDescriptor(ds, 0, buf.getPhiBuf(), 0, buf.getPhiSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 1, buf.getConductivityBuf(), 0, buf.getConductivitySize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 2, buf.getMaxPhiBuf(), 0, buf.getPhiSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 3, buf.getRcompBuf(), 0, buf.getPhiSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 4, buf.getTypeBuf(), 0, buf.getTypeSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 5, buf.getFailFlagsBuf(), 0, buf.getN());
            VulkanComputeContext.bindBufferToDescriptor(ds, 6, buf.getRtensBuf(), 0, buf.getPhiSize());

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
            VulkanComputeContext.bindBufferToDescriptor(ds, 1, buf.getSourceBuf(), 0, buf.getPhiSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 2, buf.getConductivityBuf(), 0, buf.getConductivitySize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 3, buf.getTypeBuf(), 0, buf.getTypeSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 4, buf.getMaxPhiBuf(), 0, buf.getPhiSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 5, buf.getRcompBuf(), 0, buf.getPhiSize());

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
            VulkanComputeContext.bindBufferToDescriptor(ds, 0, buf.getFailFlagsBuf(), 0, buf.getTypeSize());
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

    // ─── Phi Max Reduction (stub) ───

    static void recordPhiMaxReduction(VkCommandBuffer cmdBuf, PFSFIslandBuffer buf,
                                       PFSFAsyncCompute.ComputeFrame frame) {
        // Stub: full GPU-side two-pass reduction deferred to Phase 2.
        // Divergence detection handled by Chebyshev + oscillation detection.
    }
}
