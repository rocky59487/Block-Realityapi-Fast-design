package com.blockreality.api.physics.pfsf;

import com.blockreality.api.physics.StressField;
import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.*;

import static org.lwjgl.vulkan.VK10.*;

/**
 * PFSF 渲染橋接 — phi[] Buffer 零拷貝共享（Compute → Graphics）。
 *
 * <p>核心概念：phi[] VkBuffer 同時作為 Compute Shader 的 SSBO 和
 * Fragment Shader 的 readonly SSBO，透過 Pipeline Memory Barrier 確保
 * 寫入完畢再讀取。無需 CPU 中轉，消除記憶體頻寬瓶頸。</p>
 *
 * <p>CPU Fallback：當 Vulkan 不可用時，讀回 phi[] 產生
 * {@link StressField} 供 StressHeatmapRenderer 使用。</p>
 *
 * 參考：PFSF 手冊 §7
 */
@OnlyIn(Dist.CLIENT)
public final class PFSFRenderBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("PFSF-Render");

    private static boolean initialized = false;

    private PFSFRenderBridge() {}

    // ═══════════════════════════════════════════════════════════════
    //  Initialization
    // ═══════════════════════════════════════════════════════════════

    public static void init() {
        if (initialized) return;
        initialized = true;
        LOGGER.info("[PFSF] Render bridge initialized");
    }

    public static void shutdown() {
        initialized = false;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Pipeline Memory Barrier (§7.1)
    // ═══════════════════════════════════════════════════════════════

    /**
     * 在 Compute Dispatch 完成後、Graphics Pass 開始前插入 Memory Barrier。
     * 確保 phi[] 寫入完畢再供 Fragment Shader 讀取。
     */
    public static void insertComputeToGraphicsBarrier(VkCommandBuffer cmdBuf) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT);

            vkCmdPipelineBarrier(cmdBuf,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    0, barrier, null, null);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  CPU Fallback: StressField 產生
    // ═══════════════════════════════════════════════════════════════

    /**
     * 從 GPU 讀回 phi[] 並產生 CPU 端 StressField（供 StressHeatmapRenderer fallback）。
     *
     * @param buf island buffer
     * @return StressField（stress 0~2 normalized，damagedBlocks 為 stress ≥ 1.0 的方塊）
     */
    /**
     * 從 GPU 讀回 phi[] 並產生 CPU 端 StressField。
     * 委託至 {@link PFSFEngine#extractStressField(PFSFIslandBuffer)}（server-safe 實作）。
     *
     * @param buf island buffer
     * @return StressField
     */
    public static StressField generateCPUStressField(PFSFIslandBuffer buf) {
        return PFSFEngine.extractStressField(buf);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Query
    // ═══════════════════════════════════════════════════════════════

    /**
     * PFSF 渲染是否可用（零拷貝路徑）。
     */
    public static boolean isAvailable() {
        return initialized && PFSFEngine.isAvailable();
    }
}
