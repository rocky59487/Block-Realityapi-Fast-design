package com.blockreality.api.physics.pfsf;

import com.blockreality.api.config.BRConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static com.blockreality.api.physics.pfsf.PFSFConstants.*;

/**
 * PFSF GPU Dispatch 錄製器 — 從 PFSFEngine 提取的 GPU 命令錄製邏輯。
 *
 * <p>P2 重構：PFSFEngine 拆分為三層之一。</p>
 *
 * <p>職責：
 * <ul>
 *   <li>recordSolveSteps() — Hybrid RBGS+PCG / 純 RBGS + W-Cycle 迭代錄製</li>
 *   <li>recordPhaseFieldEvolve() — Ambati 2015 相場演化</li>
 *   <li>recordFailureDetection() — 失效掃描 + compact readback + phi reduce</li>
 *   <li>handleSparseOrFullUpload() — 稀疏/全量上傳決策</li>
 * </ul>
 *
 * <p>Hybrid RBGS+PCG 策略（pfsfUsePCG = true 時啟用）：</p>
 * <ul>
 *   <li>Phase 1: 前 N/2 步使用 RBGS（高頻噪聲平滑）</li>
 *   <li>Phase 2: 後 N/2 步使用 PCG（低頻全域模式收斂，O(√κ)）</li>
 *   <li>總迭代數減少 ~50%</li>
 * </ul>
 */
public final class PFSFDispatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger("PFSF-Dispatch");

    /**
     * 處理稀疏更新或全量重建。
     *
     * @return true 若有執行任何上傳
     */
    public boolean handleDataUpload(PFSFAsyncCompute.ComputeFrame frame,
                                     PFSFIslandBuffer buf,
                                     PFSFSparseUpdate sparse,
                                     PFSFEngine.UploadContext ctx,
                                     long descriptorPool) {
        if (!sparse.hasPendingUpdates()) return false;

        List<PFSFSparseUpdate.VoxelUpdate> updates = sparse.drainUpdates();
        if (updates == null) {
            // 全量重建
            PFSFDataBuilder.updateSourceAndConductivity(buf, ctx.island, ctx.level,
                    ctx.materialLookup, ctx.anchorLookup, ctx.fillRatioLookup,
                    ctx.curingLookup, ctx.windVec);
            buf.markClean();
            return true;
        } else if (!updates.isEmpty()) {
            // 稀疏更新
            int count = sparse.packUpdates(updates);
            PFSFFailureRecorder.recordSparseScatter(frame.cmdBuf, buf, sparse, count, descriptorPool);
            buf.markClean();
            return true;
        }
        return false;
    }

    /**
     * 錄製求解步驟 — Hybrid RBGS+PCG 或純 RBGS+W-Cycle。
     *
     * <p>當 {@link BRConfig#isPFSFPCGEnabled()} 為 true 且 PCG buffer 已分配時，
     * 使用 hybrid 策略：前半步 RBGS（高頻平滑），後半步 PCG（低頻收斂）。
     * 否則退回純 RBGS + W-Cycle。</p>
     *
     * @return 實際執行的步數
     */
    public int recordSolveSteps(org.lwjgl.vulkan.VkCommandBuffer cmdBuf,
                                 PFSFIslandBuffer buf,
                                 int steps,
                                 long descriptorPool) {
        boolean usePCG = BRConfig.isPFSFPCGEnabled() && buf.isPCGAllocated() && steps >= 2;

        if (usePCG) {
            // ─── Hybrid RBGS+PCG strategy ───
            int rbgsSteps = steps / 2;
            int pcgSteps  = steps - rbgsSteps;

            // Phase 1: RBGS for high-frequency smoothing
            for (int k = 0; k < rbgsSteps; k++) {
                if (k > 0 && k % MG_INTERVAL == 0 && buf.getLmax() > 4) {
                    PFSFVCycleRecorder.recordVCycle(cmdBuf, buf, descriptorPool);
                } else {
                    PFSFVCycleRecorder.recordRBGSStep(cmdBuf, buf, descriptorPool);
                    buf.chebyshevIter++;
                }
            }

            // Barrier: RBGS writes to phi buffer; PCG reads it for initial residual.
            // Without this barrier, PCG may read stale RBGS output (data race).
            VulkanComputeContext.computeBarrier(cmdBuf);

            // Phase 2: PCG for low-frequency convergence
            if (pcgSteps > 0) {
                PFSFPCGRecorder.computeInitialResidual(cmdBuf, buf, descriptorPool);
                for (int k = 0; k < pcgSteps; k++) {
                    PFSFPCGRecorder.recordPCGStep(cmdBuf, buf, descriptorPool);
                }
            }
        } else {
            // ─── Pure RBGS + W-Cycle (original path) ───
            for (int k = 0; k < steps; k++) {
                if (k > 0 && k % MG_INTERVAL == 0 && buf.getLmax() > 4) {
                    PFSFVCycleRecorder.recordVCycle(cmdBuf, buf, descriptorPool);
                } else {
                    PFSFVCycleRecorder.recordRBGSStep(cmdBuf, buf, descriptorPool);
                    buf.chebyshevIter++;
                }
            }
        }
        return steps;
    }

    /**
     * 錄製 Phase-Field Evolution（Ambati 2015）。
     */
    public void recordPhaseFieldEvolve(org.lwjgl.vulkan.VkCommandBuffer cmdBuf,
                                        PFSFIslandBuffer buf,
                                        long descriptorPool) {
        if (buf.getDFieldBuf() == 0) return;

        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VK10.vkCmdBindPipeline(
                    cmdBuf, org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    PFSFPipelineFactory.phaseFieldPipeline);

            long ds = VulkanComputeContext.allocateDescriptorSet(descriptorPool, PFSFPipelineFactory.phaseFieldDSLayout);
            if (ds == 0) {
                LOGGER.error("[PFSF] Descriptor set allocation failed (pool exhausted) in recordPhaseFieldEvolve");
                return;
            }
            VulkanComputeContext.bindBufferToDescriptor(ds, 0, buf.getPhiBuf(),          buf.getPhiOffset(), buf.getPhiSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 1, buf.getHFieldBuf(),       0, buf.getHFieldSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 2, buf.getDFieldBuf(),       0, buf.getDFieldSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 3, buf.getConductivityBuf(), buf.getConductivityOffset(), buf.getConductivitySize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 4, buf.getTypeBuf(),         buf.getTypeOffset(), buf.getTypeSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 5, buf.getFailFlagsBuf(),    buf.getFailFlagsOffset(), buf.getTypeSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 6, buf.getHydrationBuf(),    0, buf.getHydrationSize());

            org.lwjgl.vulkan.VK10.vkCmdBindDescriptorSets(
                    cmdBuf, org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    PFSFPipelineFactory.phaseFieldPipelineLayout, 0, stack.longs(ds), null);

            java.nio.ByteBuffer pc = stack.malloc(24);
            pc.putInt(buf.getLx()).putInt(buf.getLy()).putInt(buf.getLz());
            pc.putFloat(PHASE_FIELD_L0)
              .putFloat(G_C_CONCRETE)
              .putFloat(PHASE_FIELD_RELAX);
            pc.flip();

            org.lwjgl.vulkan.VK10.vkCmdPushConstants(
                    cmdBuf, PFSFPipelineFactory.phaseFieldPipelineLayout,
                    org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);

            org.lwjgl.vulkan.VK10.vkCmdDispatch(cmdBuf, PFSFVCycleRecorder.ceilDiv(buf.getN(), WG_SCAN), 1, 1);
            VulkanComputeContext.computeBarrier(cmdBuf);
        }
    }

    /**
     * 錄製失效偵測 + compact readback + phi max reduction。
     * Bug #3 fix: 每次 scan 前清除 macroBlockResidual，防止殘差只增不減。
     */
    public void recordFailureDetection(PFSFAsyncCompute.ComputeFrame frame,
                                        PFSFIslandBuffer buf,
                                        long descriptorPool) {
        buf.clearMacroBlockResiduals();
        PFSFFailureRecorder.recordFailureScan(frame.cmdBuf, buf, descriptorPool);
        PFSFFailureRecorder.recordFailureCompact(frame.cmdBuf, buf, frame, descriptorPool);
        PFSFFailureRecorder.recordPhiMaxReduction(frame.cmdBuf, buf, frame);
    }
}
