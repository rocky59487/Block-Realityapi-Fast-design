package com.blockreality.api.physics.fluid;

import com.blockreality.api.physics.pfsf.VulkanComputeContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * 流體 Jacobi GPU 指令記錄器。
 *
 * <p>將 Jacobi 迭代、壓力計算和邊界提取的 GPU dispatch 指令
 * 記錄到 {@link FluidAsyncCompute.FluidComputeFrame} 的 command buffer 中。
 *
 * <p>遵循 {@code PFSFVCycleRecorder} 的記錄模式：
 * 綁定 pipeline → 分配描述子集 → 綁定 buffer → push constants → dispatch。
 */
public class FluidJacobiRecorder {

    private static final Logger LOGGER = LogManager.getLogger("BR-FluidJacobi");

    /**
     * GPU compute 路徑已啟用（Phase 2 完成）。
     * ghost cell valid_count 修正：fluid_jacobi.comp.glsl 使用動態分母，不再硬編碼 6.0。
     */
    private static final boolean GPU_PATH_ENABLED = true;

    /**
     * 記錄多步 Jacobi 擴散迭代。
     *
     * <p>每步：swap phi ↔ phiPrev → dispatch jacobi shader → barrier。
     *
     * @param frame 計算幀
     * @param buf 區域 GPU 緩衝
     * @param iterations 迭代次數
     */
    public static void recordJacobiIterations(FluidAsyncCompute.FluidComputeFrame frame,
                                              FluidRegionBuffer buf, int iterations) {
        if (!GPU_PATH_ENABLED) return;

        VkCommandBuffer cmdBuf = frame.commandBuffer;
        if (cmdBuf == null) return;

        long pool     = FluidPipelineFactory.getDescriptorPool();
        long pipeline = FluidPipelineFactory.getJacobiPipeline();
        long layout   = FluidPipelineFactory.getJacobiPipelineLayout();
        long dsLayout = FluidPipelineFactory.getJacobiDSLayout();

        for (int i = 0; i < iterations; i++) {
            // Swap phi buffers (O(1) pointer swap)
            buf.swapPhi();

            // Bind pipeline
            vkCmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);

            // Allocate and write descriptor set
            long ds = VulkanComputeContext.allocateDescriptorSet(pool, dsLayout);
            if (ds == 0) {
                LOGGER.error("[BR-FluidJacobi] Descriptor set allocation failed at iter {}", i);
                return;
            }
            long nFloats = (long) buf.getN() * Float.BYTES;
            VulkanComputeContext.bindBufferToDescriptor(ds, 0, buf.getPhiBufA()[0],    0, nFloats);
            VulkanComputeContext.bindBufferToDescriptor(ds, 1, buf.getPhiBufB()[0],    0, nFloats);
            VulkanComputeContext.bindBufferToDescriptor(ds, 2, buf.getDensityBuf()[0], 0, nFloats);
            VulkanComputeContext.bindBufferToDescriptor(ds, 3, buf.getVolumeBuf()[0],  0, nFloats);
            VulkanComputeContext.bindBufferToDescriptor(ds, 4, buf.getTypeBuf()[0],    0, (long) buf.getN()); // uint8[N]
            VulkanComputeContext.bindBufferToDescriptor(ds, 5, buf.getPressureBuf()[0],0, nFloats);

            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                vkCmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE,
                    layout, 0, stack.longs(ds), null);

                // Push constants: Lx, Ly, Lz, diffusionRate, gravity, damping, originY (28 bytes)
                java.nio.ByteBuffer pc = stack.malloc(28);
                pc.putInt(buf.getLx()).putInt(buf.getLy()).putInt(buf.getLz());
                pc.putFloat(FluidConstants.DEFAULT_DIFFUSION_RATE);
                pc.putFloat((float) FluidConstants.GRAVITY);
                pc.putFloat(FluidConstants.DAMPING_FACTOR);
                pc.putInt(buf.getOrigin().getY());
                pc.flip();
                vkCmdPushConstants(cmdBuf, layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);
            }

            // Dispatch: ceil(Lx/8) × ceil(Ly/8) × ceil(Lz/8)
            int gx = (buf.getLx() + 7) / 8;
            int gy = (buf.getLy() + 7) / 8;
            int gz = (buf.getLz() + 7) / 8;
            vkCmdDispatch(cmdBuf, gx, gy, gz);
            VulkanComputeContext.computeBarrier(cmdBuf);
        }

        LOGGER.trace("[BR-FluidJacobi] Recorded {} Jacobi iterations for region {}",
            iterations, buf.getRegionId());
    }

    /**
     * 記錄壓力 + 速度場計算 dispatch。
     *
     * <p>從 phi 場直接計算靜水壓及速度梯度（fluid_pressure.comp.glsl）。
     */
    public static void recordPressureCompute(FluidAsyncCompute.FluidComputeFrame frame,
                                             FluidRegionBuffer buf) {
        if (!GPU_PATH_ENABLED) return;

        VkCommandBuffer cmdBuf = frame.commandBuffer;
        if (cmdBuf == null) return;

        long pool     = FluidPipelineFactory.getDescriptorPool();
        long pipeline = FluidPipelineFactory.getPressurePipeline();
        long layout   = FluidPipelineFactory.getPressurePipelineLayout();
        long dsLayout = FluidPipelineFactory.getPressureDSLayout();

        vkCmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);

        long ds = VulkanComputeContext.allocateDescriptorSet(pool, dsLayout);
        if (ds == 0) {
            LOGGER.error("[BR-FluidJacobi] Pressure DS allocation failed for region {}", buf.getRegionId());
            return;
        }
        long nFloats = (long) buf.getN() * Float.BYTES;
        // phi(0), density(1), volume(2), type(3), pressure(4), velocity(5)
        VulkanComputeContext.bindBufferToDescriptor(ds, 0, buf.getPhiBufA()[0],    0, nFloats);
        VulkanComputeContext.bindBufferToDescriptor(ds, 1, buf.getDensityBuf()[0], 0, nFloats);
        VulkanComputeContext.bindBufferToDescriptor(ds, 2, buf.getVolumeBuf()[0],  0, nFloats);
        VulkanComputeContext.bindBufferToDescriptor(ds, 3, buf.getTypeBuf()[0],    0, (long) buf.getN());
        VulkanComputeContext.bindBufferToDescriptor(ds, 4, buf.getPressureBuf()[0],0, nFloats);
        VulkanComputeContext.bindBufferToDescriptor(ds, 5, buf.getVxBuf()[0], 0, nFloats * 3); // sub-cell vx (size >= 3N)

        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            vkCmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE,
                layout, 0, stack.longs(ds), null);

            // Push constants: Lx, Ly, Lz, gravity, originY (20 bytes)
            java.nio.ByteBuffer pc = stack.malloc(20);
            pc.putInt(buf.getLx()).putInt(buf.getLy()).putInt(buf.getLz());
            pc.putFloat((float) FluidConstants.GRAVITY);
            pc.putInt(buf.getOrigin().getY());
            pc.flip();
            vkCmdPushConstants(cmdBuf, layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);
        }

        int gx = (buf.getLx() + 7) / 8;
        int gy = (buf.getLy() + 7) / 8;
        int gz = (buf.getLz() + 7) / 8;
        vkCmdDispatch(cmdBuf, gx, gy, gz);
        VulkanComputeContext.computeBarrier(cmdBuf);

        LOGGER.trace("[BR-FluidJacobi] Recorded pressure compute for region {}", buf.getRegionId());
    }

    /**
     * 記錄邊界壓力提取 dispatch + GPU→CPU 讀回。
     *
     * <p>偵測固體壁面（type==4）相鄰的流體壓力，累積到 boundaryBuf，
     * 再透過 vkCmdCopyBuffer 讀回至 stagingBuf（供 CPU 結構耦合讀取）。
     */
    public static void recordBoundaryExtraction(FluidAsyncCompute.FluidComputeFrame frame,
                                                FluidRegionBuffer buf) {
        if (!GPU_PATH_ENABLED) return;

        VkCommandBuffer cmdBuf = frame.commandBuffer;
        if (cmdBuf == null) return;

        long pool     = FluidPipelineFactory.getDescriptorPool();
        long pipeline = FluidPipelineFactory.getBoundaryPipeline();
        long layout   = FluidPipelineFactory.getBoundaryPipelineLayout();
        long dsLayout = FluidPipelineFactory.getBoundaryDSLayout();

        vkCmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);

        long ds = VulkanComputeContext.allocateDescriptorSet(pool, dsLayout);
        if (ds == 0) {
            LOGGER.error("[BR-FluidJacobi] Boundary DS allocation failed for region {}", buf.getRegionId());
            return;
        }
        long nFloats = (long) buf.getN() * Float.BYTES;
        // pressure(0), type(1), volume(2), boundaryPressure(3)
        VulkanComputeContext.bindBufferToDescriptor(ds, 0, buf.getPressureBuf()[0], 0, nFloats);
        VulkanComputeContext.bindBufferToDescriptor(ds, 1, buf.getTypeBuf()[0],     0, (long) buf.getN());
        VulkanComputeContext.bindBufferToDescriptor(ds, 2, buf.getVolumeBuf()[0],   0, nFloats);
        VulkanComputeContext.bindBufferToDescriptor(ds, 3, buf.getBoundaryBuf()[0], 0, nFloats);

        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            vkCmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE,
                layout, 0, stack.longs(ds), null);

            // Push constants: Lx, Ly, Lz, couplingFactor, minPressure (20 bytes)
            java.nio.ByteBuffer pc = stack.malloc(20);
            pc.putInt(buf.getLx()).putInt(buf.getLy()).putInt(buf.getLz());
            pc.putFloat(FluidConstants.PRESSURE_COUPLING_FACTOR);
            pc.putFloat(FluidConstants.MIN_COUPLING_PRESSURE);
            pc.flip();
            vkCmdPushConstants(cmdBuf, layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);
        }

        int gx = (buf.getLx() + 7) / 8;
        int gy = (buf.getLy() + 7) / 8;
        int gz = (buf.getLz() + 7) / 8;
        vkCmdDispatch(cmdBuf, gx, gy, gz);

        // Readback: boundaryBuf → stagingBuf (for CPU structural coupling)
        VulkanComputeContext.computeToTransferBarrier(cmdBuf);
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkBufferCopy.Buffer region =
                org.lwjgl.vulkan.VkBufferCopy.calloc(1, stack)
                    .srcOffset(0).dstOffset(0).size(nFloats);
            vkCmdCopyBuffer(cmdBuf, buf.getBoundaryBuf()[0], buf.getStagingBuf()[0], region);
        }

        LOGGER.trace("[BR-FluidJacobi] Recorded boundary extraction for region {}", buf.getRegionId());
    }
}
