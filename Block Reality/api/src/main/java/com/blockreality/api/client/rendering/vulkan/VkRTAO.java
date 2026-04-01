package com.blockreality.api.client.rendering.vulkan;

import com.blockreality.api.client.render.rt.BRVulkanDevice;
import com.blockreality.api.client.render.shader.BRShaderEngine;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.vulkan.VK10.*;

/**
 * VkRTAO (Ray Traced Ambient Occlusion) - Compute Shader Ray Query System
 * 
 * Instead of using the heavy VK_KHR_ray_tracing_pipeline (rgen, rchit, rmiss) which
 * traces out all paths and incurs Shader Binding Table (SBT) overhead, this class
 * uses a standard Compute Shader combined with VK_KHR_ray_query.
 * 
 * It reads the GBuffer depth/normal, reconstructs world position, and traces
 * short rays against the TLAS within the compute bounds to generate an AO factor.
 */
public class VkRTAO {
    private static final Logger LOGGER = LoggerFactory.getLogger(VkRTAO.class);
    
    // Hardcoded AO sample count
    private static final int AO_SAMPLES = 4;

    private boolean initialized = false;
    private int width;
    private int height;

    private long computePipeline;
    private long pipelineLayout;
    private long descriptorSetLayout;

    // Output AO texture (either Vulkan image or OpenGL handle bridged)
    private int outputAoTex = 0;

    public void init(int w, int h) {
        this.width = w;
        this.height = h;

        // Stubbed layout creation
        try {
            long device = BRVulkanDevice.getVkDevice();
            if (device != 0L) {
                // Initialize compute pipeline, layout, descriptor sets for Ray Query
                this.descriptorSetLayout = BRVulkanDevice.createRTDescriptorSetLayout(device);
                this.pipelineLayout = BRVulkanDevice.createPipelineLayout(device, descriptorSetLayout);
                
                // Assume device compile
                this.computePipeline = createComputePipeline(device, pipelineLayout);
                
                // Construct output texture
                outputAoTex = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, outputAoTex);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, w, h, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                
                initialized = true;
                LOGGER.info("VkRTAO initialized: Ray Query AO enabled ({} samples)", AO_SAMPLES);
            } else {
                LOGGER.warn("VkRTAO skipped: Vulkan device not ready.");
            }
        } catch (Exception e) {
            LOGGER.error("Failed to initialize VkRTAO", e);
        }
    }

    /** P7-H：從資源目錄載入 rtao.comp.glsl，編譯 SPIR-V，建立 VkComputePipeline。 */
    private long createComputePipeline(long device, long layout) {
        String glsl = loadShaderSource("rt/ada/rtao.comp.glsl");
        if (glsl == null) {
            LOGGER.warn("[RTAO] rtao.comp.glsl not found; RTAO disabled");
            return 0L;
        }
        byte[] spv = BRVulkanDevice.compileGLSLtoSPIRV(glsl, "rtao.comp");
        if (spv.length == 0) {
            LOGGER.error("[RTAO] GLSL compile failed; RTAO disabled");
            return 0L;
        }
        long shaderModule = BRVulkanDevice.createShaderModule(device, spv);
        if (shaderModule == 0L) return 0L;

        VkDevice vkDev = BRVulkanDevice.getVkDeviceObj();
        if (vkDev == null) { BRVulkanDevice.destroyShaderModule(device, shaderModule); return 0L; }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            java.nio.LongBuffer pPipeline = stack.mallocLong(1);

            VkPipelineShaderStageCreateInfo.Buffer stageInfo =
                VkPipelineShaderStageCreateInfo.calloc(1, stack);
            stageInfo.get(0)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                .module(shaderModule)
                .pName(stack.UTF8("main"));

            VkComputePipelineCreateInfo.Buffer pipelineInfo =
                VkComputePipelineCreateInfo.calloc(1, stack);
            pipelineInfo.get(0)
                .sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                .stage(stageInfo.get(0))
                .layout(layout);

            int r = vkCreateComputePipelines(vkDev, VK_NULL_HANDLE, pipelineInfo, null, pPipeline);
            BRVulkanDevice.destroyShaderModule(device, shaderModule);
            if (r != VK_SUCCESS) { LOGGER.error("[RTAO] vkCreateComputePipelines failed: {}", r); return 0L; }
            LOGGER.info("[RTAO] compute pipeline created: {}", pPipeline.get(0));
            return pPipeline.get(0);
        }
    }

    /** 從 JAR resources 讀取 GLSL 原始碼。 */
    private String loadShaderSource(String resourcePath) {
        try (InputStream is = getClass().getResourceAsStream(
                "/assets/blockreality/shaders/" + resourcePath)) {
            if (is == null) return null;
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.error("[RTAO] Failed to load shader {}", resourcePath, e);
            return null;
        }
    }

    /**
     * Dispatch the Ray Query compute shader to calculate AO.
     * 
     * @param depthTex GBuffer g_Depth
     * @param normalTex GBuffer g_Normal
     * @param invProjView Inverse ProjView matrix to reconstruct world position
     * @param tlas Top Level Acceleration Structure for Ray Query intersection
     * @param frameIndex for temporal accumulation and halton sequences
     * @return The GL texture ID containing the AO factor (R8 format)
     */
    /**
     * P7-H：Dispatch RTAO compute shader。
     * 每幀建立一次性 command buffer，綁定 pipeline + descriptor sets，
     * dispatch workgroups (width/8) × (height/8)。
     */
    public int dispatchAO(int depthTex, int normalTex, Matrix4f invProjView, long tlas, long frameIndex) {
        if (!initialized || computePipeline == 0L) return outputAoTex;

        long device = BRVulkanDevice.getVkDevice();
        if (device == 0L || tlas == 0L) return outputAoTex;

        long cmd = BRVulkanDevice.beginSingleTimeCommands(device);
        if (cmd == 0L) return outputAoTex;

        // Bind compute pipeline
        BRVulkanDevice.cmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, computePipeline);

        // Bind descriptor sets（TLAS + GBuffer + AO output，以 RT 通用 layout 暫代）
        // TODO P8: 建立 RTAO 專屬的 descriptor set 涵蓋 depthTex/normalTex/outputAoTex
        // 目前 descriptorSetLayout 來自 createRTDescriptorSetLayout，先跳過 bind

        // Dispatch（每 8×8 一個 workgroup）
        VkDevice vkDev = BRVulkanDevice.getVkDeviceObj();
        VkQueue  vkQ   = BRVulkanDevice.getVkQueueObj();
        if (vkDev != null) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkCommandBuffer cb = new VkCommandBuffer(cmd, vkDev);
                int groupsX = (width  + 7) / 8;
                int groupsY = (height + 7) / 8;
                vkCmdDispatch(cb, groupsX, groupsY, 1);
            }
        }

        BRVulkanDevice.endSingleTimeCommands(device, cmd);
        return outputAoTex;
    }

    public void cleanup() {
        if (outputAoTex != 0) {
            GL11.glDeleteTextures(outputAoTex);
            outputAoTex = 0;
        }

        long device = BRVulkanDevice.getVkDevice();
        if (device != 0L) {
            if (computePipeline != 0L) BRVulkanDevice.destroyPipeline(device, computePipeline);
            if (pipelineLayout != 0L) BRVulkanDevice.destroyPipelineLayout(device, pipelineLayout);
            if (descriptorSetLayout != 0L) BRVulkanDevice.destroyDescriptorSetLayout(device, descriptorSetLayout);
        }
        
        initialized = false;
        LOGGER.info("VkRTAO cleaned up");
    }
}
