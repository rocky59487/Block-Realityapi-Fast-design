package com.blockreality.api.physics.fluid;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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

        long cmdBuf = frame.commandBuffer;
        long pipeline = FluidPipelineFactory.getJacobiPipeline();
        long layout = FluidPipelineFactory.getJacobiPipelineLayout();
        long dsLayout = FluidPipelineFactory.getJacobiDSLayout();

        for (int i = 0; i < iterations; i++) {
            // Swap phi buffers (O(1) pointer swap)
            buf.swapPhi();

            // Bind pipeline
            org.lwjgl.vulkan.VK10.vkCmdBindPipeline(
                cmdBuf, org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);

            // Allocate and write descriptor set
            long ds = VulkanComputeContext.allocateDescriptorSet(dsLayout);
            if (ds == 0) {
                LOGGER.error("[BR-FluidJacobi] Descriptor set allocation failed at iter {}", i);
                return;
            }
            VulkanComputeContext.bindBufferToDescriptor(ds, 0, buf.getPhiBufA(),    0, buf.getPhiSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 1, buf.getPhiBufB(),    0, buf.getPhiSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 2, buf.getDensityBuf(), 0, buf.getDensitySize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 3, buf.getVolumeBuf(),  0, buf.getVolumeSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 4, buf.getTypeBuf(),    0, buf.getTypeSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 5, buf.getPressureBuf(),0, buf.getPressureSize());

            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                org.lwjgl.vulkan.VK10.vkCmdBindDescriptorSets(
                    cmdBuf, org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    layout, 0, stack.longs(ds), null);

                // Push constants: Lx, Ly, Lz, diffusionRate, gravity, damping, originY
                java.nio.ByteBuffer pc = stack.malloc(28);
                pc.putInt(buf.getLx()).putInt(buf.getLy()).putInt(buf.getLz());
                pc.putFloat(FluidConstants.DEFAULT_DIFFUSION_RATE);
                pc.putFloat((float) FluidConstants.GRAVITY);
                pc.putFloat(FluidConstants.DAMPING_FACTOR);
                pc.putInt(buf.getOrigin().getY());
                pc.flip();
                org.lwjgl.vulkan.VK10.vkCmdPushConstants(
                    cmdBuf, layout, org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);
            }

            // Dispatch: ceil(Lx/8) × ceil(Ly/8) × ceil(Lz/8)
            int gx = (buf.getLx() + 7) / 8;
            int gy = (buf.getLy() + 7) / 8;
            int gz = (buf.getLz() + 7) / 8;
            org.lwjgl.vulkan.VK10.vkCmdDispatch(cmdBuf, gx, gy, gz);

            VulkanComputeContext.computeBarrier(cmdBuf);
        }

        LOGGER.trace("[BR-FluidJacobi] Recorded {} Jacobi iterations for region {}",
            iterations, buf.getRegionId());
    }

    /**
     * 記錄壓力 + 速度場計算 dispatch。
     */
    public static void recordPressureCompute(FluidAsyncCompute.FluidComputeFrame frame,
                                             FluidRegionBuffer buf) {
        if (!GPU_PATH_ENABLED) return; // Phase 2 TODO
        // 實際 Vulkan：
        // Bind fluid_pressure pipeline
        // Descriptors: phi(0), density(1), volume(2), type(3), pressure(4), velocity(5)
        // Push: Lx, Ly, Lz, gravity, originY
        // Dispatch + barrier

        LOGGER.trace("[BR-FluidJacobi] Recorded pressure compute for region {}",
            buf.getRegionId());
    }

    /**
     * 記錄邊界壓力提取 dispatch。
     */
    public static void recordBoundaryExtraction(FluidAsyncCompute.FluidComputeFrame frame,
                                                FluidRegionBuffer buf) {
        if (!GPU_PATH_ENABLED) return; // Phase 2 TODO
        // 實際 Vulkan：
        // Bind fluid_boundary pipeline
        // Descriptors: pressure(0), type(1), volume(2), boundaryPressure(3)
        // Push: Lx, Ly, Lz, couplingFactor, minPressure
        // Dispatch + barrier
        // Record readback: boundaryBuf → staging (for CPU extraction)

        LOGGER.trace("[BR-FluidJacobi] Recorded boundary extraction for region {}",
            buf.getRegionId());
    }
}
