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
 *   <li>{@code fluid_jacobi} — 6 bindings, PC 28 bytes（既有 Jacobi 路徑）</li>
 *   <li>{@code fluid_pressure} — 6 bindings, PC 20 bytes</li>
 *   <li>{@code fluid_boundary} — 4 bindings, PC 20 bytes</li>
 *   <li>{@code fluid_advect_velocity} — 6 bindings, PC 20 bytes（NS sub-cell 路徑）</li>
 *   <li>{@code fluid_divergence} — 4 bindings, PC 16 bytes</li>
 *   <li>{@code fluid_pressure_solve} — 3 bindings, PC 20 bytes（Poisson solver）</li>
 *   <li>{@code fluid_project_velocity} — 5 bindings, PC 20 bytes</li>
 * </ol>
 */
public class FluidPipelineFactory {

    private static final Logger LOGGER = LogManager.getLogger("BR-FluidPipeline");

    // ─── Pipeline Handles ───
    static long jacobiPipeline, jacobiPipelineLayout, jacobiDSLayout;
    static long pressurePipeline, pressurePipelineLayout, pressureDSLayout;
    static long boundaryPipeline, boundaryPipelineLayout, boundaryDSLayout;

    // NS sub-cell pipeline handles
    static long advectPipeline, advectPipelineLayout, advectDSLayout;
    static long divergencePipeline, divergencePipelineLayout, divergenceDSLayout;
    static long pressureSolvePipeline, pressureSolvePipelineLayout, pressureSolveDSLayout;
    static long projectPipeline, projectPipelineLayout, projectDSLayout;

    // Descriptor pool shared by all fluid record methods
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

            // ─── NS Sub-Cell Pipelines ───
            // advect: vx(0), vy(1), vz(2), vxOld(3), vyOld(4), vzOld(5)
            // PC: Lx,Ly,Lz (3×uint), dt (float), h (float) = 20 bytes
            advectDSLayout = VulkanComputeContext.createDescriptorSetLayout(6);
            advectPipelineLayout = VulkanComputeContext.createPipelineLayout(advectDSLayout, 20);
            advectPipeline = compilePipeline("fluid/fluid_advect_velocity.comp.glsl", "fluid_advect_velocity", advectPipelineLayout);

            // divergence: vx(0), vy(1), vz(2), div(3)
            // PC: Lx,Ly,Lz (3×uint), h (float) = 16 bytes
            divergenceDSLayout = VulkanComputeContext.createDescriptorSetLayout(4);
            divergencePipelineLayout = VulkanComputeContext.createPipelineLayout(divergenceDSLayout, 16);
            divergencePipeline = compilePipeline("fluid/fluid_divergence.comp.glsl", "fluid_divergence", divergencePipelineLayout);

            // pressure_solve: pressure(0), div(1), type(2)
            // PC: Lx,Ly,Lz (3×uint), h (float), dt (float) = 20 bytes
            pressureSolveDSLayout = VulkanComputeContext.createDescriptorSetLayout(3);
            pressureSolvePipelineLayout = VulkanComputeContext.createPipelineLayout(pressureSolveDSLayout, 20);
            pressureSolvePipeline = compilePipeline("fluid/fluid_pressure_solve.comp.glsl", "fluid_pressure_solve", pressureSolvePipelineLayout);

            // project: vx(0), vy(1), vz(2), pressure(3), type(4)
            // PC: Lx,Ly,Lz (3×uint), h (float), rho (float) = 20 bytes
            projectDSLayout = VulkanComputeContext.createDescriptorSetLayout(5);
            projectPipelineLayout = VulkanComputeContext.createPipelineLayout(projectDSLayout, 20);
            projectPipeline = compilePipeline("fluid/fluid_project_velocity.comp.glsl", "fluid_project_velocity", projectPipelineLayout);

            // Descriptor pool: 7 pipelines × 128 sets each
            descriptorPool = VulkanComputeContext.createDescriptorPool(256, 256);
            if (descriptorPool == 0)
                throw new RuntimeException("[BR-FluidPipeline] createDescriptorPool returned 0 — Vulkan init incomplete?");

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

        long[] pipelines  = {jacobiPipeline,      pressurePipeline,      boundaryPipeline,
                              advectPipeline,      divergencePipeline,    pressureSolvePipeline,    projectPipeline};
        long[] layouts    = {jacobiPipelineLayout, pressurePipelineLayout, boundaryPipelineLayout,
                              advectPipelineLayout, divergencePipelineLayout, pressureSolvePipelineLayout, projectPipelineLayout};
        long[] dsLayouts  = {jacobiDSLayout,       pressureDSLayout,       boundaryDSLayout,
                              advectDSLayout,       divergenceDSLayout,     pressureSolveDSLayout,    projectDSLayout};

        for (long h : pipelines)  if (h != 0) org.lwjgl.vulkan.VK10.vkDestroyPipeline(device, h, null);
        for (long h : layouts)    if (h != 0) org.lwjgl.vulkan.VK10.vkDestroyPipelineLayout(device, h, null);
        for (long h : dsLayouts)  if (h != 0) org.lwjgl.vulkan.VK10.vkDestroyDescriptorSetLayout(device, h, null);
        if (descriptorPool != 0)  org.lwjgl.vulkan.VK10.vkDestroyDescriptorPool(device, descriptorPool, null);

        jacobiPipeline = jacobiPipelineLayout = jacobiDSLayout = 0;
        pressurePipeline = pressurePipelineLayout = pressureDSLayout = 0;
        boundaryPipeline = boundaryPipelineLayout = boundaryDSLayout = 0;
        advectPipeline = advectPipelineLayout = advectDSLayout = 0;
        divergencePipeline = divergencePipelineLayout = divergenceDSLayout = 0;
        pressureSolvePipeline = pressureSolvePipelineLayout = pressureSolveDSLayout = 0;
        projectPipeline = projectPipelineLayout = projectDSLayout = 0;
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

    // NS sub-cell pipeline accessors
    public static long getAdvectPipeline()              { return advectPipeline; }
    public static long getAdvectPipelineLayout()        { return advectPipelineLayout; }
    public static long getAdvectDSLayout()              { return advectDSLayout; }

    public static long getDivergencePipeline()          { return divergencePipeline; }
    public static long getDivergencePipelineLayout()    { return divergencePipelineLayout; }
    public static long getDivergenceDSLayout()          { return divergenceDSLayout; }

    public static long getPressureSolvePipeline()       { return pressureSolvePipeline; }
    public static long getPressureSolvePipelineLayout() { return pressureSolvePipelineLayout; }
    public static long getPressureSolveDSLayout()       { return pressureSolveDSLayout; }

    public static long getProjectPipeline()             { return projectPipeline; }
    public static long getProjectPipelineLayout()       { return projectPipelineLayout; }
    public static long getProjectDSLayout()             { return projectDSLayout; }
}
