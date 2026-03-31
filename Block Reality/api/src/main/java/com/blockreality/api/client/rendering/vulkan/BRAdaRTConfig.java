package com.blockreality.api.client.rendering.vulkan;

import com.blockreality.api.client.render.optimization.BRSparseVoxelDAG;
import com.blockreality.api.client.render.rt.BRVulkanBVH;
import com.blockreality.api.client.render.rt.BRVulkanDevice;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.*;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;

/**
 * BR Ada RT Config — Ada（SM 8.9）與 Blackwell（SM 10.x）專屬 RT 功能偵測與配置。
 *
 * <h3>GPU 世代偵測</h3>
 * <pre>
 * SM 8.9 (Ada Lovelace)  → RTX 40xx：SER、OMM、ray query
 * SM 10.x (Blackwell)    → RTX 50xx：Cluster AS、Cooperative Vectors、MegaGeometry
 * </pre>
 *
 * <h3>核心優化（相對於前代 Ampere RT）</h3>
 * <ul>
 *   <li><b>SER</b>（VK_NV_ray_tracing_invocation_reorder）：
 *       按材料/LOD 重新排序 wave，消除 Minecraft 異質材料的 warp 分歧，
 *       在純體素場景可獲得 2-4× 吞吐量提升</li>
 *   <li><b>OMM</b>（VK_EXT_opacity_micromap）：
 *       玻璃/水/葉片的 alpha-test 由硬體處理，無需 any-hit shader 調用</li>
 *   <li><b>LOD-aware BLAS 幾何</b>：
 *       LOD 0-1 使用三角形幾何（精確陰影），LOD 2-3 使用 AABB（快速建構）</li>
 *   <li><b>Cluster AS</b>（Blackwell VK_NV_cluster_acceleration_structure）：
 *       將鄰近 LOD section 打包成 cluster，減少 TLAS instance 數量 8-16×</li>
 *   <li><b>RTAO Compute</b>：Ray Query 在 Compute Shader 中執行，
 *       比 RT pipeline 更低 overhead，且支援 shared memory bilateral blur</li>
 *   <li><b>DAG SSBO</b>：BRSparseVoxelDAG 序列化上傳 GPU，
 *       遠距 GI（128+ chunk）使用軟追蹤節省 RT 預算</li>
 * </ul>
 *
 * @author Block Reality Team
 */
@OnlyIn(Dist.CLIENT)
public final class BRAdaRTConfig {

    private static final Logger LOG = LoggerFactory.getLogger("BR-AdaRTCfg");

    // ─── GPU 世代常數 ─────────────────────────────────────────────────────
    public static final int TIER_ADA        = 0;   // SM 8.9 (RTX 40xx)
    public static final int TIER_BLACKWELL  = 1;   // SM 10.x (RTX 50xx)

    // ─── AO Samples per GPU tier ─────────────────────────────────────────
    public static final int AO_SAMPLES_ADA       = 8;
    public static final int AO_SAMPLES_BLACKWELL = 16;

    // ─── Max bounces per GPU tier ─────────────────────────────────────────
    public static final int BOUNCES_ADA       = 1;
    public static final int BOUNCES_BLACKWELL = 2;

    // ─── 偵測結果 ─────────────────────────────────────────────────────────
    private static boolean detected  = false;
    private static int     gpuTier   = -1; // -1 = 不支援

    // Ada 功能
    private static boolean hasSER  = false; // VK_NV_ray_tracing_invocation_reorder
    private static boolean hasOMM  = false; // VK_EXT_opacity_micromap
    private static boolean hasRayQuery = false; // VK_KHR_ray_query

    // Blackwell 功能
    private static boolean hasClusterAS   = false; // VK_NV_cluster_acceleration_structure
    private static boolean hasCoopVector  = false; // VK_NV_cooperative_vector

    // SER 屬性（invocation reorder mode）
    private static int serInvocationReorderMode = 0;

    // ─── DAG SSBO ─────────────────────────────────────────────────────────
    private static long dagBuffer     = VK_NULL_HANDLE;
    private static long dagMemory     = VK_NULL_HANDLE;
    private static long dagBufferSize = 0L;

    private BRAdaRTConfig() {}

    // ═══════════════════════════════════════════════════════════════════════
    //  GPU 世代偵測
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 偵測 GPU 世代與 RT 功能支援。
     * 必須在 BRVulkanDevice.init() 成功後呼叫。
     */
    public static void detect() {
        if (detected) return;
        if (!BRVulkanDevice.isRTSupported()) {
            LOG.info("BRAdaRTConfig: RT not supported, skipping Ada/Blackwell detection");
            detected = true;
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            long physDev = BRVulkanDevice.getVkPhysicalDevice();
            long inst    = BRVulkanDevice.getVkInstance();

            // ── SM 版本 via driver version ──────────────────────────────
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(
                new VkPhysicalDevice(physDev, new VkInstance(inst,
                    VkInstanceCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO))),
                props);

            int vendorId = props.vendorID();
            int deviceId = props.deviceID();
            String deviceName = props.deviceNameString();

            // ── NVIDIA vendor check ─────────────────────────────────────
            if (vendorId != 0x10DE) {
                LOG.info("BRAdaRTConfig: Non-NVIDIA GPU ({}), Ada/Blackwell features N/A", vendorId);
                detected = true;
                return;
            }

            // ── 列舉 device extensions ─────────────────────────────────
            IntBuffer extCount = stack.callocInt(1);
            vkEnumerateDeviceExtensionProperties(
                new VkPhysicalDevice(physDev, new VkInstance(inst,
                    VkInstanceCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO))),
                (ByteBuffer) null, extCount, null);

            VkExtensionProperties.Buffer exts =
                VkExtensionProperties.calloc(extCount.get(0), stack);
            vkEnumerateDeviceExtensionProperties(
                new VkPhysicalDevice(physDev, new VkInstance(inst,
                    VkInstanceCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO))),
                (ByteBuffer) null, extCount, exts);

            for (int i = 0; i < exts.capacity(); i++) {
                String extName = exts.get(i).extensionNameString();
                switch (extName) {
                    case "VK_NV_ray_tracing_invocation_reorder" -> hasSER        = true;
                    case "VK_EXT_opacity_micromap"              -> hasOMM        = true;
                    case "VK_KHR_ray_query"                     -> hasRayQuery   = true;
                    case "VK_NV_cluster_acceleration_structure" -> hasClusterAS  = true;
                    case "VK_NV_cooperative_vector"             -> hasCoopVector = true;
                }
            }

            // ── 世代判斷 ──────────────────────────────────────────────
            // Ada Lovelace: device ID 0x2684(4090), 0x2702(4080), etc. — SM 8.9
            // Blackwell: RTX 50xx — SM 10.x，有 ClusterAS
            if (hasClusterAS && hasCoopVector) {
                gpuTier = TIER_BLACKWELL;
            } else if (hasSER) {
                gpuTier = TIER_ADA;
            } else {
                gpuTier = -1; // 前代（Ampere 等），使用舊 pipeline
            }

            detected = true;

            LOG.info("BRAdaRTConfig detected GPU: {}", deviceName);
            LOG.info("  Tier: {}", gpuTier == TIER_BLACKWELL ? "Blackwell (SM10+)" :
                                    gpuTier == TIER_ADA       ? "Ada (SM8.9)"       : "Legacy");
            LOG.info("  SER: {}  OMM: {}  RayQuery: {}  ClusterAS: {}  CoopVec: {}",
                hasSER, hasOMM, hasRayQuery, hasClusterAS, hasCoopVector);

        } catch (Exception e) {
            LOG.error("BRAdaRTConfig detection error", e);
            detected = true; // 不重試
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  DAG SSBO 管理
    //  將 BRSparseVoxelDAG 序列化資料上傳至 GPU buffer
    //  供 primary.rgen.glsl 的遠距 GI 使用
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 上傳 BRSparseVoxelDAG 資料到 Vulkan SSBO。
     * 應在 DAG 更新後（chunk 卸載等）呼叫。
     */
    public static void uploadDAGToGPU() {
        if (!BRSparseVoxelDAG.isInitialized()) return;
        if (!BRVulkanDevice.isRTSupported()) return;

        try {
            byte[] dagData = BRSparseVoxelDAG.serialize();
            if (dagData == null || dagData.length == 0) return;

            long device = BRVulkanDevice.getVkDevice();
            long needed = dagData.length;

            // 重建 buffer（若大小改變）
            if (dagBufferSize < needed || dagBuffer == VK_NULL_HANDLE) {
                cleanupDAGBuffer();
                dagBuffer = BRVulkanDevice.createBuffer(device, needed,
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
                dagMemory = BRVulkanDevice.allocateAndBindBuffer(device, dagBuffer,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
                dagBufferSize = needed;
                LOG.debug("DAG SSBO allocated: {} bytes ({} KB)", needed, needed / 1024);
            }

            // 上傳資料
            long ptr = BRVulkanDevice.mapMemory(device, dagMemory, 0, needed);
            BRVulkanDevice.memcpy(ptr, dagData, 0, (int) needed);
            BRVulkanDevice.unmapMemory(device, dagMemory);

            LOG.debug("DAG SSBO uploaded: {} nodes, {} bytes",
                BRSparseVoxelDAG.getTotalNodes(), needed);

        } catch (Exception e) {
            LOG.debug("DAG SSBO upload error: {}", e.getMessage());
        }
    }

    private static void cleanupDAGBuffer() {
        if (!BRVulkanDevice.isRTSupported()) return;
        long device = BRVulkanDevice.getVkDevice();
        if (dagBuffer != VK_NULL_HANDLE) { BRVulkanDevice.destroyBuffer(device, dagBuffer); dagBuffer = VK_NULL_HANDLE; }
        if (dagMemory != VK_NULL_HANDLE) { BRVulkanDevice.freeMemory(device, dagMemory);   dagMemory = VK_NULL_HANDLE; }
        dagBufferSize = 0L;
    }

    public static void cleanup() {
        cleanupDAGBuffer();
        detected = false;
        gpuTier  = -1;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Specialization Constants（注入 shader pipeline）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 建立 VkSpecializationInfo，將 GPU_TIER 和 AO_SAMPLES/MAX_BOUNCES 注入 shader。
     * 由 VkRTPipeline 在建立 VkRayTracingPipelineCreateInfoKHR 時使用。
     *
     * @param stack MemoryStack（呼叫方持有）
     * @return VkSpecializationInfo，包含 SC_0 = GPU_TIER, SC_1 = AO_SAMPLES/MAX_BOUNCES
     */
    public static VkSpecializationInfo buildSpecializationInfo(MemoryStack stack) {
        // SC 0: GPU_TIER, SC 1: AO_SAMPLES（raygen/rtao用）或 MAX_BOUNCES（closesthit用）
        // 此方法返回通用版本；closesthit 另有 buildClosesthitSpec
        VkSpecializationMapEntry.Buffer entries = VkSpecializationMapEntry.calloc(2, stack);
        entries.get(0).constantID(0).offset(0).size(4);  // GPU_TIER (int)
        entries.get(1).constantID(1).offset(4).size(4);  // AO_SAMPLES (int)

        ByteBuffer data = stack.calloc(Integer.BYTES * 2);
        data.asIntBuffer()
            .put(0, effectiveGpuTier())
            .put(1, gpuTier == TIER_BLACKWELL ? AO_SAMPLES_BLACKWELL : AO_SAMPLES_ADA);

        return VkSpecializationInfo.calloc(stack)
            .pMapEntries(entries)
            .pData(data);
    }

    public static VkSpecializationInfo buildClosesthitSpec(MemoryStack stack) {
        VkSpecializationMapEntry.Buffer entries = VkSpecializationMapEntry.calloc(2, stack);
        entries.get(0).constantID(0).offset(0).size(4);  // GPU_TIER
        entries.get(1).constantID(1).offset(4).size(4);  // MAX_BOUNCES

        ByteBuffer data = stack.calloc(Integer.BYTES * 2);
        data.asIntBuffer()
            .put(0, effectiveGpuTier())
            .put(1, gpuTier == TIER_BLACKWELL ? BOUNCES_BLACKWELL : BOUNCES_ADA);

        return VkSpecializationInfo.calloc(stack)
            .pMapEntries(entries)
            .pData(data);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Getters
    // ═══════════════════════════════════════════════════════════════════════

    public static boolean isDetected()    { return detected; }
    public static int     getGpuTier()    { return gpuTier; }
    /** Ada 或更新 → SER 可用 */
    public static boolean hasSER()        { return hasSER; }
    public static boolean hasOMM()        { return hasOMM; }
    public static boolean hasRayQuery()   { return hasRayQuery; }
    /** Blackwell 專屬 */
    public static boolean hasClusterAS()  { return hasClusterAS; }
    public static boolean hasCoopVector() { return hasCoopVector; }

    public static long getDagBufferHandle() { return dagBuffer; }

    /**
     * 有效 GPU tier（供 specialization constant 使用）。
     * 前代（-1）降級為 Ada 路徑（最低公分母）。
     */
    public static int effectiveGpuTier() {
        return Math.max(gpuTier, TIER_ADA);
    }

    public static boolean isAdaOrNewer()      { return gpuTier >= TIER_ADA; }
    public static boolean isBlackwellOrNewer() { return gpuTier >= TIER_BLACKWELL; }
}
