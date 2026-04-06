package com.blockreality.api.physics.pfsf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * PFSF Compute Pipeline 工廠 — 建立 / 銷毀所有 Vulkan Compute Pipeline。
 *
 * <p>管理的 pipeline：Jacobi、RBGS（v2.1）、Restrict、Prolong、FailureScan、
 * SparseScatter、FailureCompact、PhiReduceMax、PhaseFieldEvolve（v2.1）。</p>
 */
public final class PFSFPipelineFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger("PFSF-Pipeline");

    // ─── Pipeline handles (package-private for PFSFEngine access) ───
    static long jacobiPipeline, jacobiPipelineLayout, jacobiDSLayout;
    // v2.1: RBGS 8-color in-place smoother（取代 Jacobi，仍保留 Jacobi 供粗網格使用）
    static long rbgsPipeline, rbgsPipelineLayout, rbgsDSLayout;
    static long restrictPipeline, restrictPipelineLayout, restrictDSLayout;
    static long prolongPipeline, prolongPipelineLayout, prolongDSLayout;
    static long failurePipeline, failurePipelineLayout, failureDSLayout;
    static long scatterPipeline, scatterPipelineLayout, scatterDSLayout;
    static long compactPipeline, compactPipelineLayout, compactDSLayout;
    static long reduceMaxPipeline, reduceMaxPipelineLayout, reduceMaxDSLayout;
    // v2.1: Ambati 2015 hybrid phase-field evolution
    static long phaseFieldPipeline, phaseFieldPipelineLayout, phaseFieldDSLayout;

    private PFSFPipelineFactory() {}

    /**
     * 建立所有 Compute Pipeline + 初始化 PFSFAsyncCompute。
     */
    static void createAll() {
        try {
            // Jacobi（仍用於 W-Cycle 粗網格 L1/L2 平滑）
            // push constant: Lx, Ly, Lz (3×uint) + omega, rho_spec, iter (3×float/uint) + damping (float) = 28 bytes
            jacobiDSLayout = VulkanComputeContext.createDescriptorSetLayout(6); // +1 for hField (v2.1)
            jacobiPipelineLayout = VulkanComputeContext.createPipelineLayout(jacobiDSLayout, 28);
            jacobiPipeline = compilePipeline("pfsf/jacobi_smooth.comp.glsl", "jacobi_smooth.comp", jacobiPipelineLayout);

            // v2.1: RBGS 8-color smoother（細網格主求解器）
            // push constant: Lx, Ly, Lz (3×uint) + colorPass (uint) + damping (float) = 20 bytes
            rbgsDSLayout = VulkanComputeContext.createDescriptorSetLayout(5);
            rbgsPipelineLayout = VulkanComputeContext.createPipelineLayout(rbgsDSLayout, 20);
            rbgsPipeline = compilePipeline("pfsf/rbgs_smooth.comp.glsl", "rbgs_smooth.comp", rbgsPipelineLayout);

            restrictDSLayout = VulkanComputeContext.createDescriptorSetLayout(6);
            restrictPipelineLayout = VulkanComputeContext.createPipelineLayout(restrictDSLayout, 24);
            restrictPipeline = compilePipeline("pfsf/mg_restrict.comp.glsl", "mg_restrict.comp", restrictPipelineLayout);

            prolongDSLayout = VulkanComputeContext.createDescriptorSetLayout(2);
            prolongPipelineLayout = VulkanComputeContext.createPipelineLayout(prolongDSLayout, 24);
            prolongPipeline = compilePipeline("pfsf/mg_prolong.comp.glsl", "mg_prolong.comp", prolongPipelineLayout);

            failureDSLayout = VulkanComputeContext.createDescriptorSetLayout(7);
            failurePipelineLayout = VulkanComputeContext.createPipelineLayout(failureDSLayout, 16);
            failurePipeline = compilePipeline("pfsf/failure_scan.comp.glsl", "failure_scan.comp", failurePipelineLayout);

            scatterDSLayout = VulkanComputeContext.createDescriptorSetLayout(6);
            scatterPipelineLayout = VulkanComputeContext.createPipelineLayout(scatterDSLayout, 8);
            scatterPipeline = compilePipeline("pfsf/sparse_scatter.comp.glsl", "sparse_scatter.comp", scatterPipelineLayout);

            compactDSLayout = VulkanComputeContext.createDescriptorSetLayout(2);
            compactPipelineLayout = VulkanComputeContext.createPipelineLayout(compactDSLayout, 8);
            compactPipeline = compilePipeline("pfsf/failure_compact.comp.glsl", "failure_compact.comp", compactPipelineLayout);

            reduceMaxDSLayout = VulkanComputeContext.createDescriptorSetLayout(2);
            reduceMaxPipelineLayout = VulkanComputeContext.createPipelineLayout(reduceMaxDSLayout, 8);
            reduceMaxPipeline = compilePipeline("pfsf/phi_reduce_max.comp.glsl", "phi_reduce_max.comp", reduceMaxPipelineLayout);

            // v2.1: Phase-field evolution（Ambati 2015 混合相場公式）
            // bindings: phi(0), hField(1), dField(2), conductivity(3), type(4), failFlags(5), hydration(6)
            // push constant: Lx, Ly, Lz (3×uint) + l0, Gc_scale, relax (3×float) = 24 bytes
            phaseFieldDSLayout = VulkanComputeContext.createDescriptorSetLayout(7);
            phaseFieldPipelineLayout = VulkanComputeContext.createPipelineLayout(phaseFieldDSLayout, 24);
            phaseFieldPipeline = compilePipeline("pfsf/phase_field_evolve.comp.glsl", "phase_field_evolve.comp", phaseFieldPipelineLayout);

            PFSFAsyncCompute.init();

            LOGGER.info("[PFSF] All compute pipelines created (v2.1: +RBGS, +PhaseField Ambati2015)");
        } catch (Exception e) {
            throw new RuntimeException("Failed to create PFSF pipelines", e);
        }
    }

    private static long compilePipeline(String shaderPath, String name, long pipelineLayout) {
        String fullPath = "assets/blockreality/shaders/compute/" + shaderPath;
        String src;
        try {
            src = VulkanComputeContext.loadShaderSource(fullPath);
        } catch (java.io.IOException e) {
            throw new RuntimeException("[PFSF] Failed to load shader: " + fullPath, e);
        }
        if (src == null || src.isBlank()) {
            throw new RuntimeException("[PFSF] Shader source not found or empty: " + fullPath);
        }

        ByteBuffer spirv;
        try {
            spirv = VulkanComputeContext.compileGLSL(src, name);
        } catch (Exception e) {
            throw new RuntimeException("[PFSF] Shader compilation failed for " + name
                    + ": " + e.getMessage(), e);
        }

        if (spirv == null || spirv.remaining() == 0) {
            throw new RuntimeException("[PFSF] SPIR-V compilation produced empty output for " + name);
        }

        long pipeline = VulkanComputeContext.createComputePipeline(spirv, pipelineLayout);
        org.lwjgl.system.MemoryUtil.memFree(spirv);

        if (pipeline == 0) {
            throw new RuntimeException("[PFSF] vkCreateComputePipelines returned null handle for " + name);
        }

        LOGGER.debug("[PFSF] Pipeline '{}' created successfully", name);
        return pipeline;
    }
}
