package com.blockreality.api.physics.pfsf.vector;

import com.blockreality.api.physics.pfsf.PFSFIslandBuffer;
import com.blockreality.api.physics.pfsf.PFSFPipelineFactory;
import com.blockreality.api.physics.pfsf.VulkanComputeContext;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * PFSF WSS-HQR 向量場求解 dispatch 錄製器。
 *
 * <p>每個 8³ macro-block 映射到一個 Workgroup（512 threads = 16 warps）。
 * 在高應力巨集塊（stressRatio > 0.7）上執行局部 Householder QR，
 * 恢復精確的 3D 向量場（扭轉、複合剪切）。
 *
 * <p>插入位置：RBGS Phase 1 完成後、PCG Phase 2 開始前。
 */
public final class PFSFVectorRecorder {

    private static final Logger LOGGER = LoggerFactory.getLogger("PFSF-Vector");

    private PFSFVectorRecorder() {}

    /**
     * 錄製 WSS-HQR 向量場求解 dispatch。
     *
     * @param cmdBuf         Vulkan command buffer
     * @param buf            island GPU buffer（需已分配 vectorFieldBuf）
     * @param descriptorPool descriptor pool（由 PFSFDispatcher 管理）
     */
    public static void recordVectorSolve(VkCommandBuffer cmdBuf,
                                          PFSFIslandBuffer buf,
                                          long descriptorPool) {
        if (buf.getVectorFieldBuf() == 0) {
            LOGGER.warn("[PFSF-Vector] vectorFieldBuf not allocated for island {}", buf.getIslandId());
            return;
        }

        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            vkCmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE,
                PFSFPipelineFactory.vectorSolvePipeline);

            long ds = VulkanComputeContext.allocateDescriptorSet(
                descriptorPool, PFSFPipelineFactory.vectorSolveDSLayout);
            if (ds == 0) {
                LOGGER.error("[PFSF-Vector] Descriptor set allocation failed for island {}",
                    buf.getIslandId());
                return;
            }

            // Binding 0: phi (readonly)
            VulkanComputeContext.bindBufferToDescriptor(
                ds, 0, buf.getPhiBuf(), buf.getPhiOffset(), buf.getPhiSize());
            // Binding 1: conductivity (readonly)
            VulkanComputeContext.bindBufferToDescriptor(
                ds, 1, buf.getConductivityBuf(), buf.getConductivityOffset(), buf.getConductivitySize());
            // Binding 2: type (readonly)
            VulkanComputeContext.bindBufferToDescriptor(
                ds, 2, buf.getTypeBuf(), buf.getTypeOffset(), buf.getTypeSize());
            // Binding 3: vectorField (write)
            VulkanComputeContext.bindBufferToDescriptor(
                ds, 3, buf.getVectorFieldBuf(), 0, buf.getVectorFieldSize());

            vkCmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE,
                PFSFPipelineFactory.vectorSolvePipelineLayout, 0, stack.longs(ds), null);

            // Push constants: Lx, Ly, Lz, mbX, mbY, mbZ, stressThreshold
            int mbX = (buf.getLx() + 7) / 8;
            int mbY = (buf.getLy() + 7) / 8;
            int mbZ = (buf.getLz() + 7) / 8;

            ByteBuffer pc = stack.malloc(28);  // 6 uint + 1 float
            pc.putInt(buf.getLx()).putInt(buf.getLy()).putInt(buf.getLz());
            pc.putInt(mbX).putInt(mbY).putInt(mbZ);
            pc.putFloat(0.7f);   // stressThreshold
            pc.flip();

            vkCmdPushConstants(cmdBuf, PFSFPipelineFactory.vectorSolvePipelineLayout,
                VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);

            // Dispatch: one Workgroup per macro-block
            vkCmdDispatch(cmdBuf, mbX, mbY, mbZ);
            VulkanComputeContext.computeBarrier(cmdBuf);

            LOGGER.trace("[PFSF-Vector] Dispatched WSS-HQR for island {} ({}×{}×{} macro-blocks)",
                buf.getIslandId(), mbX, mbY, mbZ);
        }
    }
}
