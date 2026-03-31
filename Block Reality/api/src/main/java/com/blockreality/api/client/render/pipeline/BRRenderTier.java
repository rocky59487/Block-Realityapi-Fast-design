package com.blockreality.api.client.render.pipeline;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        TIER_0("Compatibility", "GL 3.3", "Intel HD 4000+"),
        TIER_1("Quality",       "GL 4.5", "GTX 1060+"),
        TIER_2("Ultra",         "GL 4.6", "RTX 2060+"),
        TIER_3("Ray Tracing",   "Vulkan RT", "RTX 3060+");

        public final String name, glRequirement, gpuTarget;

        Tier(String n, String g, String t) {
            name = n;
            glRequirement = g;
            gpuTarget = t;
        }
    }

    private static Tier currentTier = Tier.TIER_0;
    private static Tier maxSupportedTier = Tier.TIER_0;
    private static boolean initialized = false;
    private static String gpuVendor = "unknown";
    private static String gpuRenderer = "unknown";
    private static int glMajor, glMinor;
    private static boolean hasComputeShaders, hasMeshShaders, hasSSBO;

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

            // Vulkan RT detection — delegate to BRVulkanDevice
            try {
                if (com.blockreality.api.client.render.rt.BRVulkanDevice.isRTSupported()) {
                    maxSupportedTier = Tier.TIER_3;
                    LOG.info("Vulkan RT support detected — TIER_3 available");
                }
            } catch (Throwable t) {
                // VK not available / driver issue — silently stay on GL tier
                LOG.debug("Vulkan RT not available: {}", t.getMessage());
            }

            currentTier = maxSupportedTier;
            initialized = true;

            LOG.info("GPU: {} ({})", gpuRenderer, gpuVendor);
            LOG.info("GL Version: {}.{}", glMajor, glMinor);
            LOG.info("Compute shaders: {}, SSBO/bindless: {}, Mesh shaders: {}",
                    hasComputeShaders, hasSSBO, hasMeshShaders);
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
        int tier = currentTier.ordinal();
        return switch (feature) {
            case "compute_skinning" -> tier >= Tier.TIER_1.ordinal();
            case "gpu_culling"      -> tier >= Tier.TIER_1.ordinal();
            case "vct"              -> tier >= Tier.TIER_1.ordinal();
            case "mesh_shader"      -> tier >= Tier.TIER_2.ordinal();
            case "svdag"            -> tier >= Tier.TIER_1.ordinal();
            case "ssr"              -> tier >= Tier.TIER_0.ordinal(); // always
            case "ssgi"             -> tier >= Tier.TIER_1.ordinal();
            case "ray_tracing"      -> tier >= Tier.TIER_3.ordinal();
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
}
