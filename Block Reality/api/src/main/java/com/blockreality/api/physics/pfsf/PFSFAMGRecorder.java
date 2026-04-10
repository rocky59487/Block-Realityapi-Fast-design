package com.blockreality.api.physics.pfsf;

import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * PFSF AMG GPU V-Cycle 錄製器。
 *
 * <p>執行代數多重網格（AMG）預條件子的 GPU V-Cycle：
 * <ol>
 *   <li>Restriction：r_c = R · r_f via {@code amg_scatter_restrict.comp.glsl}</li>
 *   <li>Coarse solve：Jacobi on coarse grid（N_coarse ≤ 512 時直接 shared memory 求解）</li>
 *   <li>Prolongation：phi_f += P · e_c via {@code amg_gather_prolong.comp.glsl}</li>
 * </ol>
 *
 * <p>整合點：{@link PFSFDispatcher#recordSolveSteps} 中，當
 * {@code buf.amgPreconditioner.isReady() == true} 時替代幾何 V-Cycle。
 *
 * <p>參考：Vaněk et al. 1996 (Algebraic multigrid by smoothed aggregation)
 */
public final class PFSFAMGRecorder {

    private static final Logger LOGGER = LoggerFactory.getLogger("PFSF-AMG");

    private PFSFAMGRecorder() {}

    /**
     * 錄製一次完整的 AMG V-Cycle（restrict → coarse solve → prolong）。
     *
     * @param cmdBuf         Vulkan command buffer
     * @param buf            island GPU buffer（需已上傳 AMG 資料）
     * @param descriptorPool descriptor pool
     */
    public static void recordAMGVCycle(VkCommandBuffer cmdBuf,
                                        PFSFIslandBuffer buf,
                                        long descriptorPool) {
        if (buf.amgPreconditioner == null || !buf.amgPreconditioner.isReady()) {
            LOGGER.warn("[PFSF-AMG] AMG not ready for island {}, skipping V-Cycle",
                buf.getIslandId());
            return;
        }

        int nFine   = buf.getN();
        int nCoarse = buf.amgPreconditioner.getNCoarse();

        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            ByteBuffer pc = stack.malloc(8);  // uint N_fine, uint N_coarse
            pc.putInt(nFine).putInt(nCoarse);
            pc.flip();

            // ─── Step 1: Restriction (scatter residual Fine → Coarse) ───
            vkCmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE,
                PFSFPipelineFactory.amgRestrictPipeline);

            long dsRestrict = VulkanComputeContext.allocateDescriptorSet(
                descriptorPool, PFSFPipelineFactory.amgRestrictDSLayout);
            if (dsRestrict == 0) {
                LOGGER.error("[PFSF-AMG] Restrict DS allocation failed for island {}",
                    buf.getIslandId());
                return;
            }

            // binding 0: FineResidual (PCG r buffer = fine residual proxy)
            VulkanComputeContext.bindBufferToDescriptor(dsRestrict, 0,
                buf.getPcgRBuf(), 0, (long) nFine * Float.BYTES);
            // binding 1: Aggregation
            VulkanComputeContext.bindBufferToDescriptor(dsRestrict, 1,
                buf.getAggregationBuf(), 0, (long) nFine * Integer.BYTES);
            // binding 2: PWeights
            VulkanComputeContext.bindBufferToDescriptor(dsRestrict, 2,
                buf.getPWeightBuf(), 0, (long) nFine * Float.BYTES);
            // binding 3: CoarseSrc
            VulkanComputeContext.bindBufferToDescriptor(dsRestrict, 3,
                buf.getCoarseSrcBuf(), 0, (long) nCoarse * Float.BYTES);

            vkCmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE,
                PFSFPipelineFactory.amgRestrictPipelineLayout, 0,
                stack.longs(dsRestrict), null);
            vkCmdPushConstants(cmdBuf, PFSFPipelineFactory.amgRestrictPipelineLayout,
                VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);

            int groups = (nFine + 255) / 256;
            vkCmdDispatch(cmdBuf, groups, 1, 1);
            VulkanComputeContext.computeBarrier(cmdBuf);

            // ─── Step 2: Coarse Jacobi Solve ───
            // 4 Jacobi iterations using existing recordJacobiStep (same package).
            // amg_gather_prolong shared-mem path handles N_coarse ≤ 512 fast solve.
            for (int iter = 0; iter < 4; iter++) {
                PFSFVCycleRecorder.recordJacobiStep(cmdBuf, buf, 1.0f, descriptorPool);
                VulkanComputeContext.computeBarrier(cmdBuf);
            }

            // ─── Step 3: Prolongation (gather correction Coarse → Fine) ───
            vkCmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE,
                PFSFPipelineFactory.amgProlongPipeline);

            long dsProlong = VulkanComputeContext.allocateDescriptorSet(
                descriptorPool, PFSFPipelineFactory.amgProlongDSLayout);
            if (dsProlong == 0) {
                LOGGER.error("[PFSF-AMG] Prolong DS allocation failed for island {}",
                    buf.getIslandId());
                return;
            }

            pc.rewind();
            // binding 0: CoarsePhi (correction from coarse solve)
            VulkanComputeContext.bindBufferToDescriptor(dsProlong, 0,
                buf.getCoarsePhiBuf(), 0, (long) nCoarse * Float.BYTES);
            // binding 1: Aggregation
            VulkanComputeContext.bindBufferToDescriptor(dsProlong, 1,
                buf.getAggregationBuf(), 0, (long) nFine * Integer.BYTES);
            // binding 2: PWeights
            VulkanComputeContext.bindBufferToDescriptor(dsProlong, 2,
                buf.getPWeightBuf(), 0, (long) nFine * Float.BYTES);
            // binding 3: FinePhi (accumulate correction)
            VulkanComputeContext.bindBufferToDescriptor(dsProlong, 3,
                buf.getPhiBuf(), buf.getPhiOffset(), buf.getPhiSize());

            vkCmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE,
                PFSFPipelineFactory.amgProlongPipelineLayout, 0,
                stack.longs(dsProlong), null);
            vkCmdPushConstants(cmdBuf, PFSFPipelineFactory.amgProlongPipelineLayout,
                VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);

            vkCmdDispatch(cmdBuf, groups, 1, 1);
            VulkanComputeContext.computeBarrier(cmdBuf);

            LOGGER.trace("[PFSF-AMG] V-Cycle complete: island={} nFine={} nCoarse={}",
                buf.getIslandId(), nFine, nCoarse);
        }
    }
}
