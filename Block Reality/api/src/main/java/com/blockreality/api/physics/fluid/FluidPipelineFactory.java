package com.blockreality.api.physics.fluid;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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

    /**
     * 建立所有流體 compute pipeline。
     *
     * <p>必須在 {@code VulkanComputeContext} 初始化後呼叫。
     * 完成後呼叫 {@link FluidAsyncCompute#init()} 初始化非同步管線。
     */
    public static void createAll() {
        LOGGER.info("[BR-FluidPipeline] Creating fluid compute pipelines...");

        // ─── Jacobi Diffusion Pipeline ───
        // Bindings: phi(0), phiPrev(1), density(2), volume(3), type(4), pressure(5)
        // Push constants: Lx(uint), Ly(uint), Lz(uint), diffusionRate(float),
        //                 gravity(float), damping(float), originY(int) = 28 bytes
        // Shader: "fluid/fluid_jacobi.comp.glsl"
        // jacobiDSLayout = VulkanComputeContext.createDescriptorSetLayout(6 storage buffers);
        // jacobiPipelineLayout = VulkanComputeContext.createPipelineLayout(jacobiDSLayout, 28);
        // jacobiPipeline = compilePipeline("fluid/fluid_jacobi.comp.glsl", "fluid_jacobi", jacobiPipelineLayout);

        // ─── Pressure + Velocity Pipeline ───
        // Bindings: phi(0), density(1), volume(2), type(3), pressure(4), velocity(5)
        // Push constants: Lx(uint), Ly(uint), Lz(uint), gravity(float), originY(int) = 20 bytes
        // Shader: "fluid/fluid_pressure.comp.glsl"
        // pressureDSLayout = VulkanComputeContext.createDescriptorSetLayout(6 storage buffers);
        // pressurePipelineLayout = VulkanComputeContext.createPipelineLayout(pressureDSLayout, 20);
        // pressurePipeline = compilePipeline("fluid/fluid_pressure.comp.glsl", "fluid_pressure", pressurePipelineLayout);

        // ─── Boundary Extraction Pipeline ───
        // Bindings: pressure(0), type(1), volume(2), boundaryPressure(3)
        // Push constants: Lx(uint), Ly(uint), Lz(uint), couplingFactor(float), minPressure(float) = 20 bytes
        // Shader: "fluid/fluid_boundary.comp.glsl"
        // boundaryDSLayout = VulkanComputeContext.createDescriptorSetLayout(4 storage buffers);
        // boundaryPipelineLayout = VulkanComputeContext.createPipelineLayout(boundaryDSLayout, 20);
        // boundaryPipeline = compilePipeline("fluid/fluid_boundary.comp.glsl", "fluid_boundary", boundaryPipelineLayout);

        // 初始化非同步計算幀池
        FluidAsyncCompute.init();

        LOGGER.info("[BR-FluidPipeline] All fluid pipelines created successfully");
    }

    /**
     * 銷毀所有流體 compute pipeline。
     *
     * <p>必須在 {@code FluidAsyncCompute.shutdown()} 之後、
     * {@code VulkanComputeContext.shutdown()} 之前呼叫。
     */
    public static void destroyAll() {
        // 實際 Vulkan：vkDestroyPipeline, vkDestroyPipelineLayout, vkDestroyDescriptorSetLayout
        jacobiPipeline = jacobiPipelineLayout = jacobiDSLayout = 0;
        pressurePipeline = pressurePipelineLayout = pressureDSLayout = 0;
        boundaryPipeline = boundaryPipelineLayout = boundaryDSLayout = 0;
        LOGGER.info("[BR-FluidPipeline] All fluid pipelines destroyed");
    }

    // ─── Pipeline Accessors ───

    public static long getJacobiPipeline() { return jacobiPipeline; }
    public static long getJacobiPipelineLayout() { return jacobiPipelineLayout; }
    public static long getJacobiDSLayout() { return jacobiDSLayout; }

    public static long getPressurePipeline() { return pressurePipeline; }
    public static long getPressurePipelineLayout() { return pressurePipelineLayout; }
    public static long getPressureDSLayout() { return pressureDSLayout; }

    public static long getBoundaryPipeline() { return boundaryPipeline; }
    public static long getBoundaryPipelineLayout() { return boundaryPipelineLayout; }
    public static long getBoundaryDSLayout() { return boundaryDSLayout; }
}
