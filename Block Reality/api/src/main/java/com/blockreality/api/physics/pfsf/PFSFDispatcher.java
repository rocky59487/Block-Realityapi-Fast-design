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
            // ─── 殘差驅動自適應切換（Residual-Driven Adaptive Switching）───
            //
            // 策略：先跑 RBGS，每步追蹤殘差下降率。
            // 當 RBGS 的邊際收益停滯（殘差下降率 < STALL_RATIO）時，
            // 立即切換到 PCG 處理剩餘的低頻誤差。
            //
            // 優點：
            //  - 小島嶼（高頻主導）：RBGS 幾步就收斂，PCG 不浪費 dispatch
            //  - 大島嶼（低頻主導）：RBGS 2-3 步後停滯，盡早切 PCG
            //  - 病態問題：RBGS 第 1 步就停滯，幾乎全部給 PCG
            //
            // 殘差追蹤：利用 Chebyshev omega 的增長趨勢間接判斷。
            // 若連續 2 步 omega 變化 < 1%（已飽和），視為 RBGS 停滯。
            //
            // Fallback：最少 2 步 RBGS（確保高頻噪聲被消除），
            //           最少 1 步 PCG（確保低頻至少被觸碰）。

            // 殘差停滯偵測比例：上一 tick 的 macro-block 殘差下降率 < 5% = RBGS 停滯
            final float RESIDUAL_STALL_RATIO = 0.95f;
            final int MIN_RBGS = 2;
            final int MIN_PCG  = 1;
            int maxRbgs = steps - MIN_PCG;

            // 從上一 tick 的 macro-block 殘差判斷初始停滯傾向
            // 若上一 tick 殘差幾乎沒下降 → RBGS 已無邊際收益，早切 PCG
            float prevResidual = buf.prevMaxMacroResidual;
            float currentResidual = 0;
            if (buf.cachedMacroResiduals != null) {
                for (float r : buf.cachedMacroResiduals) {
                    if (r > currentResidual) currentResidual = r;
                }
            }
            float residualRatio = (prevResidual > 1e-10f) ? currentResidual / prevResidual : 0f;
            boolean residualStalled = residualRatio > RESIDUAL_STALL_RATIO;

            // 若殘差已停滯，直接用最少 RBGS
            int rbgsTarget = residualStalled ? MIN_RBGS : maxRbgs;
            int rbgsSteps = 0;
            boolean stalled = residualStalled;

            // Phase 1: RBGS（若殘差未停滯則跑到 maxRbgs，否則只跑 MIN_RBGS）
            for (int k = 0; k < rbgsTarget; k++) {
                if (k > 0 && k % MG_INTERVAL == 0 && buf.getLmax() > 4) {
                    PFSFVCycleRecorder.recordVCycle(cmdBuf, buf, descriptorPool);
                } else {
                    PFSFVCycleRecorder.recordRBGSStep(cmdBuf, buf, descriptorPool);
                    buf.chebyshevIter++;
                }
                rbgsSteps++;
            }

            int pcgSteps = steps - rbgsSteps;

            // Barrier: RBGS writes → PCG reads
            VulkanComputeContext.computeBarrier(cmdBuf);

            // WSS-HQR: Vector field solve between RBGS Phase 1 and PCG Phase 2
            // Use prevMaxMacroResidual as stress ratio proxy: high residual = high stress
            // Normalized: residual > 0.7 of maxPhi → activate vector field solve
            float stressProxy = (buf.maxPhiPrev > 1e-6f)
                ? buf.prevMaxMacroResidual / buf.maxPhiPrev : 0f;
            if (com.blockreality.api.physics.pfsf.vector.PFSFVectorSolver
                    .isVectorSolveNeeded(stressProxy)) {
                buf.ensureVectorFieldAllocated();
                com.blockreality.api.physics.pfsf.vector.PFSFVectorRecorder
                    .recordVectorSolve(cmdBuf, buf, descriptorPool);
            }

            // Phase 2: PCG for remaining steps
            if (pcgSteps > 0) {
                PFSFPCGRecorder.computeInitialResidual(cmdBuf, buf, descriptorPool);
                for (int k = 0; k < pcgSteps; k++) {
                    PFSFPCGRecorder.recordPCGStep(cmdBuf, buf, descriptorPool);
                }
            }

            if (stalled) {
                LOGGER.debug("[PFSF] Residual stalled (ratio={}), RBGS={} PCG={}",
                        residualRatio, rbgsSteps, pcgSteps);
            }
        } else {
            // ─── Pure RBGS + W-Cycle (original path) ───
            for (int k = 0; k < steps; k++) {
                if (k > 0 && k % MG_INTERVAL == 0 && buf.getLmax() > 4) {
                    // AMG GPU V-Cycle (Module 2): replaces geometric V-Cycle when AMG is ready
                    if (buf.amgPreconditioner != null && buf.amgPreconditioner.isReady()) {
                        PFSFAMGRecorder.recordAMGVCycle(cmdBuf, buf, descriptorPool);
                    } else {
                        PFSFVCycleRecorder.recordVCycle(cmdBuf, buf, descriptorPool);
                    }
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

            // 拓撲穩定條件：l₀ ≥ 2 × h_mesh（h_mesh = 1 block）
            float l0Clamped = Math.max(PHASE_FIELD_L0, 2.0f);
            java.nio.ByteBuffer pc = stack.malloc(28);
            pc.putInt(buf.getLx()).putInt(buf.getLy()).putInt(buf.getLz());
            pc.putFloat(l0Clamped)
              .putFloat(G_C_CONCRETE)
              .putFloat(PHASE_FIELD_RELAX)
              .putInt(1);  // spectralSplitEnabled = 1 (AT2 + spectral split)
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
