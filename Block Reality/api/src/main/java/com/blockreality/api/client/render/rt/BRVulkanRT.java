package com.blockreality.api.client.render.rt;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;

/**
 * Main Vulkan ray tracing pipeline for Block Reality.
 * Dispatches ray tracing for shadows, reflections, ambient occlusion, and global illumination.
 *
 * <p>Uses VK_KHR_ray_tracing_pipeline to trace rays against the BLAS/TLAS built by
 * {@code BRVulkanBVH}. The output is written to an image that is interoped to GL via
 * {@code BRVulkanInterop} and then denoised by {@link BRSVGFDenoiser}.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BRVulkanRT {

    private static final Logger LOGGER = LoggerFactory.getLogger("BR-VulkanRT");

    // ── RT Effects ──────────────────────────────────────────────────────────

    public enum RTEffect {
        SHADOWS(true, 0.5f),               // ~0.5ms @ 1080p
        REFLECTIONS(false, 1.0f),          // ~1.0ms
        AMBIENT_OCCLUSION(false, 0.3f),
        GLOBAL_ILLUMINATION(false, 3.0f);

        public boolean enabledByDefault;
        public float estimatedCostMs;

        RTEffect(boolean d, float c) {
            enabledByDefault = d;
            estimatedCostMs = c;
        }
    }

    // ── Constants ────────────────────────────────────────────────────────────

    /** Shader Binding Table handle size (typical, queried from device properties at init). */
    private static final int SBT_HANDLE_SIZE = 32;

    /** SBT handle alignment requirement. */
    private static final int SBT_HANDLE_ALIGNMENT = 64;

    /** Max recursion depth. Shadow-only = 1 bounce. */
    private static final int MAX_RT_RECURSION_DEPTH = 1;

    // ── GLSL Shader Sources (documentation / runtime compilation) ───────────
    //
    // In practice these would be pre-compiled to SPIR-V and loaded from
    // resources. They are kept here as Java constants so that a runtime
    // GLSL→SPIR-V path (via shaderc) can be used during development.

    private static final String RAYGEN_GLSL = """
            #version 460 core
            #extension GL_EXT_ray_tracing : require
            layout(binding = 0, set = 0) uniform accelerationStructureEXT tlas;
            layout(binding = 1, set = 0, rgba16f) uniform image2D rtOutput;
            layout(binding = 2, set = 0) uniform sampler2D gbufferDepth;
            layout(binding = 3, set = 0) uniform sampler2D gbufferNormal;
            layout(binding = 4, set = 0) uniform CameraUBO { mat4 invViewProj; vec3 cameraPos; vec3 sunDir; } cam;

            layout(location = 0) rayPayloadEXT vec4 payload;

            void main() {
                ivec2 pixel = ivec2(gl_LaunchIDEXT.xy);
                vec2 uv = (vec2(pixel) + 0.5) / vec2(gl_LaunchSizeEXT.xy);

                float depth = texture(gbufferDepth, uv).r;
                if (depth >= 1.0) { imageStore(rtOutput, pixel, vec4(1,1,1,0)); return; }

                // Reconstruct world position
                vec4 clip = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
                vec4 world = cam.invViewProj * clip;
                vec3 worldPos = world.xyz / world.w;
                vec3 normal = texture(gbufferNormal, uv).xyz;

                // Shadow ray
                payload = vec4(1.0); // default: lit
                traceRayEXT(tlas, gl_RayFlagsTerminateOnFirstHitEXT | gl_RayFlagsOpaqueEXT,
                    0xFF, 0, 0, 0,
                    worldPos + normal * 0.01, 0.001, cam.sunDir, 1000.0,
                    0);

                imageStore(rtOutput, pixel, payload);
            }
            """;

    private static final String CLOSEST_HIT_GLSL = """
            #version 460 core
            #extension GL_EXT_ray_tracing : require
            layout(location = 0) rayPayloadInEXT vec4 payload;
            void main() { payload = vec4(0.0, 0.0, 0.0, 1.0); } // shadow = occluded
            """;

    private static final String MISS_GLSL = """
            #version 460 core
            #extension GL_EXT_ray_tracing : require
            layout(location = 0) rayPayloadInEXT vec4 payload;
            void main() { payload = vec4(1.0, 1.0, 1.0, 0.0); } // no hit = lit
            """;

    // ── Pipeline state ──────────────────────────────────────────────────────

    private static boolean initialized = false;
    private static long rtPipeline;             // VkPipeline (ray tracing)
    private static long rtPipelineLayout;       // VkPipelineLayout
    private static long rtDescriptorSetLayout;
    private static long rtDescriptorPool;
    private static long rtDescriptorSet;
    private static long sbtBuffer;              // Shader Binding Table
    private static long sbtBufferMemory;
    private static final EnumSet<RTEffect> enabledEffects = EnumSet.of(RTEffect.SHADOWS);

    // SBT regions
    private static long raygenRegionOffset, raygenRegionStride, raygenRegionSize;
    private static long missRegionOffset, missRegionStride, missRegionSize;
    private static long hitRegionOffset, hitRegionStride, hitRegionSize;

    // Stats
    private static float lastTraceTimeMs;
    private static long totalRaysTraced;
    private static long frameCount;

    private BRVulkanRT() { }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    /**
     * Initialise the Vulkan ray tracing pipeline.
     *
     * <ol>
     *   <li>Check {@code BRVulkanDevice.isRTSupported()}</li>
     *   <li>Create descriptor set layout (TLAS + output image + GBuffer samplers + UBO)</li>
     *   <li>Create pipeline layout</li>
     *   <li>Load / compile shader modules (raygen, miss, closest-hit)</li>
     *   <li>Create ray tracing pipeline via {@code vkCreateRayTracingPipelinesKHR}</li>
     *   <li>Query SBT handle size and create SBT buffer</li>
     *   <li>Copy shader group handles to SBT</li>
     * </ol>
     */
    public static void init() {
        if (initialized) {
            LOGGER.warn("BRVulkanRT.init() called but already initialised");
            return;
        }

        try {
            // Step 1 — device capability check
            if (!BRVulkanDevice.isRTSupported()) {
                LOGGER.warn("Vulkan ray tracing not supported on this device — RT effects disabled");
                return;
            }

            long device = BRVulkanDevice.getVkDevice();
            LOGGER.info("Initialising Vulkan RT pipeline...");

            // Step 2 — descriptor set layout
            // Bindings: 0=TLAS, 1=output image, 2=depth sampler, 3=normal sampler, 4=camera UBO
            rtDescriptorSetLayout = createDescriptorSetLayout(device);

            // Step 3 — pipeline layout
            rtPipelineLayout = createPipelineLayout(device, rtDescriptorSetLayout);

            // Step 4 — shader modules
            long raygenModule = createShaderModule(device, RAYGEN_GLSL, "raygen");
            long missModule = createShaderModule(device, MISS_GLSL, "miss");
            long chitModule = createShaderModule(device, CLOSEST_HIT_GLSL, "closest_hit");

            // Step 5 — ray tracing pipeline
            rtPipeline = createRTPipeline(device, rtPipelineLayout, raygenModule, missModule, chitModule);

            // Destroy shader modules — no longer needed after pipeline creation
            BRVulkanDevice.destroyShaderModule(device, raygenModule);
            BRVulkanDevice.destroyShaderModule(device, missModule);
            BRVulkanDevice.destroyShaderModule(device, chitModule);

            // Step 6 — SBT
            int handleSize = BRVulkanDevice.getRTShaderGroupHandleSize();
            int alignedHandleSize = alignUp(handleSize, SBT_HANDLE_ALIGNMENT);

            raygenRegionOffset = 0;
            raygenRegionStride = alignedHandleSize;
            raygenRegionSize = alignedHandleSize;

            missRegionOffset = alignedHandleSize;
            missRegionStride = alignedHandleSize;
            missRegionSize = alignedHandleSize;

            hitRegionOffset = alignedHandleSize * 2L;
            hitRegionStride = alignedHandleSize;
            hitRegionSize = alignedHandleSize;

            long sbtSize = alignedHandleSize * 3L; // raygen + miss + hit
            sbtBuffer = BRVulkanDevice.createBuffer(device, sbtSize,
                    0x00000100 /* VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR */
                    | 0x00000200 /* VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT */);
            sbtBufferMemory = BRVulkanDevice.allocateAndBindBuffer(device, sbtBuffer,
                    0x00000002 /* VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT */
                    | 0x00000004 /* VK_MEMORY_PROPERTY_HOST_COHERENT_BIT */);

            // Step 7 — copy shader group handles into SBT
            copyShaderGroupHandlesToSBT(device, rtPipeline, sbtBufferMemory, sbtSize, handleSize, alignedHandleSize);

            // Descriptor pool + set
            rtDescriptorPool = createDescriptorPool(device);
            rtDescriptorSet = allocateDescriptorSet(device, rtDescriptorPool, rtDescriptorSetLayout);

            initialized = true;
            LOGGER.info("Vulkan RT pipeline initialised successfully");
        } catch (Exception e) {
            LOGGER.error("Failed to initialise Vulkan RT pipeline — RT effects disabled", e);
            cleanup();
        }
    }

    /**
     * Destroy the RT pipeline and all associated Vulkan resources.
     */
    public static void cleanup() {
        if (!initialized && rtPipeline == 0) {
            return;
        }

        LOGGER.info("Cleaning up Vulkan RT pipeline");
        try {
            long device = BRVulkanDevice.getVkDevice();
            BRVulkanDevice.deviceWaitIdle(device);

            if (rtDescriptorPool != 0) {
                BRVulkanDevice.destroyDescriptorPool(device, rtDescriptorPool);
                rtDescriptorPool = 0;
                rtDescriptorSet = 0;
            }
            if (sbtBuffer != 0) {
                BRVulkanDevice.destroyBuffer(device, sbtBuffer);
                sbtBuffer = 0;
            }
            if (sbtBufferMemory != 0) {
                BRVulkanDevice.freeMemory(device, sbtBufferMemory);
                sbtBufferMemory = 0;
            }
            if (rtPipeline != 0) {
                BRVulkanDevice.destroyPipeline(device, rtPipeline);
                rtPipeline = 0;
            }
            if (rtPipelineLayout != 0) {
                BRVulkanDevice.destroyPipelineLayout(device, rtPipelineLayout);
                rtPipelineLayout = 0;
            }
            if (rtDescriptorSetLayout != 0) {
                BRVulkanDevice.destroyDescriptorSetLayout(device, rtDescriptorSetLayout);
                rtDescriptorSetLayout = 0;
            }
        } catch (Exception e) {
            LOGGER.error("Error during RT pipeline cleanup", e);
        }

        initialized = false;
        lastTraceTimeMs = 0;
        totalRaysTraced = 0;
        frameCount = 0;
    }

    public static boolean isInitialized() {
        return initialized;
    }

    // ── Effect toggles ──────────────────────────────────────────────────────

    public static void enableEffect(RTEffect effect) {
        enabledEffects.add(effect);
        LOGGER.debug("RT effect enabled: {} (est. {:.1f}ms)", effect.name(), effect.estimatedCostMs);
    }

    public static void disableEffect(RTEffect effect) {
        enabledEffects.remove(effect);
        LOGGER.debug("RT effect disabled: {}", effect.name());
    }

    public static boolean isEffectEnabled(RTEffect effect) {
        return enabledEffects.contains(effect);
    }

    public static EnumSet<RTEffect> getEnabledEffects() {
        return EnumSet.copyOf(enabledEffects);
    }

    // ── Trace dispatch ──────────────────────────────────────────────────────

    /**
     * Dispatch ray tracing for the current frame.
     *
     * <ol>
     *   <li>Record command buffer</li>
     *   <li>Bind RT pipeline</li>
     *   <li>Update descriptor set (TLAS from BRVulkanBVH, output from BRVulkanInterop)</li>
     *   <li>{@code vkCmdTraceRaysKHR} with SBT regions</li>
     *   <li>Submit command buffer</li>
     * </ol>
     *
     * @param width  dispatch width in pixels
     * @param height dispatch height in pixels
     */
    public static void traceRays(int width, int height) {
        if (!initialized) {
            return;
        }

        try {
            long device = BRVulkanDevice.getVkDevice();
            long commandBuffer = BRVulkanDevice.beginSingleTimeCommands(device);

            // Bind ray tracing pipeline
            BRVulkanDevice.cmdBindPipeline(commandBuffer,
                    0x3B9D0A8B /* VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR */, rtPipeline);

            // Bind descriptor sets
            BRVulkanDevice.cmdBindDescriptorSets(commandBuffer,
                    0x3B9D0A8B /* VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR */,
                    rtPipelineLayout, 0, rtDescriptorSet);

            // Get SBT device address
            long sbtAddress = BRVulkanDevice.getBufferDeviceAddress(device, sbtBuffer);

            // Trace rays
            long startTime = System.nanoTime();
            BRVulkanDevice.cmdTraceRaysKHR(commandBuffer,
                    sbtAddress + raygenRegionOffset, raygenRegionStride, raygenRegionSize,
                    sbtAddress + missRegionOffset, missRegionStride, missRegionSize,
                    sbtAddress + hitRegionOffset, hitRegionStride, hitRegionSize,
                    0, 0, 0, // callable (unused)
                    width, height, 1);

            BRVulkanDevice.endSingleTimeCommands(device, commandBuffer);

            long elapsed = System.nanoTime() - startTime;
            lastTraceTimeMs = elapsed / 1_000_000.0f;
            totalRaysTraced += (long) width * height;
            frameCount++;
        } catch (Exception e) {
            LOGGER.error("Error during ray trace dispatch", e);
        }
    }

    /**
     * Update the descriptor set bindings for TLAS and output image.
     *
     * @param tlas            Vulkan acceleration structure handle
     * @param outputImageView VkImageView for the RT output
     */
    public static void updateDescriptors(long tlas, long outputImageView) {
        if (!initialized) {
            return;
        }

        try {
            long device = BRVulkanDevice.getVkDevice();
            BRVulkanDevice.updateRTDescriptorSet(device, rtDescriptorSet, tlas, outputImageView);
        } catch (Exception e) {
            LOGGER.error("Failed to update RT descriptors", e);
        }
    }

    /**
     * Upload camera data to the UBO bound at binding 4.
     */
    public static void setCameraData(Matrix4f invViewProj,
                                     float camX, float camY, float camZ,
                                     float sunDirX, float sunDirY, float sunDirZ) {
        if (!initialized) {
            return;
        }

        try {
            long device = BRVulkanDevice.getVkDevice();
            BRVulkanDevice.updateCameraUBO(device, rtDescriptorSet,
                    invViewProj, camX, camY, camZ, sunDirX, sunDirY, sunDirZ);
        } catch (Exception e) {
            LOGGER.error("Failed to update camera UBO", e);
        }
    }

    // ── Stats ───────────────────────────────────────────────────────────────

    public static float getLastTraceTimeMs() {
        return lastTraceTimeMs;
    }

    public static long getTotalRaysTraced() {
        return totalRaysTraced;
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    private static int alignUp(int value, int alignment) {
        return (value + alignment - 1) & ~(alignment - 1);
    }

    private static long createDescriptorSetLayout(long device) {
        // Binding 0: acceleration structure (TLAS)
        // Binding 1: storage image (RT output, rgba16f)
        // Binding 2: combined image sampler (gbuffer depth)
        // Binding 3: combined image sampler (gbuffer normal)
        // Binding 4: uniform buffer (camera UBO)
        return BRVulkanDevice.createRTDescriptorSetLayout(device);
    }

    private static long createPipelineLayout(long device, long descriptorSetLayout) {
        return BRVulkanDevice.createPipelineLayout(device, descriptorSetLayout);
    }

    private static long createShaderModule(long device, String glslSource, String name) {
        // Compile GLSL to SPIR-V via shaderc (runtime) or load pre-compiled resource
        byte[] spirv = BRVulkanDevice.compileGLSLtoSPIRV(glslSource, name);
        if (spirv == null || spirv.length == 0) {
            throw new RuntimeException("Failed to compile RT shader: " + name);
        }
        return BRVulkanDevice.createShaderModule(device, spirv);
    }

    private static long createRTPipeline(long device, long pipelineLayout,
                                         long raygenModule, long missModule, long chitModule) {
        return BRVulkanDevice.createRayTracingPipeline(device, pipelineLayout,
                raygenModule, missModule, chitModule, MAX_RT_RECURSION_DEPTH);
    }

    private static void copyShaderGroupHandlesToSBT(long device, long pipeline,
                                                     long memory, long sbtSize,
                                                     int handleSize, int alignedHandleSize) {
        byte[] handles = BRVulkanDevice.getRayTracingShaderGroupHandles(device, pipeline, 3, handleSize);
        if (handles == null) {
            throw new RuntimeException("Failed to query RT shader group handles");
        }

        // Map SBT memory and copy aligned handles
        long mapped = BRVulkanDevice.mapMemory(device, memory, 0, sbtSize);
        for (int i = 0; i < 3; i++) {
            BRVulkanDevice.memcpy(mapped + (long) i * alignedHandleSize,
                    handles, i * handleSize, handleSize);
        }
        BRVulkanDevice.unmapMemory(device, memory);
    }

    private static long createDescriptorPool(long device) {
        return BRVulkanDevice.createRTDescriptorPool(device);
    }

    private static long allocateDescriptorSet(long device, long pool, long layout) {
        return BRVulkanDevice.allocateDescriptorSet(device, pool, layout);
    }
}
