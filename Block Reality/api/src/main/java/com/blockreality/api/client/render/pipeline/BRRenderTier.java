package com.blockreality.api.client.render.pipeline;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;

/**
 * Rendering tier detection and dynamic feature switching.
 * <p>
 * Detects GPU capabilities at startup and selects an appropriate rendering tier,
 * enabling or disabling features accordingly. Users may manually override the tier
 * (clamped to the maximum supported level) via the settings menu.
 * <p>
 * Reference: Research Report v4 §9.1
 */
@OnlyIn(Dist.CLIENT)
public final class BRRenderTier {

    private BRRenderTier() {}

    private static final Logger LOG = LoggerFactory.getLogger("BR-Tier");

    public enum Tier {
        // ── 舊 Tier（向後兼容，ordinal 0–3 不變） ──────────────────────
        TIER_0("Compatibility", "GL 3.3",    "Intel HD 4000+"),
        TIER_1("Quality",       "GL 4.5",    "GTX 1060+"),
        TIER_2("Ultra",         "GL 4.6",    "RTX 2060+"),
        TIER_3("Ray Tracing",   "Vulkan RT", "RTX 3060+"),
        // ── 新語義 Tier（Phase 4-4）───────────────────────────────────
        /** Vulkan RT：陰影 + 低精度 GI，適合 VRAM < 10 GB 的 RT 顯卡 */
        RT_BALANCED("RT Balanced", "Vulkan RT", "RTX 3060 8 GB"),
        /** Vulkan RT：陰影 + 反射 + 全精度 GI，適合 VRAM ≥ 10 GB */
        RT_ULTRA("RT Ultra",    "Vulkan RT", "RTX 3080 10 GB+");

        public final String name, glRequirement, gpuTarget;

        Tier(String n, String g, String t) {
            name = n;
            glRequirement = g;
            gpuTarget = t;
        }
    }

    // ── 語義別名（方便外部程式碼引用） ──────────────────────────────────
    /** 與 TIER_0 等價：GL 3.3 相容模式，停用 LOD / RT */
    public static final Tier LEGACY   = Tier.TIER_0;
    /** 與 TIER_1 等價：啟用 Voxy LOD，停用 RT */
    public static final Tier LOD_ONLY = Tier.TIER_1;

    private static Tier currentTier = Tier.TIER_0;
    private static Tier maxSupportedTier = Tier.TIER_0;
    private static boolean initialized = false;
    private static String gpuVendor = "unknown";
    private static String gpuRenderer = "unknown";
    private static int glMajor, glMinor;
    private static boolean hasComputeShaders, hasMeshShaders, hasSSBO;

    // ═══ Vulkan RT 偵測結果 ═══
    private static boolean vulkanAvailable = false;
    private static boolean vulkanRTSupported = false;
    private static String vulkanGpuName = "unknown";
    private static long vulkanVRAMBytes = 0;

    /**
     * Detect GL version, vendor, extensions and determine the maximum supported tier.
     * Must be called on the render thread after GL context creation.
     */
    public static void init() {
        if (initialized) {
            LOG.warn("BRRenderTier already initialized, skipping");
            return;
        }

        try {
            // Read GL strings
            String versionStr = GL11.glGetString(GL11.GL_VERSION);
            gpuVendor = GL11.glGetString(GL11.GL_VENDOR);
            gpuRenderer = GL11.glGetString(GL11.GL_RENDERER);

            if (gpuVendor == null) gpuVendor = "unknown";
            if (gpuRenderer == null) gpuRenderer = "unknown";

            // Parse major.minor from GL_VERSION (format: "major.minor.release ...")
            if (versionStr != null && !versionStr.isEmpty()) {
                String[] parts = versionStr.split("[.\\s]");
                if (parts.length >= 2) {
                    try {
                        glMajor = Integer.parseInt(parts[0]);
                        glMinor = Integer.parseInt(parts[1]);
                    } catch (NumberFormatException e) {
                        LOG.warn("Failed to parse GL version from '{}', attempting fallback detection via GL_MAJOR_VERSION/GL_MINOR_VERSION", versionStr);
                        // Secondary fallback: try GL30.glGetInteger queries
                        try {
                            glMajor = GL30.glGetInteger(GL30.GL_MAJOR_VERSION);
                            glMinor = GL30.glGetInteger(GL30.GL_MINOR_VERSION);
                            LOG.info("Successfully detected GL version via integer queries: {}.{}", glMajor, glMinor);
                        } catch (Exception fallbackEx) {
                            LOG.warn("GL_MAJOR_VERSION/GL_MINOR_VERSION query also failed, defaulting to GL 3.3", fallbackEx);
                            glMajor = 3;
                            glMinor = 3;
                        }
                    }
                }
            } else {
                LOG.warn("GL_VERSION string is null or empty, attempting fallback detection via GL_MAJOR_VERSION/GL_MINOR_VERSION");
                // Secondary fallback: try GL30.glGetInteger queries
                try {
                    glMajor = GL30.glGetInteger(GL30.GL_MAJOR_VERSION);
                    glMinor = GL30.glGetInteger(GL30.GL_MINOR_VERSION);
                    LOG.info("Successfully detected GL version via integer queries: {}.{}", glMajor, glMinor);
                } catch (Exception fallbackEx) {
                    LOG.warn("GL_MAJOR_VERSION/GL_MINOR_VERSION query also failed, defaulting to GL 3.3", fallbackEx);
                    glMajor = 3;
                    glMinor = 3;
                }
            }

            // Check capabilities
            GLCapabilities caps = GL.getCapabilities();
            hasComputeShaders = caps.OpenGL43;
            hasSSBO = caps.OpenGL45;

            // Mesh shaders: NV_mesh_shader extension
            hasMeshShaders = caps.GL_NV_mesh_shader;

            // Determine max supported tier
            if (hasMeshShaders && glMajor >= 4 && glMinor >= 6) {
                maxSupportedTier = Tier.TIER_2;
            } else if (hasComputeShaders) {
                maxSupportedTier = Tier.TIER_1;
            } else if (glMajor > 3 || (glMajor == 3 && glMinor >= 3)) {
                maxSupportedTier = Tier.TIER_0;
            } else {
                maxSupportedTier = Tier.TIER_0;
            }

            // ═══ Vulkan RT 偵測（Phase 0 渲染遷移）═══
            detectVulkanRT();
            if (vulkanRTSupported) {
                // Phase 4-4：根據 VRAM 自動選擇 RT_BALANCED 或 RT_ULTRA
                // VRAM ≥ 10 GB → RT_ULTRA（完整 GI + 反射）
                // VRAM < 10 GB  → RT_BALANCED（陰影 + 低精度 GI）
                long vramGb = vulkanVRAMBytes / (1024L * 1024L * 1024L);
                maxSupportedTier = (vramGb >= 10) ? Tier.RT_ULTRA : Tier.RT_BALANCED;
                LOG.info("RT tier selected: {} (VRAM {}GB detected)", maxSupportedTier.name(), vramGb);
            }

            currentTier = maxSupportedTier;
            initialized = true;

            LOG.info("GPU: {} ({})", gpuRenderer, gpuVendor);
            LOG.info("GL Version: {}.{}", glMajor, glMinor);
            LOG.info("Compute shaders: {}, SSBO/bindless: {}, Mesh shaders: {}",
                    hasComputeShaders, hasSSBO, hasMeshShaders);
            LOG.info("Vulkan available: {}, RT: {}, GPU: {}, VRAM: {}MB",
                    vulkanAvailable, vulkanRTSupported, vulkanGpuName,
                    vulkanVRAMBytes / (1024 * 1024));
            LOG.info("Detected tier: {} ({})", currentTier.name(), currentTier.name);
        } catch (Exception e) {
            LOG.error("Failed to initialize BRRenderTier, falling back to TIER_0", e);
            currentTier = Tier.TIER_0;
            maxSupportedTier = Tier.TIER_0;
            initialized = true;
        }
    }

    /**
     * Reset all state. Intended for resource reload or shutdown.
     */
    public static void cleanup() {
        currentTier = Tier.TIER_0;
        maxSupportedTier = Tier.TIER_0;
        initialized = false;
        gpuVendor = "unknown";
        gpuRenderer = "unknown";
        glMajor = 0;
        glMinor = 0;
        hasComputeShaders = false;
        hasMeshShaders = false;
        hasSSBO = false;
        LOG.info("BRRenderTier cleaned up");
    }

    /** @return the currently active rendering tier */
    public static Tier getCurrentTier() {
        return currentTier;
    }

    /** @return the maximum tier supported by this GPU */
    public static Tier getMaxSupportedTier() {
        return maxSupportedTier;
    }

    /**
     * Manually override the rendering tier (e.g. from settings menu).
     * The tier is clamped to {@link #getMaxSupportedTier()}.
     */
    public static void setTier(Tier tier) {
        if (tier.ordinal() > maxSupportedTier.ordinal()) {
            LOG.warn("Requested tier {} exceeds max supported {}, clamping",
                    tier.name(), maxSupportedTier.name());
            currentTier = maxSupportedTier;
        } else {
            currentTier = tier;
        }
        LOG.info("Rendering tier set to: {} ({})", currentTier.name(), currentTier.name);
    }

    /**
     * Check whether a named rendering feature is enabled at the current tier.
     *
     * @param feature feature identifier (e.g. "compute_skinning", "mesh_shader")
     * @return true if the feature is available at the current tier
     */
    public static boolean isFeatureEnabled(String feature) {
        Tier t = currentTier;
        int tier = t.ordinal();
        return switch (feature) {
            case "compute_skinning" -> tier >= Tier.TIER_1.ordinal();
            case "gpu_culling"      -> tier >= Tier.TIER_1.ordinal();
            case "vct"              -> tier >= Tier.TIER_1.ordinal();
            case "mesh_shader"      -> tier >= Tier.TIER_2.ordinal();
            case "svdag"            -> tier >= Tier.TIER_1.ordinal();
            case "ssr"              -> true; // always
            case "ssgi"             -> tier >= Tier.TIER_1.ordinal();
            case "voxy_lod"         -> tier >= Tier.TIER_1.ordinal();

            // ── RT 功能（Phase 4-4：按 VRAM 分級） ───────────────────────
            // ray_tracing: RT_BALANCED / RT_ULTRA / TIER_3（向後兼容）
            case "ray_tracing"    -> tier >= Tier.TIER_3.ordinal();
            case "vulkan_rt"      -> tier >= Tier.TIER_3.ordinal();
            // rt_shadows: 所有 RT tier 均支援
            case "rt_shadows"     -> tier >= Tier.TIER_3.ordinal();
            // rt_reflections: 需要 RT_ULTRA（VRAM ≥ 10 GB）
            case "rt_reflections" -> t == Tier.RT_ULTRA;
            // rt_gi: RT_BALANCED 啟用低精度 GI（1 ray），RT_ULTRA 啟用完整 GI
            case "rt_gi"          -> t == Tier.RT_BALANCED || t == Tier.RT_ULTRA;
            // rt_gi_full: 只有 RT_ULTRA 才啟用多光線 GI
            case "rt_gi_full"     -> t == Tier.RT_ULTRA;

            default -> {
                LOG.warn("Unknown feature queried: '{}'", feature);
                yield false;
            }
        };
    }

    /** @return the GPU vendor string (e.g. "NVIDIA Corporation") */
    public static String getGPUVendor() {
        return gpuVendor;
    }

    /** @return the GPU renderer string (e.g. "NVIDIA GeForce RTX 3080/PCIe/SSE2") */
    public static String getGPURenderer() {
        return gpuRenderer;
    }

    /** @return the GL version as "major.minor" */
    public static String getGLVersion() {
        return glMajor + "." + glMinor;
    }

    /** @return true if the GPU vendor is NVIDIA */
    public static boolean isNvidia() {
        return gpuVendor.toLowerCase().contains("nvidia");
    }

    /** @return true if the GPU vendor is AMD / ATI */
    public static boolean isAMD() {
        String v = gpuVendor.toLowerCase();
        return v.contains("amd") || v.contains("ati");
    }

    /** @return true if the GPU vendor is Intel */
    public static boolean isIntel() {
        return gpuVendor.toLowerCase().contains("intel");
    }

    /** @return true if {@link #init()} has been called successfully */
    public static boolean isInitialized() {
        return initialized;
    }

    // ═══ Vulkan RT 偵測 ═══════════════════════════════════════

    /** @return true if Vulkan is available on this system */
    public static boolean isVulkanAvailable() { return vulkanAvailable; }

    /** @return true if Vulkan RT (VK_KHR_ray_tracing_pipeline) is supported */
    public static boolean isVulkanRTSupported() { return vulkanRTSupported; }

    /** @return Vulkan GPU name */
    public static String getVulkanGpuName() { return vulkanGpuName; }

    /** @return Vulkan dedicated VRAM in bytes */
    public static long getVulkanVRAMBytes() { return vulkanVRAMBytes; }

    /**
     * ★ Phase 0: 偵測 Vulkan RT 能力。
     * 使用 LWJGL Vulkan binding 列舉裝置和 extension。
     * 此方法獨立於 OpenGL 上下文，可安全呼叫。
     */
    private static void detectVulkanRT() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 1. 測試 Vulkan 是否可用
            IntBuffer pVersion = stack.mallocInt(1);
            int result = VK11.vkEnumerateInstanceVersion(pVersion);
            if (result != VK10.VK_SUCCESS) {
                LOG.info("Vulkan not available on this system (vkEnumerateInstanceVersion failed)");
                return;
            }
            int apiVersion = pVersion.get(0);
            int major = VK10.VK_API_VERSION_MAJOR(apiVersion);
            int minor = VK10.VK_API_VERSION_MINOR(apiVersion);
            LOG.info("Vulkan instance version: {}.{}", major, minor);

            // 需要 Vulkan 1.1+ 支援 acceleration structure
            if (major < 1 || (major == 1 && minor < 1)) {
                LOG.info("Vulkan version too old for RT (need 1.1+), got {}.{}", major, minor);
                vulkanAvailable = true;
                return;
            }

            // 2. 建立臨時 VkInstance（最小初始化，只為偵測）
            VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack)
                    .sType$Default()
                    .pApplicationInfo(VkApplicationInfo.calloc(stack)
                            .sType$Default()
                            .apiVersion(VK10.VK_MAKE_API_VERSION(0, 1, 1, 0)));

            var pp = stack.mallocPointer(1);
            result = VK10.vkCreateInstance(createInfo, null, pp);
            if (result != VK10.VK_SUCCESS) {
                LOG.warn("Failed to create temp Vulkan instance for RT detection: {}", result);
                vulkanAvailable = false;
                return;
            }
            VkInstance instance = new VkInstance(pp.get(0), createInfo);
            vulkanAvailable = true;

            try {
                // 3. 列舉物理裝置
                IntBuffer pCount = stack.mallocInt(1);
                VK10.vkEnumeratePhysicalDevices(instance, pCount, null);
                int deviceCount = pCount.get(0);
                if (deviceCount == 0) {
                    LOG.info("No Vulkan physical devices found");
                    return;
                }

                var devices = stack.mallocPointer(deviceCount);
                VK10.vkEnumeratePhysicalDevices(instance, pCount, devices);

                // 4. 檢查每個裝置是否支援 VK_KHR_ray_tracing_pipeline
                for (int i = 0; i < deviceCount; i++) {
                    VkPhysicalDevice physDevice = new VkPhysicalDevice(devices.get(i), instance);

                    // 讀取裝置名稱和 VRAM
                    VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
                    VK10.vkGetPhysicalDeviceProperties(physDevice, props);
                    String name = props.deviceNameString();

                    // 列舉 extension
                    VK10.vkEnumerateDeviceExtensionProperties(physDevice, (CharSequence) null, pCount, null);
                    int extCount = pCount.get(0);
                    VkExtensionProperties.Buffer exts = VkExtensionProperties.calloc(extCount, stack);
                    VK10.vkEnumerateDeviceExtensionProperties(physDevice, (CharSequence) null, pCount, exts);

                    boolean hasRTPipeline = false;
                    boolean hasAccelStruct = false;
                    for (int j = 0; j < extCount; j++) {
                        String extName = exts.get(j).extensionNameString();
                        if ("VK_KHR_ray_tracing_pipeline".equals(extName)) hasRTPipeline = true;
                        if ("VK_KHR_acceleration_structure".equals(extName)) hasAccelStruct = true;
                    }

                    if (hasRTPipeline && hasAccelStruct) {
                        vulkanRTSupported = true;
                        vulkanGpuName = name;

                        // 估算 VRAM（讀取 device-local heap 大小）
                        VkPhysicalDeviceMemoryProperties memProps =
                                VkPhysicalDeviceMemoryProperties.calloc(stack);
                        VK10.vkGetPhysicalDeviceMemoryProperties(physDevice, memProps);
                        for (int h = 0; h < memProps.memoryHeapCount(); h++) {
                            if ((memProps.memoryHeaps(h).flags() & VK10.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) {
                                vulkanVRAMBytes = Math.max(vulkanVRAMBytes, memProps.memoryHeaps(h).size());
                            }
                        }
                        LOG.info("Vulkan RT capable device found: {} (VRAM: {}MB)",
                                name, vulkanVRAMBytes / (1024 * 1024));
                        break;
                    } else {
                        LOG.debug("Device '{}': RT pipeline={}, accel struct={}", name, hasRTPipeline, hasAccelStruct);
                    }
                }
            } finally {
                // 5. 銷毀臨時 instance
                VK10.vkDestroyInstance(instance, null);
            }
        } catch (Exception e) {
            LOG.warn("Vulkan RT detection failed (non-fatal): {}", e.getMessage());
            vulkanAvailable = false;
            vulkanRTSupported = false;
        }
    }
}
