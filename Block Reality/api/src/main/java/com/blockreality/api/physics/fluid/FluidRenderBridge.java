package com.blockreality.api.physics.fluid;

import com.blockreality.api.physics.pfsf.VulkanComputeContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static org.lwjgl.vulkan.VK10.*;

/**
 * 流體渲染橋接 — Compute↔Graphics 零拷貝 Buffer 共享。
 *
 * <p>遵循 {@code PFSFRenderBridge} 的模式：透過 Pipeline Memory Barrier
 * 讓 Graphics pipeline 直接讀取 Compute pipeline 的 velocity[] 和 volume[] buffer，
 * 無需 CPU 中繼拷貝。
 *
 * <h3>渲染資料流</h3>
 * <pre>
 * FluidGPUEngine compute → velocity[], volume[] buffers
 *         ↓ (pipeline barrier, zero-copy)
 * FluidRenderBridge → 暴露 VkBuffer handles
 *         ↓
 * WaterSurfaceNode  → 讀取 volume 驅動水面高度
 * WaterCausticsNode → 讀取 velocity magnitude 調制焦散
 * WaterFoamNode     → 讀取 velocity divergence 驅動泡沫
 * </pre>
 *
 * <p>此類別僅在客戶端載入（{@code @OnlyIn(Dist.CLIENT)} 等效邏輯）。
 */
public class FluidRenderBridge {

    private static final Logger LOGGER = LogManager.getLogger("BR-FluidRender");

    private static boolean initialized = false;

    /**
     * 初始化渲染橋接（客戶端啟動時呼叫）。
     */
    public static void init() {
        if (initialized) return;
        initialized = true;
        LOGGER.info("[BR-FluidRender] Fluid render bridge initialized");
    }

    /**
     * 關閉渲染橋接。
     */
    public static void shutdown() {
        initialized = false;
        LOGGER.info("[BR-FluidRender] Fluid render bridge shutdown");
    }

    /**
     * 插入 Compute→Graphics 記憶體屏障。
     *
     * <p>確保流體 compute shader 的 velocity/volume 寫入
     * 在 graphics shader 讀取之前完成。
     *
     * @param commandBuffer VkCommandBuffer raw handle
     */
    public static void insertComputeToGraphicsBarrier(long commandBuffer) {
        VkDevice device = VulkanComputeContext.getVkDeviceObj();
        if (commandBuffer == 0L || device == null) return;

        VkCommandBuffer vkCmd = new VkCommandBuffer(commandBuffer, device);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
            vkCmdPipelineBarrier(vkCmd,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                0, barrier, null, null);
        }
    }

    /**
     * 取得指定區域的速度場 buffer handle（供 graphics shader 綁定）。
     *
     * @param regionId 流體區域 ID
     * @return VkBuffer handle，未分配返回 0
     */
    public static long getVelocityBufferHandle(int regionId) {
        FluidRegionBuffer buf = FluidGPUEngine.getInstance().getGpuBufferFor(regionId);
        if (buf == null) return 0L;
        long[] vBuf = buf.getVelocityBuf();
        return (vBuf != null && vBuf.length > 0) ? vBuf[0] : 0L;
    }

    /**
     * 取得指定區域的體積分率 buffer handle。
     *
     * @param regionId 流體區域 ID
     * @return VkBuffer handle，未分配返回 0
     */
    public static long getVolumeBufferHandle(int regionId) {
        FluidRegionBuffer buf = FluidGPUEngine.getInstance().getGpuBufferFor(regionId);
        if (buf == null) return 0L;
        long[] vBuf = buf.getVolumeBuf();
        return (vBuf != null && vBuf.length > 0) ? vBuf[0] : 0L;
    }

    public static boolean isAvailable() {
        return initialized && FluidGPUEngine.isAvailable();
    }
}
