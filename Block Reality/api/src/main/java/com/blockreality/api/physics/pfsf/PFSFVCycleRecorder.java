package com.blockreality.api.physics.pfsf;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;

import static com.blockreality.api.physics.pfsf.PFSFConstants.*;
import static com.blockreality.api.physics.pfsf.PFSFPipelineFactory.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * PFSF GPU Dispatch 錄製器 — Jacobi 迭代 + V-Cycle 多重網格。
 *
 * <p>從 PFSFEngine 提取的 §5.5 GPU Dispatch 邏輯。</p>
 */
public final class PFSFVCycleRecorder {

    private PFSFVCycleRecorder() {}

    // ─── Single Jacobi Step ───

    static void recordJacobiStep(VkCommandBuffer cmdBuf, PFSFIslandBuffer buf,
                                  float omega, long descriptorPool) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, jacobiPipeline);

            long ds = VulkanComputeContext.allocateDescriptorSet(descriptorPool, jacobiDSLayout);
            VulkanComputeContext.bindBufferToDescriptor(ds, 0, buf.getPhiBuf(), 0, buf.getPhiSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 1, buf.getPhiPrevBuf(), 0, buf.getPhiSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 2, buf.getSourceBuf(), 0, buf.getPhiSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 3, buf.getConductivityBuf(), 0, buf.getConductivitySize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 4, buf.getTypeBuf(), 0, buf.getTypeSize());

            vkCmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE,
                    jacobiPipelineLayout, 0, stack.longs(ds), null);

            // M1-fix: damping 由 PFSFIslandBuffer.dampingActive 控制
            float damping = buf.dampingActive ? DAMPING_FACTOR : 0.0f;
            ByteBuffer pc = stack.malloc(28);
            pc.putInt(buf.getLx()).putInt(buf.getLy()).putInt(buf.getLz());
            pc.putFloat(omega).putFloat(buf.rhoSpecOverride);
            pc.putInt(buf.chebyshevIter).putFloat(damping);
            pc.flip();

            vkCmdPushConstants(cmdBuf, jacobiPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);
            vkCmdDispatch(cmdBuf, ceilDiv(buf.getLx(), WG_X), ceilDiv(buf.getLy(), WG_Y), ceilDiv(buf.getLz(), WG_Z));
            VulkanComputeContext.computeBarrier(cmdBuf);
        }
    }

    // ─── V-Cycle ───

    static void recordVCycle(VkCommandBuffer cmdBuf, PFSFIslandBuffer buf, long descriptorPool) {
        if (!buf.isAllocated()) return;
        buf.allocateMultigrid();

        float omega = PFSFScheduler.getTickOmega(buf);

        // 1. Pre-smooth: 2 Jacobi on fine
        recordJacobiStep(cmdBuf, buf, omega, descriptorPool);
        buf.swapPhi();
        recordJacobiStep(cmdBuf, buf, omega, descriptorPool);
        buf.swapPhi();

        // 2. Restrict: fine → L1
        recordRestrict(cmdBuf, buf, descriptorPool);

        // 3. Coarse solve: 4 Jacobi on L1
        for (int i = 0; i < 4; i++) {
            recordCoarseJacobi(cmdBuf, buf, omega, descriptorPool);
            buf.swapPhiL1();
        }

        // 4. Prolong: L1 → fine
        recordProlong(cmdBuf, buf, descriptorPool);

        // 5. Post-smooth: 2 Jacobi on fine
        recordJacobiStep(cmdBuf, buf, omega, descriptorPool);
        recordJacobiStep(cmdBuf, buf, omega, descriptorPool);
    }

    // ─── Restrict: fine → coarse ───

    private static void recordRestrict(VkCommandBuffer cmdBuf, PFSFIslandBuffer buf, long descriptorPool) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, restrictPipeline);

            long ds = VulkanComputeContext.allocateDescriptorSet(descriptorPool, restrictDSLayout);
            VulkanComputeContext.bindBufferToDescriptor(ds, 0, buf.getPhiBuf(), 0, buf.getPhiSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 1, buf.getSourceBuf(), 0, buf.getPhiSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 2, buf.getConductivityBuf(), 0, buf.getConductivitySize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 3, buf.getTypeBuf(), 0, buf.getTypeSize());

            long nL1 = (long) buf.getLxL1() * buf.getLyL1() * buf.getLzL1() * Float.BYTES;
            VulkanComputeContext.bindBufferToDescriptor(ds, 4, buf.getPhiL1Buf(), 0, nL1);
            VulkanComputeContext.bindBufferToDescriptor(ds, 5, buf.getSourceL1Buf(), 0, nL1);

            vkCmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE,
                    restrictPipelineLayout, 0, stack.longs(ds), null);

            ByteBuffer pc = stack.malloc(24);
            pc.putInt(buf.getLx()).putInt(buf.getLy()).putInt(buf.getLz());
            pc.putInt(buf.getLxL1()).putInt(buf.getLyL1()).putInt(buf.getLzL1());
            pc.flip();
            vkCmdPushConstants(cmdBuf, restrictPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);

            int nCoarse = buf.getLxL1() * buf.getLyL1() * buf.getLzL1();
            vkCmdDispatch(cmdBuf, ceilDiv(nCoarse, WG_SCAN), 1, 1);
            VulkanComputeContext.computeBarrier(cmdBuf);
        }
    }

    // ─── Prolong: coarse → fine ───

    private static void recordProlong(VkCommandBuffer cmdBuf, PFSFIslandBuffer buf, long descriptorPool) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, prolongPipeline);

            long ds = VulkanComputeContext.allocateDescriptorSet(descriptorPool, prolongDSLayout);
            VulkanComputeContext.bindBufferToDescriptor(ds, 0, buf.getPhiBuf(), 0, buf.getPhiSize());

            long nL1 = (long) buf.getLxL1() * buf.getLyL1() * buf.getLzL1() * Float.BYTES;
            VulkanComputeContext.bindBufferToDescriptor(ds, 1, buf.getPhiL1Buf(), 0, nL1);

            vkCmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE,
                    prolongPipelineLayout, 0, stack.longs(ds), null);

            ByteBuffer pc = stack.malloc(24);
            pc.putInt(buf.getLx()).putInt(buf.getLy()).putInt(buf.getLz());
            pc.putInt(buf.getLxL1()).putInt(buf.getLyL1()).putInt(buf.getLzL1());
            pc.flip();
            vkCmdPushConstants(cmdBuf, prolongPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);

            vkCmdDispatch(cmdBuf, ceilDiv(buf.getLx(), WG_X), ceilDiv(buf.getLy(), WG_Y), ceilDiv(buf.getLz(), WG_Z));
            VulkanComputeContext.computeBarrier(cmdBuf);
        }
    }

    // ─── Coarse Jacobi ───

    private static void recordCoarseJacobi(VkCommandBuffer cmdBuf, PFSFIslandBuffer buf,
                                            float omega, long descriptorPool) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, jacobiPipeline);

            int nL1 = buf.getN_L1();
            long phiSizeL1 = (long) nL1 * Float.BYTES;
            long condSizeL1 = (long) nL1 * 6 * Float.BYTES;
            long typeSizeL1 = nL1;

            long ds = VulkanComputeContext.allocateDescriptorSet(descriptorPool, jacobiDSLayout);
            VulkanComputeContext.bindBufferToDescriptor(ds, 0, buf.getPhiL1Buf(), 0, phiSizeL1);
            VulkanComputeContext.bindBufferToDescriptor(ds, 1, buf.getPhiPrevL1Buf(), 0, phiSizeL1);
            VulkanComputeContext.bindBufferToDescriptor(ds, 2, buf.getSourceL1Buf(), 0, phiSizeL1);
            VulkanComputeContext.bindBufferToDescriptor(ds, 3, buf.getConductivityL1Buf(), 0, condSizeL1);
            VulkanComputeContext.bindBufferToDescriptor(ds, 4, buf.getTypeL1Buf(), 0, typeSizeL1);

            vkCmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE,
                    jacobiPipelineLayout, 0, stack.longs(ds), null);

            float damping = buf.dampingActive ? DAMPING_FACTOR : 0.0f;
            ByteBuffer pc = stack.malloc(28);
            pc.putInt(buf.getLxL1()).putInt(buf.getLyL1()).putInt(buf.getLzL1());
            pc.putFloat(omega).putFloat(buf.rhoSpecOverride);
            pc.putInt(buf.chebyshevIter).putFloat(damping);
            pc.flip();
            vkCmdPushConstants(cmdBuf, jacobiPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);

            vkCmdDispatch(cmdBuf, ceilDiv(buf.getLxL1(), WG_X), ceilDiv(buf.getLyL1(), WG_Y), ceilDiv(buf.getLzL1(), WG_Z));
            VulkanComputeContext.computeBarrier(cmdBuf);
        }
    }

    public static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
