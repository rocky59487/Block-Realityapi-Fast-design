package com.blockreality.api.physics.pfsf;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.blockreality.api.physics.pfsf.PFSFPipelineFactory.*;
import static com.blockreality.api.physics.pfsf.PFSFVCycleRecorder.ceilDiv;
import static org.lwjgl.vulkan.VK10.*;

/**
 * PFSF Phase-Field 漸進式裂縫 — GPU Dispatch 錄製器。
 *
 * <p>v2 Phase C：Miehe 2010 Operator Split。
 * 在 phi solve 之後、failure scan 之前執行損傷場演化。</p>
 *
 * <p>每 tick 的 operator split 序列：</p>
 * <ol>
 *   <li>Phi solve（RBGS，用退化 σ×(1-d)²）</li>
 *   <li><b>Damage evolution（本類別）</b></li>
 *   <li>Failure scan（d > 0.95 觸發崩塌）</li>
 * </ol>
 */
public final class PFSFPhaseFieldRecorder {

    private static final Logger LOGGER = LoggerFactory.getLogger("PFSF-PhaseField");

    private PFSFPhaseFieldRecorder() {}

    /** 正則化長度（blocks）。影響裂縫寬度，建議 1.5-2.0 */
    public static final float REGULARIZATION_LENGTH = 1.5f;

    /** 損傷閾值：d > 此值視為完全斷裂 */
    public static final float DAMAGE_CRITICAL_THRESHOLD = 0.95f;

    /**
     * 錄製一步 phase-field 損傷演化 dispatch。
     */
    static void recordPhaseFieldStep(VkCommandBuffer cmdBuf, PFSFIslandBuffer buf,
                                      long descriptorPool) {
        if (buf.getDamageBuf() == 0 || buf.getHistoryBuf() == 0) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, phaseFieldPipeline);

            long ds = VulkanComputeContext.allocateDescriptorSet(descriptorPool, phaseFieldDSLayout);
            if (ds == 0) {
                LOGGER.error("[PFSF] Descriptor set allocation failed (pool exhausted) in recordPhaseFieldStep");
                return;
            }
            VulkanComputeContext.bindBufferToDescriptor(ds, 0, buf.getPhiBuf(), buf.getPhiOffset(), buf.getPhiSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 1, buf.getConductivityBuf(), buf.getConductivityOffset(), buf.getConductivitySize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 2, buf.getDamageBuf(), 0, buf.getDamageSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 3, buf.getHistoryBuf(), 0, buf.getDamageSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 4, buf.getTypeBuf(), buf.getTypeOffset(), buf.getTypeSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 5, buf.getGcBuf(), 0, buf.getDamageSize());

            vkCmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE,
                    phaseFieldPipelineLayout, 0, stack.longs(ds), null);

            // Push constants: Lx(4) + Ly(4) + Lz(4) + l_0(4) + dt(4) = 20 bytes
            ByteBuffer pc = stack.malloc(20);
            pc.putInt(buf.getLx()).putInt(buf.getLy()).putInt(buf.getLz());
            pc.putFloat(REGULARIZATION_LENGTH);
            pc.putFloat(0.0f); // dt reserved
            pc.flip();

            vkCmdPushConstants(cmdBuf, phaseFieldPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);
            vkCmdDispatch(cmdBuf, ceilDiv(buf.getN(), 256), 1, 1);
            VulkanComputeContext.computeBarrier(cmdBuf);
        }
    }
}
