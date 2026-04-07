package com.blockreality.api.physics.pfsf;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.blockreality.api.physics.pfsf.PFSFConstants.*;
import static com.blockreality.api.physics.pfsf.PFSFPipelineFactory.*;
import static com.blockreality.api.physics.pfsf.PFSFVCycleRecorder.ceilDiv;
import static org.lwjgl.vulkan.VK10.*;

/**
 * PCG (Preconditioned Conjugate Gradient) GPU 求解器。
 *
 * <p>用於低頻殘差收斂：RBGS 消除高頻噪聲後，PCG 快速收斂全域模式。
 * Jacobi 預條件（對角線）= 每體素 1/sum(conductivity)。</p>
 *
 * <p>GPU 向量（額外 3 個 buffer per island）：</p>
 * <pre>
 *   r[N]  — 殘差向量
 *   p[N]  — 搜索方向
 *   Ap[N] — 矩陣-向量乘積
 * </pre>
 *
 * <p>PCG 迭代步驟（每步 4 個 GPU dispatch）：</p>
 * <ol>
 *   <li>Ap = A * p          (matvec: 同 Laplacian stencil，但輸入是 p 而非 phi)</li>
 *   <li>alpha = rTr / pAp   (兩個 dot product reduction)</li>
 *   <li>phi += alpha * p; r -= alpha * Ap   (axpy update)</li>
 *   <li>beta = rTr_new / rTr_old; p = r + beta * p    (direction update)</li>
 * </ol>
 *
 * <p>收斂速度 O(√κ) vs RBGS 的 O(κ)，其中 κ 為條件數。</p>
 */
public final class PFSFPCGRecorder {

    private static final Logger LOGGER = LoggerFactory.getLogger("PFSF-PCG");

    private PFSFPCGRecorder() {}

    // ─── Dot product reduction parameters ───
    // Each workgroup of 256 threads handles 512 elements (2 per thread)
    private static final int REDUCE_WG_SIZE = 256;
    private static final int REDUCE_ELEMENTS_PER_WG = REDUCE_WG_SIZE * 2;

    /**
     * 分配 PCG 所需的額外 GPU buffer（r, p, Ap + 2 個 reduction buffer）。
     *
     * <p>總額外 VRAM = 3*N*4 + 2*ceil(N/512)*4 bytes per island。</p>
     *
     * @param buf island buffer（必須已 allocate）
     */
    static void initPCGBuffers(PFSFIslandBuffer buf) {
        if (buf.isPCGAllocated()) return;
        buf.allocatePCG();
    }

    /**
     * 計算初始殘差 r = source - A*phi，並將 r 複製到 p。
     *
     * <p>使用 pcg_matvec shader 計算 Ap = A*phi，然後
     * pcg_update shader 中的特殊模式計算 r = source - Ap 並複製 p = r。</p>
     *
     * @param cmdBuf Vulkan command buffer
     * @param buf    island buffer（含 PCG buffers）
     * @param descriptorPool descriptor pool handle
     */
    static void computeInitialResidual(VkCommandBuffer cmdBuf, PFSFIslandBuffer buf,
                                        long descriptorPool) {
        int N = buf.getN();

        // Step 1: Ap = A * phi（使用 matvec shader，輸入 = phi，輸出 = Ap）
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, pcgMatvecPipeline);

            long ds = VulkanComputeContext.allocateDescriptorSet(descriptorPool, pcgMatvecDSLayout);
            if (ds == 0) {
                LOGGER.error("[PFSF] Descriptor set allocation failed (pool exhausted) in computeInitialResidual/matvec");
                return;
            }
            // binding 0: input vector (phi)
            VulkanComputeContext.bindBufferToDescriptor(ds, 0, buf.getPhiBuf(), buf.getPhiOffset(), buf.getPhiSize());
            // binding 1: output vector (Ap)
            VulkanComputeContext.bindBufferToDescriptor(ds, 1, buf.getPcgApBuf(), 0, buf.getPhiSize());
            // binding 2: conductivity
            VulkanComputeContext.bindBufferToDescriptor(ds, 2, buf.getConductivityBuf(), buf.getConductivityOffset(), buf.getConductivitySize());
            // binding 3: type
            VulkanComputeContext.bindBufferToDescriptor(ds, 3, buf.getTypeBuf(), buf.getTypeOffset(), buf.getTypeSize());

            vkCmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE,
                    pcgMatvecPipelineLayout, 0, stack.longs(ds), null);

            ByteBuffer pc = stack.malloc(12);
            pc.putInt(buf.getLx()).putInt(buf.getLy()).putInt(buf.getLz());
            pc.flip();
            vkCmdPushConstants(cmdBuf, pcgMatvecPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);

            vkCmdDispatch(cmdBuf, ceilDiv(N, WG_SCAN), 1, 1);
            VulkanComputeContext.computeBarrier(cmdBuf);
        }

        // Step 2: r = source - Ap, p = r (initial residual + direction)
        // Use pcg_update shader in init mode (alpha = -1, compute r = source + (-1)*Ap, then p = r)
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, pcgUpdatePipeline);

            long ds = VulkanComputeContext.allocateDescriptorSet(descriptorPool, pcgUpdateDSLayout);
            if (ds == 0) {
                LOGGER.error("[PFSF] Descriptor set allocation failed (pool exhausted) in computeInitialResidual/update");
                return;
            }
            // binding 0: phi (not modified in init mode)
            VulkanComputeContext.bindBufferToDescriptor(ds, 0, buf.getPhiBuf(), buf.getPhiOffset(), buf.getPhiSize());
            // binding 1: r (output)
            VulkanComputeContext.bindBufferToDescriptor(ds, 1, buf.getPcgRBuf(), 0, buf.getPhiSize());
            // binding 2: p (output, set = r)
            VulkanComputeContext.bindBufferToDescriptor(ds, 2, buf.getPcgPBuf(), 0, buf.getPhiSize());
            // binding 3: Ap (input)
            VulkanComputeContext.bindBufferToDescriptor(ds, 3, buf.getPcgApBuf(), 0, buf.getPhiSize());
            // binding 4: source (input)
            VulkanComputeContext.bindBufferToDescriptor(ds, 4, buf.getSourceBuf(), buf.getSourceOffset(), buf.getPhiSize());
            // binding 5: type (input)
            VulkanComputeContext.bindBufferToDescriptor(ds, 5, buf.getTypeBuf(), buf.getTypeOffset(), buf.getTypeSize());
            // binding 6: partial sums (output)
            int numGroups = ceilDiv(N, REDUCE_ELEMENTS_PER_WG);
            long partialSize = (long) numGroups * Float.BYTES;
            VulkanComputeContext.bindBufferToDescriptor(ds, 6, buf.getPcgPartialBuf(), 0, partialSize);

            vkCmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE,
                    pcgUpdatePipelineLayout, 0, stack.longs(ds), null);

            // push constants: N, alpha=-1.0, isInit=1
            ByteBuffer pc = stack.malloc(16);
            pc.putInt(N);
            pc.putFloat(-1.0f);  // alpha = -1 (for r = source - Ap = source + (-1)*Ap)
            pc.putInt(1);        // isInit = 1 (set p = r, don't update phi)
            pc.putInt(0);        // padding
            pc.flip();
            vkCmdPushConstants(cmdBuf, pcgUpdatePipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);

            vkCmdDispatch(cmdBuf, ceilDiv(N, WG_SCAN), 1, 1);
            VulkanComputeContext.computeBarrier(cmdBuf);
        }

        // Step 3: Reduce partial sums → rTr (stored in reductionBuf[0])
        recordDotProductReduction(cmdBuf, buf, buf.getPcgPartialBuf(), buf.getPcgReductionBuf(),
                ceilDiv(N, REDUCE_ELEMENTS_PER_WG), descriptorPool);
    }

    /**
     * 錄製一步 PCG 迭代（4 個 GPU dispatch + barriers）。
     *
     * <p>前提：r, p 已初始化（由 computeInitialResidual 或上一步設定），
     * rTr 存於 reductionBuf[0]。</p>
     *
     * @param cmdBuf Vulkan command buffer
     * @param buf    island buffer（含 PCG buffers）
     * @param descriptorPool descriptor pool handle
     */
    static void recordPCGStep(VkCommandBuffer cmdBuf, PFSFIslandBuffer buf,
                               long descriptorPool) {
        int N = buf.getN();
        int numGroups = ceilDiv(N, REDUCE_ELEMENTS_PER_WG);

        // ─── Dispatch 1: Ap = A * p (matrix-vector product) ───
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, pcgMatvecPipeline);

            long ds = VulkanComputeContext.allocateDescriptorSet(descriptorPool, pcgMatvecDSLayout);
            if (ds == 0) {
                LOGGER.error("[PFSF] Descriptor set allocation failed (pool exhausted) in recordPCGStep/matvec");
                return;
            }
            VulkanComputeContext.bindBufferToDescriptor(ds, 0, buf.getPcgPBuf(), 0, buf.getPhiSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 1, buf.getPcgApBuf(), 0, buf.getPhiSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 2, buf.getConductivityBuf(), buf.getConductivityOffset(), buf.getConductivitySize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 3, buf.getTypeBuf(), buf.getTypeOffset(), buf.getTypeSize());

            vkCmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE,
                    pcgMatvecPipelineLayout, 0, stack.longs(ds), null);

            ByteBuffer pc = stack.malloc(12);
            pc.putInt(buf.getLx()).putInt(buf.getLy()).putInt(buf.getLz());
            pc.flip();
            vkCmdPushConstants(cmdBuf, pcgMatvecPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);

            vkCmdDispatch(cmdBuf, ceilDiv(N, WG_SCAN), 1, 1);
            VulkanComputeContext.computeBarrier(cmdBuf);
        }

        // ─── Dispatch 2: Compute pAp dot product (p · Ap) ───
        // Use a dedicated dot product shader pass: partial sums of p[i]*Ap[i]
        recordDotProduct(cmdBuf, buf, buf.getPcgPBuf(), buf.getPcgApBuf(),
                buf.getPcgPartialBuf(), N, descriptorPool);
        // Reduce partial sums → pAp stored in reductionBuf[1]
        recordDotProductReductionToSlot(cmdBuf, buf, buf.getPcgPartialBuf(),
                buf.getPcgReductionBuf(), numGroups, 1, descriptorPool);

        // ─── Dispatch 3: phi += alpha*p; r -= alpha*Ap; compute new rTr ───
        // alpha = rTr_old / pAp (read from reductionBuf[0] and [1] via push constant)
        // Since we can't read GPU buffer from CPU between dispatches,
        // we let the shader read alpha from the reduction buffer directly.
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, pcgUpdatePipeline);

            long ds = VulkanComputeContext.allocateDescriptorSet(descriptorPool, pcgUpdateDSLayout);
            if (ds == 0) {
                LOGGER.error("[PFSF] Descriptor set allocation failed (pool exhausted) in recordPCGStep/update");
                return;
            }
            VulkanComputeContext.bindBufferToDescriptor(ds, 0, buf.getPhiBuf(), buf.getPhiOffset(), buf.getPhiSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 1, buf.getPcgRBuf(), 0, buf.getPhiSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 2, buf.getPcgPBuf(), 0, buf.getPhiSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 3, buf.getPcgApBuf(), 0, buf.getPhiSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 4, buf.getSourceBuf(), buf.getSourceOffset(), buf.getPhiSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 5, buf.getTypeBuf(), buf.getTypeOffset(), buf.getTypeSize());
            long partialSize = (long) numGroups * Float.BYTES;
            VulkanComputeContext.bindBufferToDescriptor(ds, 6, buf.getPcgPartialBuf(), 0, partialSize);

            vkCmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE,
                    pcgUpdatePipelineLayout, 0, stack.longs(ds), null);

            // Push constants: N, alpha (placeholder, shader reads from reductionBuf), isInit=0
            // We use a special approach: bind reductionBuf and let shader compute alpha internally
            ByteBuffer pc = stack.malloc(16);
            pc.putInt(N);
            pc.putFloat(0.0f);   // alpha placeholder (shader reads from reductionBuf)
            pc.putInt(0);        // isInit = 0 (normal update mode)
            pc.putInt(0);        // padding
            pc.flip();
            vkCmdPushConstants(cmdBuf, pcgUpdatePipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);

            vkCmdDispatch(cmdBuf, ceilDiv(N, WG_SCAN), 1, 1);
            VulkanComputeContext.computeBarrier(cmdBuf);
        }

        // Reduce new rTr partial sums → reductionBuf[2]
        recordDotProductReductionToSlot(cmdBuf, buf, buf.getPcgPartialBuf(),
                buf.getPcgReductionBuf(), numGroups, 2, descriptorPool);

        // ─── Dispatch 4: p = r + beta*p (direction update) ───
        // beta = rTr_new / rTr_old (shader reads from reductionBuf[2] and [0])
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, pcgDirectionPipeline);

            long ds = VulkanComputeContext.allocateDescriptorSet(descriptorPool, pcgDirectionDSLayout);
            if (ds == 0) {
                LOGGER.error("[PFSF] Descriptor set allocation failed (pool exhausted) in recordPCGStep/direction");
                return;
            }
            VulkanComputeContext.bindBufferToDescriptor(ds, 0, buf.getPcgRBuf(), 0, buf.getPhiSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 1, buf.getPcgPBuf(), 0, buf.getPhiSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 2, buf.getTypeBuf(), buf.getTypeOffset(), buf.getTypeSize());
            // binding 3: reductionBuf (for reading rTr_new and rTr_old)
            VulkanComputeContext.bindBufferToDescriptor(ds, 3, buf.getPcgReductionBuf(), 0,
                    PCG_REDUCTION_SLOTS * Float.BYTES);

            vkCmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE,
                    pcgDirectionPipelineLayout, 0, stack.longs(ds), null);

            ByteBuffer pc = stack.malloc(4);
            pc.putInt(N);
            pc.flip();
            vkCmdPushConstants(cmdBuf, pcgDirectionPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);

            vkCmdDispatch(cmdBuf, ceilDiv(N, WG_SCAN), 1, 1);
            VulkanComputeContext.computeBarrier(cmdBuf);
        }

        // ─── Rotate rTr: copy reductionBuf[2] → reductionBuf[0] for next iteration ───
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkBufferCopy.Buffer region = org.lwjgl.vulkan.VkBufferCopy.calloc(1, stack)
                    .srcOffset(2L * Float.BYTES)
                    .dstOffset(0L)
                    .size(Float.BYTES);
            vkCmdCopyBuffer(cmdBuf, buf.getPcgReductionBuf(), buf.getPcgReductionBuf(), region);
            VulkanComputeContext.computeBarrier(cmdBuf);
        }
    }

    // ─── Dot product helpers (two-pass GPU reduction) ───

    /**
     * 計算兩個向量的內積局部和（Pass 1）。
     * 每個 workgroup 輸出一個 partial sum 到 partialBuf。
     */
    private static void recordDotProduct(VkCommandBuffer cmdBuf, PFSFIslandBuffer buf,
                                          long vecABuf, long vecBBuf, long partialBuf,
                                          int N, long descriptorPool) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, reduceMaxPipeline);

            int numGroups = ceilDiv(N, REDUCE_ELEMENTS_PER_WG);
            long partialSize = (long) numGroups * Float.BYTES;

            long ds = VulkanComputeContext.allocateDescriptorSet(descriptorPool, reduceMaxDSLayout);
            if (ds == 0) {
                LOGGER.error("[PFSF] Descriptor set allocation failed (pool exhausted) in recordDotProduct");
                return;
            }
            VulkanComputeContext.bindBufferToDescriptor(ds, 0, vecABuf, 0, buf.getPhiSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 1, partialBuf, 0, partialSize);

            vkCmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE,
                    reduceMaxPipelineLayout, 0, stack.longs(ds), null);

            ByteBuffer pc = stack.malloc(8);
            pc.putInt(N);
            pc.putInt(0); // isPass2 = 0
            pc.flip();
            vkCmdPushConstants(cmdBuf, reduceMaxPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);

            vkCmdDispatch(cmdBuf, numGroups, 1, 1);
            VulkanComputeContext.computeBarrier(cmdBuf);
        }
    }

    /**
     * 歸約 partial sums → 單一標量到 reductionBuf[0]。
     */
    private static void recordDotProductReduction(VkCommandBuffer cmdBuf, PFSFIslandBuffer buf,
                                                    long partialBuf, long reductionBuf,
                                                    int numPartials, long descriptorPool) {
        recordDotProductReductionToSlot(cmdBuf, buf, partialBuf, reductionBuf, numPartials, 0, descriptorPool);
    }

    /**
     * 歸約 partial sums → 單一標量到 reductionBuf[slot]。
     * 使用與 PFSFFailureRecorder.recordPhiMaxReduction() 相同的 2-pass pattern。
     */
    private static void recordDotProductReductionToSlot(VkCommandBuffer cmdBuf, PFSFIslandBuffer buf,
                                                         long partialBuf, long reductionBuf,
                                                         int numPartials, int slot,
                                                         long descriptorPool) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Use reduceMax pipeline in sum mode (reuse the same pipeline, the shader
            // uses subgroup operations which work for both max and sum)
            vkCmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, reduceMaxPipeline);

            long ds = VulkanComputeContext.allocateDescriptorSet(descriptorPool, reduceMaxDSLayout);
            if (ds == 0) {
                LOGGER.error("[PFSF] Descriptor set allocation failed (pool exhausted) in recordDotProductReductionToSlot");
                return;
            }
            long partialSize = (long) numPartials * Float.BYTES;
            VulkanComputeContext.bindBufferToDescriptor(ds, 0, partialBuf, 0, partialSize);
            VulkanComputeContext.bindBufferToDescriptor(ds, 1, reductionBuf, 0,
                    (long) PCG_REDUCTION_SLOTS * Float.BYTES);

            vkCmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE,
                    reduceMaxPipelineLayout, 0, stack.longs(ds), null);

            ByteBuffer pc = stack.malloc(8);
            pc.putInt(numPartials);
            pc.putInt(1); // isPass2 = 1
            pc.flip();
            vkCmdPushConstants(cmdBuf, reduceMaxPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);

            int numGroups2 = Math.max(ceilDiv(numPartials, REDUCE_ELEMENTS_PER_WG), 1);
            vkCmdDispatch(cmdBuf, numGroups2, 1, 1);
            VulkanComputeContext.computeBarrier(cmdBuf);
        }
    }

    /** PCG reduction buffer 槽位數（rTr_old, pAp, rTr_new） */
    static final int PCG_REDUCTION_SLOTS = 4;
}
