package com.blockreality.api.physics.pfsf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * PFSF Compute Pipeline 工廠 — 建立 / 銷毀 7 條 Vulkan Compute Pipeline。
 *
 * <p>管理的 pipeline：Jacobi、Restrict、Prolong、FailureScan、
 * SparseScatter、FailureCompact、PhiReduceMax。</p>
 */
public final class PFSFPipelineFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger("PFSF-Pipeline");

    // ─── Pipeline handles (package-private for PFSFEngine access) ───
    static long jacobiPipeline, jacobiPipelineLayout, jacobiDSLayout;
    static long restrictPipeline, restrictPipelineLayout, restrictDSLayout;
    static long prolongPipeline, prolongPipelineLayout, prolongDSLayout;
    static long failurePipeline, failurePipelineLayout, failureDSLayout;
    static long scatterPipeline, scatterPipelineLayout, scatterDSLayout;
    static long compactPipeline, compactPipelineLayout, compactDSLayout;
    static long reduceMaxPipeline, reduceMaxPipelineLayout, reduceMaxDSLayout;

    private PFSFPipelineFactory() {}

    /**
     * 建立所有 7 條 Compute Pipeline + 初始化 PFSFAsyncCompute。
     */
    static void createAll() {
        try {
            jacobiDSLayout = VulkanComputeContext.createDescriptorSetLayout(5);
            jacobiPipelineLayout = VulkanComputeContext.createPipelineLayout(jacobiDSLayout, 28);
            jacobiPipeline = compilePipeline("pfsf/jacobi_smooth.comp.glsl", "jacobi_smooth.comp", jacobiPipelineLayout);

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

            PFSFAsyncCompute.init();

            LOGGER.info("[PFSF] All 7 compute pipelines created");
        } catch (Exception e) {
            throw new RuntimeException("Failed to create PFSF pipelines", e);
        }
    }

    private static long compilePipeline(String shaderPath, String name, long pipelineLayout) {
        String fullPath = "assets/blockreality/shaders/compute/" + shaderPath;
        String src = VulkanComputeContext.loadShaderSource(fullPath);
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
