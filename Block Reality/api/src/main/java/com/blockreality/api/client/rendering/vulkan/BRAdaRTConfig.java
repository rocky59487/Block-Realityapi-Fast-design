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

    // ─── RT-0-2: GPU 世代常數（三層 Tier）──────────────────────────────────
    /**
     * Legacy RT Tier — Ampere（SM 8.6）/Turing（SM 7.5）等支援 RT 的前代 GPU（RTX 20xx/30xx）。
     * 使用標準 BVH，不支援 SER / OMM / Cluster / ReSTIR / DDGI。
     * 對應硬體：RTX 3060 / RTX 3080 / RTX 2080 Ti 等。
     */
    public static final int TIER_LEGACY_RT  = 0;   // SM 8.6 / 7.5 (RTX 20xx / 30xx)

    /**
     * Ada Tier — Ada Lovelace（SM 8.9）GPU（RTX 40xx）。
     * 支援 SER、OMM、DDGI、NRD ReBLUR、DLSS 3 FG。
     * 對應硬體：RTX 4060 / RTX 4070 / RTX 4090 等。
     */
    public static final int TIER_ADA        = 1;   // SM 8.9 (RTX 40xx)

    /**
     * Blackwell Tier — Blackwell（SM 10.x）GPU（RTX 50xx）。
     * 支援 Cluster BVH、Cooperative Vectors、ReSTIR DI/GI、NRD ReLAX、DLSS 4 MFG。
     * 對應硬體：RTX 5070 / RTX 5080 / RTX 5090 等。
     */
    public static final int TIER_BLACKWELL  = 2;   // SM 10.x (RTX 50xx)

    // ─── AO Samples per GPU tier ─────────────────────────────────────────
    public static final int AO_SAMPLES_LEGACY_RT  = 4;
    public static final int AO_SAMPLES_ADA        = 8;
    public static final int AO_SAMPLES_BLACKWELL  = 16;

    // ─── Max bounces per GPU tier ─────────────────────────────────────────
    public static final int BOUNCES_LEGACY_RT  = 1;
    public static final int BOUNCES_ADA        = 2;
    public static final int BOUNCES_BLACKWELL  = 4;

    // ─── 偵測結果 ─────────────────────────────────────────────────────────
    private static boolean detected  = false;
    private static int     gpuTier   = -1; // -1 = 不支援 RT（非 NVIDIA 或前代非 RT GPU）

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

            // ── RT-0-2: 三層 Tier 世代判斷 ──────────────────────────────
            // 判斷優先順序：Blackwell > Ada > Legacy RT > 不支援
            //
            // Blackwell (SM 10.x, RTX 50xx)：
            //   必要條件：ClusterAS + CoopVector（兩者均為 Blackwell 首發擴展）
            //
            // Ada (SM 8.9, RTX 40xx)：
            //   必要條件：SER（VK_NV_ray_tracing_invocation_reorder）
            //   這是 Ada 最具代表性的擴展；Ampere 不支援
            //
            // Legacy RT (SM 8.6/7.5, RTX 20xx/30xx)：
            //   有 VK_KHR_ray_tracing_pipeline 但無 SER
            //   使用舊 RT pipeline，不支援 Ada/Blackwell 功能
            //
            // 不支援 (-1)：前代 GPU 無 RT，或非 NVIDIA
            if (hasClusterAS && hasCoopVector) {
                gpuTier = TIER_BLACKWELL;
            } else if (hasSER) {
                gpuTier = TIER_ADA;
            } else if (BRVulkanDevice.isRTSupported()) {
                // 有 RT Pipeline 但無 SER → Legacy RT（Ampere/Turing）
                gpuTier = TIER_LEGACY_RT;
            } else {
                gpuTier = -1; // 不支援 RT
            }

            detected = true;

            LOG.info("BRAdaRTConfig detected GPU: {}", deviceName);
            LOG.info("  [RT-0-2] Tier: {}", switch (gpuTier) {
                case TIER_BLACKWELL -> "TIER_BLACKWELL (SM10+, RTX 50xx)";
                case TIER_ADA       -> "TIER_ADA (SM8.9, RTX 40xx)";
                case TIER_LEGACY_RT -> "TIER_LEGACY_RT (SM8.6/7.5, RTX 20-30xx)";
                default             -> "UNSUPPORTED (no RT pipeline)";
            });
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
     * 上傳 BRSparseVoxelDAG 資料到 Vulkan SSBO（{@code set=3, binding=0}）。
     * 應在 DAG 更新後（chunk 卸載/重建等）呼叫。
     *
     * <p>使用 {@link BRSparseVoxelDAG#serializeForGPU()} 輸出 GPU SSBO 格式：
     * <ul>
     *   <li>Header（32 bytes）：nodeCount, dagDepth, dagOriginXYZ, dagSize, rootIndex, _pad</li>
     *   <li>Per-node（36 bytes = 9 uint32）：flags + full 8-slot child 陣列</li>
     * </ul>
     * 此格式與 {@code primary.rgen.glsl} 的 {@code dagNodeFlags()} /
     * {@code dagNodeChild()} / {@code dagQuery()} 直接相容。
     *
     * <p>注意：{@link BRSparseVoxelDAG#setWorldOrigin(int, int, int)} 必須在
     * {@link BRSparseVoxelDAG#buildFromVoxelGrid} 前設定，否則 dagOriginXYZ = (0,0,0)。
     */
    public static void uploadDAGToGPU() {
        if (!BRSparseVoxelDAG.isInitialized()) return;
        if (!BRVulkanDevice.isRTSupported()) return;

        try {
            // 使用 GPU-native 格式（非磁碟序列化格式）
            java.nio.ByteBuffer dagBuf = BRSparseVoxelDAG.serializeForGPU();
            if (dagBuf == null || dagBuf.remaining() == 0) return;

            long device = BRVulkanDevice.getVkDevice();
            long needed = dagBuf.remaining();

            // 重建 buffer（若大小改變）
            if (dagBufferSize < needed || dagBuffer == VK_NULL_HANDLE) {
                cleanupDAGBuffer();
                dagBuffer = BRVulkanDevice.createBuffer(device, needed,
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
                dagMemory = BRVulkanDevice.allocateAndBindBuffer(device, dagBuffer,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
                dagBufferSize = needed;
                LOG.debug("DAG SSBO allocated: {} bytes ({} KB, {} nodes × 36B)",
                    needed, needed / 1024, BRSparseVoxelDAG.getTotalNodes());
            }

            // 上傳（ByteBuffer → GPU memory）
            long ptr = BRVulkanDevice.mapMemory(device, dagMemory, 0, needed);
            BRVulkanDevice.memcpyBuffer(ptr, dagBuf, (int) needed);
            BRVulkanDevice.unmapMemory(device, dagMemory);

            LOG.debug("DAG SSBO uploaded: {} nodes, {} bytes (GPU SSBO format, stride=9)",
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

        // RT-0-2: 三層 Tier AO samples 選擇
        int aoSamples = switch (gpuTier) {
            case TIER_BLACKWELL -> AO_SAMPLES_BLACKWELL;
            case TIER_ADA       -> AO_SAMPLES_ADA;
            default             -> AO_SAMPLES_LEGACY_RT;
        };
        ByteBuffer data = stack.calloc(Integer.BYTES * 2);
        data.asIntBuffer()
            .put(0, effectiveGpuTier())
            .put(1, aoSamples);

        return VkSpecializationInfo.calloc(stack)
            .pMapEntries(entries)
            .pData(data);
    }

    public static VkSpecializationInfo buildClosesthitSpec(MemoryStack stack) {
        VkSpecializationMapEntry.Buffer entries = VkSpecializationMapEntry.calloc(2, stack);
        entries.get(0).constantID(0).offset(0).size(4);  // GPU_TIER
        entries.get(1).constantID(1).offset(4).size(4);  // MAX_BOUNCES

        // RT-0-2: 三層 Tier bounce 深度選擇
        int bounces = switch (gpuTier) {
            case TIER_BLACKWELL -> BOUNCES_BLACKWELL;
            case TIER_ADA       -> BOUNCES_ADA;
            default             -> BOUNCES_LEGACY_RT;
        };
        ByteBuffer data = stack.calloc(Integer.BYTES * 2);
        data.asIntBuffer()
            .put(0, effectiveGpuTier())
            .put(1, bounces);

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
     * RT-0-2: 不支援 RT（-1）降級為 TIER_LEGACY_RT（最低 RT 公分母）。
     */
    public static int effectiveGpuTier() {
        return Math.max(gpuTier, TIER_LEGACY_RT);
    }

    /** 是否為 Legacy RT 或更新（RTX 20xx/30xx 以上） */
    public static boolean isLegacyRTOrNewer()  { return gpuTier >= TIER_LEGACY_RT; }
    /** 是否為 Ada 或更新（RTX 40xx 以上），支援 SER/OMM/DDGI/ReSTIR */
    public static boolean isAdaOrNewer()       { return gpuTier >= TIER_ADA; }
    /** 是否為 Blackwell 或更新（RTX 50xx 以上），支援 Cluster BVH/ReSTIR GI */
    public static boolean isBlackwellOrNewer() { return gpuTier >= TIER_BLACKWELL; }
}
