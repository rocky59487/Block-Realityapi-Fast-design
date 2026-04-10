package com.blockreality.api.physics.fluid;

import com.blockreality.api.physics.pfsf.VulkanComputeContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;

/**
 * 流體 Compute Pipeline 工廠 — 編譯 GLSL、建立 Vulkan Pipeline。
 *
 * <p>遵循 {@code PFSFPipelineFactory} 的模式：
 * 在 Mod 初始化時一次性建立所有 pipeline，運行時不再編譯。
 *
 * <h3>管線列表</h3>
 * <ol>
 *   <li>{@code fluid_jacobi} — 6 bindings, PC 28 bytes</li>
 *   <li>{@code fluid_pressure} — 6 bindings, PC 20 bytes</li>
 *   <li>{@code fluid_boundary} — 4 bindings, PC 20 bytes</li>
 * </ol>
 */
public class FluidPipelineFactory {

    private static final Logger LOGGER = LogManager.getLogger("BR-FluidPipeline");

    // ─── Pipeline Handles ───
    static long jacobiPipeline, jacobiPipelineLayout, jacobiDSLayout;
    static long pressurePipeline, pressurePipelineLayout, pressureDSLayout;
    static long boundaryPipeline, boundaryPipelineLayout, boundaryDSLayout;

    // Descriptor pool shared by all fluid record methods (capacity: 3 frames × 6 ds each)
    static long descriptorPool;

    /**
     * 建立所有流體 compute pipeline。
     *
     * <p>必須在 {@code VulkanComputeContext} 初始化後呼叫。
     * 完成後呼叫 {@link FluidAsyncCompute#init()} 初始化非同步管線。
     */
    public static void createAll() {
        LOGGER.info("[BR-FluidPipeline] Creating fluid compute pipelines...");

        try {
            // ─── Jacobi Diffusion Pipeline ───
            // Bindings: phi(0), phiPrev(1), density(2), volume(3), type(4), pressure(5)
            // Push constants: Lx, Ly, Lz (3×uint), diffusionRate, gravity, damping (3×float), originY (int) = 28 bytes
            jacobiDSLayout = VulkanComputeContext.createDescriptorSetLayout(6);
            jacobiPipelineLayout = VulkanComputeContext.createPipelineLayout(jacobiDSLayout, 28);
            jacobiPipeline = compilePipeline("fluid/fluid_jacobi.comp.glsl", "fluid_jacobi", jacobiPipelineLayout);

            // ─── Pressure + Velocity Pipeline ───
            // Bindings: phi(0), density(1), volume(2), type(3), pressure(4), velocity(5)
            // Push constants: Lx, Ly, Lz (3×uint), gravity (float), originY (int) = 20 bytes
            pressureDSLayout = VulkanComputeContext.createDescriptorSetLayout(6);
            pressurePipelineLayout = VulkanComputeContext.createPipelineLayout(pressureDSLayout, 20);
            pressurePipeline = compilePipeline("fluid/fluid_pressure.comp.glsl", "fluid_pressure", pressurePipelineLayout);

            // ─── Boundary Extraction Pipeline ───
            // Bindings: pressure(0), type(1), volume(2), boundaryPressure(3)
            // Push constants: Lx, Ly, Lz (3×uint), couplingFactor, minPressure (2×float) = 20 bytes
            boundaryDSLayout = VulkanComputeContext.createDescriptorSetLayout(4);
            boundaryPipelineLayout = VulkanComputeContext.createPipelineLayout(boundaryDSLayout, 20);
            boundaryPipeline = compilePipeline("fluid/fluid_boundary.comp.glsl", "fluid_boundary", boundaryPipelineLayout);

            // Descriptor pool: up to 3 frames × 3 pipelines × max 6 bindings = ~54 sets per tick
            descriptorPool = VulkanComputeContext.createDescriptorPool(128, 128);

        } catch (Exception e) {
            throw new RuntimeException("[BR-FluidPipeline] Failed to create fluid pipelines", e);
        }

        // 初始化非同步計算幀池
        FluidAsyncCompute.init();

        LOGGER.info("[BR-FluidPipeline] All fluid pipelines created successfully");
    }

    private static long compilePipeline(String shaderPath, String name, long pipelineLayout) {
        String fullPath = "assets/blockreality/shaders/compute/" + shaderPath;
        String src;
        try {
            src = VulkanComputeContext.loadShaderSource(fullPath);
        } catch (java.io.IOException e) {
            throw new RuntimeException("[BR-FluidPipeline] Failed to load shader: " + fullPath, e);
        }
        if (src == null || src.isBlank()) {
            throw new RuntimeException("[BR-FluidPipeline] Shader source empty: " + fullPath);
        }
        ByteBuffer spirv;
        try {
            spirv = VulkanComputeContext.compileGLSL(src, name);
        } catch (Exception e) {
            throw new RuntimeException("[BR-FluidPipeline] Shader compile failed for " + name, e);
        }
        if (spirv == null || spirv.remaining() == 0) {
            throw new RuntimeException("[BR-FluidPipeline] Empty SPIR-V for " + name);
        }
        long pipeline = VulkanComputeContext.createComputePipeline(spirv, pipelineLayout);
        org.lwjgl.system.MemoryUtil.memFree(spirv);
        if (pipeline == 0) {
            throw new RuntimeException("[BR-FluidPipeline] vkCreateComputePipelines returned null for " + name);
        }
        LOGGER.debug("[BR-FluidPipeline] Pipeline '{}' created", name);
        return pipeline;
    }

    /**
     * 銷毀所有流體 compute pipeline。
     */
    public static void destroyAll() {
        org.lwjgl.vulkan.VkDevice device = VulkanComputeContext.getVkDeviceObj();
        if (device == null) return;

        long[] pipelines  = {jacobiPipeline,       pressurePipeline,       boundaryPipeline};
        long[] layouts    = {jacobiPipelineLayout,  pressurePipelineLayout,  boundaryPipelineLayout};
        long[] dsLayouts  = {jacobiDSLayout,        pressureDSLayout,        boundaryDSLayout};

        for (long h : pipelines)  if (h != 0) org.lwjgl.vulkan.VK10.vkDestroyPipeline(device, h, null);
        for (long h : layouts)    if (h != 0) org.lwjgl.vulkan.VK10.vkDestroyPipelineLayout(device, h, null);
        for (long h : dsLayouts)  if (h != 0) org.lwjgl.vulkan.VK10.vkDestroyDescriptorSetLayout(device, h, null);
        if (descriptorPool != 0)  org.lwjgl.vulkan.VK10.vkDestroyDescriptorPool(device, descriptorPool, null);

        jacobiPipeline = jacobiPipelineLayout = jacobiDSLayout = 0;
        pressurePipeline = pressurePipelineLayout = pressureDSLayout = 0;
        boundaryPipeline = boundaryPipelineLayout = boundaryDSLayout = 0;
        descriptorPool = 0;
        LOGGER.info("[BR-FluidPipeline] All fluid pipelines destroyed");
    }

    // ─── Pipeline Accessors ───

    public static long getJacobiPipeline()         { return jacobiPipeline; }
    public static long getJacobiPipelineLayout()   { return jacobiPipelineLayout; }
    public static long getJacobiDSLayout()         { return jacobiDSLayout; }

    public static long getPressurePipeline()       { return pressurePipeline; }
    public static long getPressurePipelineLayout() { return pressurePipelineLayout; }
    public static long getPressureDSLayout()       { return pressureDSLayout; }

    public static long getBoundaryPipeline()       { return boundaryPipeline; }
    public static long getBoundaryPipelineLayout() { return boundaryPipelineLayout; }
    public static long getBoundaryDSLayout()       { return boundaryDSLayout; }

    public static long getDescriptorPool()         { return descriptorPool; }
}
