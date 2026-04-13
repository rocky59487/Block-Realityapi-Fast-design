package com.blockreality.api.physics.pfsf;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

import static com.blockreality.api.physics.pfsf.PFSFConstants.*;
import static com.blockreality.api.physics.pfsf.PFSFPipelineFactory.*;

// v2.1: RBGS 8-color in-place smoother（細網格主求解器）
// 粗網格 L1/L2 平滑仍使用 Jacobi（pipeline 複用）
import static org.lwjgl.vulkan.VK10.*;

/**
 * PFSF GPU Dispatch 錄製器 — Jacobi 迭代 + W-Cycle 多重網格。
 *
 * <p>v2 升級：從 V-Cycle 升級為 W-Cycle（遞迴兩層粗網格），
 * 大尺度結構（>100K 體素）收斂步數減少 30-40%。</p>
 *
 * <p>W-Cycle 結構：</p>
 * <pre>
 * Fine:   smooth → restrict ──────────────────── prolong → smooth
 * L1:                    smooth → restrict ── prolong → smooth
 *                                    ↓              ↑
 *                                smooth → restrict ── prolong → smooth
 * L2:                              direct solve (4 Jacobi)
 * </pre>
 */
public final class PFSFVCycleRecorder {

    private static final Logger LOGGER = LoggerFactory.getLogger("PFSF-VCycle");

    private PFSFVCycleRecorder() {}

    // ─── v2.1: RBGS 8-color Step（細網格主求解器）───
    //
    // 每步 RBGS = 8 個 Dispatch pass，每 pass 只更新 color == colorPass 的體素。
    // 8 色著色保證 26-connectivity 鄰域內無 Data Race。
    // In-place 更新：無需 swapPhi()，鄰居讀取同一個 phi[] buffer 的最新值。

    static void recordRBGSStep(VkCommandBuffer cmdBuf, PFSFIslandBuffer buf, long descriptorPool) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            float damping = buf.dampingActive ? DAMPING_FACTOR : 0.0f;
            int N = buf.getN();

            for (int colorPass = 0; colorPass < RBGS_COLORS; colorPass++) {
                vkCmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, rbgsPipeline);

                long ds = VulkanComputeContext.allocateDescriptorSet(descriptorPool, rbgsDSLayout);
                if (ds == 0) {
                    LOGGER.error("[PFSF] Descriptor set allocation failed (pool exhausted) in recordRBGSStep");
                    return;
                }
                VulkanComputeContext.bindBufferToDescriptor(ds, 0, buf.getPhiBuf(),          buf.getPhiOffset(), buf.getPhiSize());
                VulkanComputeContext.bindBufferToDescriptor(ds, 1, buf.getSourceBuf(),       buf.getSourceOffset(), buf.getPhiSize());
                VulkanComputeContext.bindBufferToDescriptor(ds, 2, buf.getConductivityBuf(), buf.getConductivityOffset(), buf.getConductivitySize());
                VulkanComputeContext.bindBufferToDescriptor(ds, 3, buf.getTypeBuf(),         buf.getTypeOffset(), buf.getTypeSize());
                VulkanComputeContext.bindBufferToDescriptor(ds, 4, buf.getHFieldBuf(),       0, buf.getHFieldSize());
                VulkanComputeContext.bindBufferToDescriptor(ds, 5, buf.getMacroBlockResidualBuf(), buf.getMacroResidualOffset(), buf.getMacroBlockResidualSize());

                vkCmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE,
                        rbgsPipelineLayout, 0, stack.longs(ds), null);

                // push constants: Lx, Ly, Lz, colorPass (4×uint) + damping (float) = 20 bytes
                ByteBuffer pc = stack.malloc(20);
                pc.putInt(buf.getLx()).putInt(buf.getLy()).putInt(buf.getLz());
                pc.putInt(colorPass).putFloat(damping);
                pc.flip();
                vkCmdPushConstants(cmdBuf, rbgsPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);

                vkCmdDispatch(cmdBuf, ceilDiv(N, WG_RBGS), 1, 1);
                VulkanComputeContext.computeBarrier(cmdBuf);
            }
        }
    }

    // ─── Single Jacobi Step（保留：用於 W-Cycle 粗網格 L1/L2 平滑）───

    /**
     * v2 Phase A: Red-Black Gauss-Seidel — 雙 pass in-place 更新。
     * 取代 Jacobi，收斂速度 2×，消除 phi_prev 雙重緩衝。
     */
    static void recordRBGSStep(VkCommandBuffer cmdBuf, PFSFIslandBuffer buf,
                                float omega, long descriptorPool) {
        // Red pass (color = 0)
        dispatchRBGSPass(cmdBuf, buf, omega, descriptorPool,
                buf.getPhiBuf(), buf.getPhiSize(),
                buf.getSourceBuf(), buf.getConductivityBuf(), buf.getConductivitySize(),
                buf.getTypeBuf(), buf.getTypeSize(),
                buf.getLx(), buf.getLy(), buf.getLz(), 0);
        VulkanComputeContext.computeBarrier(cmdBuf);

        // Black pass (color = 1)
        dispatchRBGSPass(cmdBuf, buf, omega, descriptorPool,
                buf.getPhiBuf(), buf.getPhiSize(),
                buf.getSourceBuf(), buf.getConductivityBuf(), buf.getConductivitySize(),
                buf.getTypeBuf(), buf.getTypeSize(),
                buf.getLx(), buf.getLy(), buf.getLz(), 1);
        VulkanComputeContext.computeBarrier(cmdBuf);
    }

    /** 向後相容別名 */
    static void recordJacobiStep(VkCommandBuffer cmdBuf, PFSFIslandBuffer buf,
                                  float omega, long descriptorPool) {
        recordRBGSStep(cmdBuf, buf, omega, descriptorPool);
    }

    private static void dispatchRBGSPass(VkCommandBuffer cmdBuf, PFSFIslandBuffer buf,
                                          float omega, long descriptorPool,
                                          long phiBuf, long phiSize,
                                          long sourceBuf, long condBuf, long condSize,
                                          long typeBuf, long typeSize,
                                          int Lx, int Ly, int Lz, int redBlackPass) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, jacobiPipeline);

            // 4 bindings (RBGS: no PhiPrev)
            long ds = VulkanComputeContext.allocateDescriptorSet(descriptorPool, jacobiDSLayout);
            if (ds == 0) {
                LOGGER.error("[PFSF] Descriptor set allocation failed (pool exhausted) in dispatchRBGSPass");
                return;
            }
            VulkanComputeContext.bindBufferToDescriptor(ds, 0, buf.getPhiBuf(),          buf.getPhiOffset(), buf.getPhiSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 1, buf.getPhiPrevBuf(),      buf.getPhiPrevOffset(), buf.getPhiSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 2, buf.getSourceBuf(),       buf.getSourceOffset(), buf.getPhiSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 3, buf.getConductivityBuf(), buf.getConductivityOffset(), buf.getConductivitySize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 4, buf.getTypeBuf(),         buf.getTypeOffset(), buf.getTypeSize());
            // binding 5: hField（v2.1 Jacobi 寫入 Amor 歷史場，供粗網格的 phase-field 感知）
            VulkanComputeContext.bindBufferToDescriptor(ds, 5, buf.getHFieldBuf(),       0, buf.getHFieldSize());

            vkCmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE,
                    jacobiPipelineLayout, 0, stack.longs(ds), null);

            // 32 bytes push constants (28 + redBlackPass uint)
            float damping = buf.dampingActive ? DAMPING_FACTOR : 0.0f;
            ByteBuffer pc = stack.malloc(32);
            pc.putInt(Lx).putInt(Ly).putInt(Lz);
            pc.putFloat(omega).putFloat(buf.rhoSpecOverride);
            pc.putInt(buf.chebyshevIter).putFloat(damping);
            pc.putInt(redBlackPass);
            pc.flip();

            vkCmdPushConstants(cmdBuf, jacobiPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);
            vkCmdDispatch(cmdBuf, ceilDiv(Lx, WG_X), ceilDiv(Ly, WG_Y), ceilDiv(Lz, WG_Z));
        }
    }

    // ─── W-Cycle (遞迴多重網格) ───

    /**
     * W-Cycle 多重網格：遞迴兩次造訪粗網格，比 V-Cycle 更有效消除低頻誤差。
     *
     * <p>當 L2 粗網格可用（N_L2 > 0）時使用完整 W-Cycle；
     * 否則退回 V-Cycle 行為（粗網格直接求解）。</p>
     */
    static void recordVCycle(VkCommandBuffer cmdBuf, PFSFIslandBuffer buf, long descriptorPool) {
        if (!buf.isAllocated()) return;
        buf.allocateMultigrid();

        float omega = PFSFScheduler.getTickOmega(buf);

        // 1. Pre-smooth: 1 RBGS step on fine grid（= 8 color passes，等效 Jacobi 2 倍收斂）
        // v2.1: 細網格改用 RBGS 就地更新，不需 swapPhi()
        recordRBGSStep(cmdBuf, buf, descriptorPool);

        // 2. Restrict: fine → L1
        recordRestrict(cmdBuf, buf, descriptorPool);

        // 3. W-Cycle on L1: 遞迴兩次造訪 L1→L2
        if (buf.getN_L2() > 0) {
            // W-Cycle 第一腿
            recordWCycleL1(cmdBuf, buf, omega, descriptorPool);
            // W-Cycle 第二腿
            recordWCycleL1(cmdBuf, buf, omega, descriptorPool);
        } else {
            // L2 不可用 → 4 步 RBGS on L1（no swap needed）
            for (int i = 0; i < 4; i++) {
                recordCoarseRBGS(cmdBuf, buf, omega, descriptorPool);
            }
        }

        // 4. Prolong: L1 → fine
        recordProlong(cmdBuf, buf, descriptorPool);

        // 5. Post-smooth: 1 RBGS step on fine grid（v2.1）
        recordRBGSStep(cmdBuf, buf, descriptorPool);
    }

    /**
     * W-Cycle 的 L1 層遞迴：smooth L1 → restrict to L2 → solve L2 → prolong to L1 → smooth L1。
     */
    private static void recordWCycleL1(VkCommandBuffer cmdBuf, PFSFIslandBuffer buf,
                                        float omega, long descriptorPool) {
        // Pre-smooth on L1: 2 RBGS (in-place, no swap)
        recordCoarseRBGS(cmdBuf, buf, omega, descriptorPool);
        recordCoarseRBGS(cmdBuf, buf, omega, descriptorPool);

        // Restrict: L1 → L2
        recordRestrictL1toL2(cmdBuf, buf, descriptorPool);

        // Direct solve on L2: 4 RBGS (in-place, no swap)
        for (int i = 0; i < 4; i++) {
            recordCoarseRBGSL2(cmdBuf, buf, omega, descriptorPool);
        }

        // Prolong: L2 → L1
        recordProlongL2toL1(cmdBuf, buf, descriptorPool);

        // Post-smooth on L1: 2 RBGS
        recordCoarseRBGS(cmdBuf, buf, omega, descriptorPool);
        recordCoarseRBGS(cmdBuf, buf, omega, descriptorPool);
    }

    // ─── Restrict: fine → coarse ───

    private static void recordRestrict(VkCommandBuffer cmdBuf, PFSFIslandBuffer buf, long descriptorPool) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, restrictPipeline);

            long ds = VulkanComputeContext.allocateDescriptorSet(descriptorPool, restrictDSLayout);
            if (ds == 0) {
                LOGGER.error("[PFSF] Descriptor set allocation failed (pool exhausted) in recordRestrict");
                return;
            }
            VulkanComputeContext.bindBufferToDescriptor(ds, 0, buf.getPhiBuf(), buf.getPhiOffset(), buf.getPhiSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 1, buf.getSourceBuf(), buf.getSourceOffset(), buf.getPhiSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 2, buf.getConductivityBuf(), buf.getConductivityOffset(), buf.getConductivitySize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 3, buf.getTypeBuf(), buf.getTypeOffset(), buf.getTypeSize());

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
            if (ds == 0) {
                LOGGER.error("[PFSF] Descriptor set allocation failed (pool exhausted) in recordProlong");
                return;
            }
            VulkanComputeContext.bindBufferToDescriptor(ds, 0, buf.getPhiBuf(), buf.getPhiOffset(), buf.getPhiSize());

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

    // ─── Coarse RBGS (L1) ───

    private static void recordCoarseRBGS(VkCommandBuffer cmdBuf, PFSFIslandBuffer buf,
                                          float omega, long descriptorPool) {
        int nL1 = buf.getN_L1();
        long phiSizeL1 = (long) nL1 * Float.BYTES;
        long condSizeL1 = (long) nL1 * 6 * Float.BYTES;
        // Red pass
        dispatchRBGSPass(cmdBuf, buf, omega, descriptorPool,
                buf.getPhiL1Buf(), phiSizeL1,
                buf.getSourceL1Buf(), buf.getConductivityL1Buf(), condSizeL1,
                buf.getTypeL1Buf(), nL1,
                buf.getLxL1(), buf.getLyL1(), buf.getLzL1(), 0);
        VulkanComputeContext.computeBarrier(cmdBuf);
        // Black pass
        dispatchRBGSPass(cmdBuf, buf, omega, descriptorPool,
                buf.getPhiL1Buf(), phiSizeL1,
                buf.getSourceL1Buf(), buf.getConductivityL1Buf(), condSizeL1,
                buf.getTypeL1Buf(), nL1,
                buf.getLxL1(), buf.getLyL1(), buf.getLzL1(), 1);
        VulkanComputeContext.computeBarrier(cmdBuf);
    }

    // ─── L2 Restrict: L1 → L2 ───

    private static void recordRestrictL1toL2(VkCommandBuffer cmdBuf, PFSFIslandBuffer buf, long descriptorPool) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, restrictPipeline);

            long ds = VulkanComputeContext.allocateDescriptorSet(descriptorPool, restrictDSLayout);
            if (ds == 0) {
                LOGGER.error("[PFSF] Descriptor set allocation failed (pool exhausted) in recordRestrictL1toL2");
                return;
            }
            long phiSizeL1 = (long) buf.getN_L1() * Float.BYTES;
            long condSizeL1 = (long) buf.getN_L1() * 6 * Float.BYTES;
            VulkanComputeContext.bindBufferToDescriptor(ds, 0, buf.getPhiL1Buf(), 0, phiSizeL1);
            VulkanComputeContext.bindBufferToDescriptor(ds, 1, buf.getSourceL1Buf(), 0, phiSizeL1);
            VulkanComputeContext.bindBufferToDescriptor(ds, 2, buf.getConductivityL1Buf(), 0, condSizeL1);
            VulkanComputeContext.bindBufferToDescriptor(ds, 3, buf.getTypeL1Buf(), 0, buf.getN_L1());

            long phiSizeL2 = (long) buf.getN_L2() * Float.BYTES;
            VulkanComputeContext.bindBufferToDescriptor(ds, 4, buf.getPhiL2Buf(), 0, phiSizeL2);
            VulkanComputeContext.bindBufferToDescriptor(ds, 5, buf.getSourceL2Buf(), 0, phiSizeL2);

            vkCmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE,
                    restrictPipelineLayout, 0, stack.longs(ds), null);

            ByteBuffer pc = stack.malloc(24);
            pc.putInt(buf.getLxL1()).putInt(buf.getLyL1()).putInt(buf.getLzL1());
            pc.putInt(buf.getLxL2()).putInt(buf.getLyL2()).putInt(buf.getLzL2());
            pc.flip();
            vkCmdPushConstants(cmdBuf, restrictPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);

            vkCmdDispatch(cmdBuf, ceilDiv(buf.getN_L2(), WG_SCAN), 1, 1);
            VulkanComputeContext.computeBarrier(cmdBuf);
        }
    }

    // ─── L2 Prolong: L2 → L1 ───

    private static void recordProlongL2toL1(VkCommandBuffer cmdBuf, PFSFIslandBuffer buf, long descriptorPool) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, prolongPipeline);

            long ds = VulkanComputeContext.allocateDescriptorSet(descriptorPool, prolongDSLayout);
            if (ds == 0) {
                LOGGER.error("[PFSF] Descriptor set allocation failed (pool exhausted) in recordProlongL2toL1");
                return;
            }
            long phiSizeL1 = (long) buf.getN_L1() * Float.BYTES;
            long phiSizeL2 = (long) buf.getN_L2() * Float.BYTES;
            VulkanComputeContext.bindBufferToDescriptor(ds, 0, buf.getPhiL1Buf(), 0, phiSizeL1);
            VulkanComputeContext.bindBufferToDescriptor(ds, 1, buf.getPhiL2Buf(), 0, phiSizeL2);

            vkCmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE,
                    prolongPipelineLayout, 0, stack.longs(ds), null);

            ByteBuffer pc = stack.malloc(24);
            pc.putInt(buf.getLxL1()).putInt(buf.getLyL1()).putInt(buf.getLzL1());
            pc.putInt(buf.getLxL2()).putInt(buf.getLyL2()).putInt(buf.getLzL2());
            pc.flip();
            vkCmdPushConstants(cmdBuf, prolongPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);

            vkCmdDispatch(cmdBuf, ceilDiv(buf.getLxL1(), WG_X), ceilDiv(buf.getLyL1(), WG_Y), ceilDiv(buf.getLzL1(), WG_Z));
            VulkanComputeContext.computeBarrier(cmdBuf);
        }
    }

    // ─── L2 Coarse RBGS ───

    private static void recordCoarseRBGSL2(VkCommandBuffer cmdBuf, PFSFIslandBuffer buf,
                                            float omega, long descriptorPool) {
        int nL2 = buf.getN_L2();
        long phiSizeL2 = (long) nL2 * Float.BYTES;
        long condSizeL2 = (long) nL2 * 6 * Float.BYTES;
        // Red pass
        dispatchRBGSPass(cmdBuf, buf, omega, descriptorPool,
                buf.getPhiL2Buf(), phiSizeL2,
                buf.getSourceL2Buf(), buf.getConductivityL2Buf(), condSizeL2,
                buf.getTypeL2Buf(), nL2,
                buf.getLxL2(), buf.getLyL2(), buf.getLzL2(), 0);
        VulkanComputeContext.computeBarrier(cmdBuf);
        // Black pass
        dispatchRBGSPass(cmdBuf, buf, omega, descriptorPool,
                buf.getPhiL2Buf(), phiSizeL2,
                buf.getSourceL2Buf(), buf.getConductivityL2Buf(), condSizeL2,
                buf.getTypeL2Buf(), nL2,
                buf.getLxL2(), buf.getLyL2(), buf.getLzL2(), 1);
        VulkanComputeContext.computeBarrier(cmdBuf);
    }

    public static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
