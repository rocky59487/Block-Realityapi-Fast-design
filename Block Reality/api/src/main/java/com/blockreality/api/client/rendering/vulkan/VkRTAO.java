package com.blockreality.api.client.rendering.vulkan;

import com.blockreality.api.client.render.rt.BRVulkanDevice;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;

/**
 * VkRTAO — Ray-Query Ambient Occlusion via Compute Shader.
 *
 * <p>Uses {@code GL_EXT_ray_query} inside a compute shader to generate AO
 * without the overhead of a full RT pipeline (no SBT, no raygen stage).
 *
 * <p>Descriptor layout (3 sets, matching {@code rtao.comp.glsl}):
 * <pre>
 *   set 0  binding 0 : accelerationStructureEXT  (TLAS)
 *   set 0  binding 4 : image2D rg16f             (AO output)
 *   set 0  binding 5 : image2D rg16f (readonly)  (AO history for temporal blend)
 *
 *   set 1  binding 0 : sampler2D                 (GBuffer depth)
 *   set 1  binding 1 : sampler2D                 (GBuffer normal, octahedron)
 *
 *   set 2  binding 0 : uniform CameraFrame       (invVP, prevInvVP, camPos, sunDir, aoRadius…)
 * </pre>
 *
 * <p>CameraFrame UBO (set 2 b0) layout — 96 bytes:
 * <pre>
 *  offset   0 –  63 : mat4  invViewProj
 *  offset  64 – 127 : mat4  prevInvViewProj
 *  offset 128 – 139 : vec3  camPos      + float _p0
 *  offset 144 – 155 : vec3  sunDir      + float _p1
 *  offset 160 – 171 : vec3  sunColor    + float _p2
 *  offset 176 – 187 : vec3  skyColor    + float _p3
 *  offset 192       : uint  frameIndex
 *  offset 196       : float aoRadius
 *  offset 200       : float aoStrength
 *  offset 204       : float reflectionRoughnessThreshold
 *  offset 208 – 223 : float _pad[4]
 * </pre>
 * Total = 224 bytes (round up to 256 for alignment).
 */
public class VkRTAO {
    private static final Logger LOGGER = LoggerFactory.getLogger(VkRTAO.class);

    private static final int AO_SAMPLES    = 8;   // Ada; Blackwell specialisation constant → 16
    // VkFormat constants used for AO images
    private static final int VK_FORMAT_R16G16_SFLOAT = 83; // rg16f
    private static final int VK_FORMAT_R8G8B8A8_UNORM = 37; // dummy GBuffer placeholder

    private boolean initialized = false;
    private int width, height;

    // ── Vulkan pipeline resources ─────────────────────────────────────────────
    private long computePipeline    = 0L;
    private long pipelineLayout     = 0L;

    // ── Descriptor set layouts (3 sets) ──────────────────────────────────────
    private long set0Layout = 0L;
    private long set1Layout = 0L;
    private long set2Layout = 0L;

    // ── Descriptor pool + sets ────────────────────────────────────────────────
    private long descriptorPool = 0L;
    private long aoSet0 = 0L;  // TLAS + AO images
    private long aoSet1 = 0L;  // depth + normal samplers
    private long aoSet2 = 0L;  // CameraFrame UBO

    // ── AO output + history images (rg16f) ────────────────────────────────────
    private long aoOutputImage = 0L, aoOutputMem = 0L, aoOutputView = 0L;
    private long aoHistImage   = 0L, aoHistMem   = 0L, aoHistView   = 0L;

    // ── Dummy GBuffer images (1×1 black, replaced by real GBuffer later) ─────
    private long dummyDepthImage = 0L, dummyDepthMem = 0L, dummyDepthView = 0L;
    private long dummyNormImage  = 0L, dummyNormMem  = 0L, dummyNormView  = 0L;
    private long gbufSampler     = 0L;

    // ── CameraFrame UBO (set 2, b0, 256 bytes) ────────────────────────────────
    private long cameraFrameBuffer = 0L;
    private long cameraFrameMemory = 0L;
    private static final int CAMERA_FRAME_SIZE = 256;

    // ── GL output handle (composite in GL pass) ───────────────────────────────
    private int outputAoTex = 0;

    // ── AO parameters (set each frame via setCameraFrame) ─────────────────────
    private float aoRadius    = 8.0f;
    private float aoStrength  = 1.5f;

    /** Sets AO parameters for next frame. */
    public void setAOParams(float radius, float strength) {
        this.aoRadius   = radius;
        this.aoStrength = strength;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  init
    // ─────────────────────────────────────────────────────────────────────────

    public void init(int w, int h) {
        this.width  = w;
        this.height = h;

        try {
            long device = BRVulkanDevice.getVkDevice();
            if (device == 0L) {
                LOGGER.warn("[RTAO] Vulkan device not ready — RTAO disabled");
                return;
            }

            // ── 1. Descriptor set layouts ─────────────────────────────────────
            set0Layout = BRVulkanDevice.createRTAOSet0Layout(device);
            set1Layout = BRVulkanDevice.createRTAOSet1Layout(device);
            set2Layout = BRVulkanDevice.createRTAOSet2Layout(device);
            if (set0Layout == 0L || set1Layout == 0L || set2Layout == 0L) {
                LOGGER.error("[RTAO] descriptor set layout creation failed");
                return;
            }

            // ── 2. Pipeline layout (3 sets) ───────────────────────────────────
            pipelineLayout = BRVulkanDevice.createRTAOPipelineLayout(device,
                    set0Layout, set1Layout, set2Layout);
            if (pipelineLayout == 0L) {
                LOGGER.error("[RTAO] pipeline layout creation failed"); return;
            }

            // ── 3. Compute pipeline ───────────────────────────────────────────
            computePipeline = createComputePipeline(device, pipelineLayout);
            if (computePipeline == 0L) {
                LOGGER.warn("[RTAO] shader compile failed — RTAO disabled"); return;
            }

            // ── 4. AO output image (rg16f, w×h, STORAGE + SAMPLED) ───────────
            final int aoUsage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                              | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
            long[] out = BRVulkanDevice.createImage2D(device, w, h,
                    VK_FORMAT_R16G16_SFLOAT, aoUsage, VK_IMAGE_ASPECT_COLOR_BIT);
            if (out == null) { LOGGER.error("[RTAO] AO output image failed"); return; }
            aoOutputImage = out[0]; aoOutputMem = out[1]; aoOutputView = out[2];

            // ── 5. AO history image (rg16f, same size) ────────────────────────
            long[] hist = BRVulkanDevice.createImage2D(device, w, h,
                    VK_FORMAT_R16G16_SFLOAT, aoUsage, VK_IMAGE_ASPECT_COLOR_BIT);
            if (hist == null) { LOGGER.error("[RTAO] AO history image failed"); return; }
            aoHistImage = hist[0]; aoHistMem = hist[1]; aoHistView = hist[2];

            // Transition both to GENERAL for storage reads/writes
            BRVulkanDevice.transitionImageLayout(device, aoOutputImage,
                    VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                    VK_IMAGE_ASPECT_COLOR_BIT);
            BRVulkanDevice.transitionImageLayout(device, aoHistImage,
                    VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                    VK_IMAGE_ASPECT_COLOR_BIT);

            // ── 6. Dummy 1×1 GBuffer images for depth and normal samplers ─────
            //    (will be replaced once real GBuffer Vulkan images are available)
            final int dummyUsage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
            long[] dd = BRVulkanDevice.createImage2D(device, 1, 1,
                    VK_FORMAT_R8G8B8A8_UNORM, dummyUsage, VK_IMAGE_ASPECT_COLOR_BIT);
            long[] dn = BRVulkanDevice.createImage2D(device, 1, 1,
                    VK_FORMAT_R8G8B8A8_UNORM, dummyUsage, VK_IMAGE_ASPECT_COLOR_BIT);
            if (dd == null || dn == null) {
                LOGGER.warn("[RTAO] dummy GBuffer images failed; depth/normal bindings will be unset");
            } else {
                dummyDepthImage = dd[0]; dummyDepthMem = dd[1]; dummyDepthView = dd[2];
                dummyNormImage  = dn[0]; dummyNormMem  = dn[1]; dummyNormView  = dn[2];
                BRVulkanDevice.transitionImageLayout(device, dummyDepthImage,
                        VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK_IMAGE_ASPECT_COLOR_BIT);
                BRVulkanDevice.transitionImageLayout(device, dummyNormImage,
                        VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK_IMAGE_ASPECT_COLOR_BIT);
            }

            // ── 7. GBuffer sampler ────────────────────────────────────────────
            gbufSampler = BRVulkanDevice.createNearestSampler(device);

            // ── 8. CameraFrame UBO ────────────────────────────────────────────
            allocateCameraFrameUBO(device);

            // ── 9. Descriptor pool + allocate 3 sets ──────────────────────────
            descriptorPool = BRVulkanDevice.createRTAODescriptorPool(device);
            if (descriptorPool == 0L) { LOGGER.error("[RTAO] pool failed"); return; }

            long[] sets = BRVulkanDevice.allocateRTAODescriptorSets(
                    descriptorPool, set0Layout, set1Layout, set2Layout);
            if (sets == null) { LOGGER.error("[RTAO] allocate sets failed"); return; }
            aoSet0 = sets[0]; aoSet1 = sets[1]; aoSet2 = sets[2];

            // ── 10. Write initial descriptor set contents ─────────────────────
            updateDescriptorSets(device, 0L /* tlas not yet known; updated each frame */);

            // ── 11. GL output texture for compositor ──────────────────────────
            outputAoTex = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, outputAoTex);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, w, h, 0,
                    GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);

            initialized = true;
            LOGGER.info("[RTAO] initialized {}×{} ({} samples, aoRadius={})",
                    w, h, AO_SAMPLES, aoRadius);
        } catch (Exception e) {
            LOGGER.error("[RTAO] init failed", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Descriptor Set Updates
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Updates set 0 (TLAS + AO images) and set 1 (GBuffer samplers).
     * Call whenever TLAS changes (after BVH rebuild).
     */
    public void updateDescriptorSets(long device, long tlas) {
        if (aoSet0 == 0L || aoSet1 == 0L) return;
        VkDevice vkDev = BRVulkanDevice.getVkDeviceObj();
        if (vkDev == null) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // ── set 0: TLAS (b0), AO output (b4), AO history (b5) ────────────
            // Count non-zero writes
            int writeCount = 2; // b4 + b5 always
            if (tlas != 0L) writeCount++;

            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(writeCount, stack);
            int wi = 0;

            if (tlas != 0L) {
                VkWriteDescriptorSetAccelerationStructureKHR asWrite =
                    VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR)
                        .pAccelerationStructures(stack.longs(tlas));
                writes.get(wi)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .pNext(asWrite.address())
                    .dstSet(aoSet0).dstBinding(0).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR);
                wi++;
            }

            // AO output → binding 4 (STORAGE_IMAGE, GENERAL)
            writes.get(wi)
                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(aoSet0).dstBinding(4).descriptorCount(1)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .pImageInfo(VkDescriptorImageInfo.calloc(1, stack)
                    .imageView(aoOutputView).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
            wi++;

            // AO history → binding 5 (STORAGE_IMAGE, GENERAL)
            writes.get(wi)
                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(aoSet0).dstBinding(5).descriptorCount(1)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .pImageInfo(VkDescriptorImageInfo.calloc(1, stack)
                    .imageView(aoHistView).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
            wi++;

            vkUpdateDescriptorSets(vkDev, writes.limit(wi), null);

            // ── set 1: depth (b0) + normal (b1) sampler ────────────────────
            if (gbufSampler != 0L && dummyDepthView != 0L && dummyNormView != 0L) {
                VkWriteDescriptorSet.Buffer gbufWrites = VkWriteDescriptorSet.calloc(2, stack);
                gbufWrites.get(0)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(aoSet1).dstBinding(0).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .pImageInfo(VkDescriptorImageInfo.calloc(1, stack)
                        .sampler(gbufSampler).imageView(dummyDepthView)
                        .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
                gbufWrites.get(1)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(aoSet1).dstBinding(1).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .pImageInfo(VkDescriptorImageInfo.calloc(1, stack)
                        .sampler(gbufSampler).imageView(dummyNormView)
                        .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
                vkUpdateDescriptorSets(vkDev, gbufWrites, null);
            }

            // ── set 2: CameraFrame UBO (b0) ────────────────────────────────
            if (cameraFrameBuffer != 0L) {
                VkWriteDescriptorSet.Buffer uboWrite = VkWriteDescriptorSet.calloc(1, stack);
                uboWrite.get(0)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(aoSet2).dstBinding(0).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack)
                        .buffer(cameraFrameBuffer).offset(0).range(CAMERA_FRAME_SIZE));
                vkUpdateDescriptorSets(vkDev, uboWrite, null);
            }

            LOGGER.debug("[RTAO] descriptor sets updated (tlas={})", tlas);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  CameraFrame UBO helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void allocateCameraFrameUBO(long device) {
        VkDevice vkDev = BRVulkanDevice.getVkDeviceObj();
        if (vkDev == null) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pBuf = stack.mallocLong(1);
            int r = vkCreateBuffer(vkDev,
                VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(CAMERA_FRAME_SIZE)
                    .usage(VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE),
                null, pBuf);
            if (r != VK_SUCCESS) { LOGGER.error("[RTAO] CameraFrame buf failed: {}", r); return; }
            long buf = pBuf.get(0);

            VkMemoryRequirements reqs = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(vkDev, buf, reqs);
            int mt = BRVulkanDevice.findMemoryType(reqs.memoryTypeBits(),
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            if (mt <= 0) { vkDestroyBuffer(vkDev, buf, null); return; }

            LongBuffer pMem = stack.mallocLong(1);
            r = vkAllocateMemory(vkDev,
                VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(reqs.size()).memoryTypeIndex(mt),
                null, pMem);
            if (r != VK_SUCCESS) { vkDestroyBuffer(vkDev, buf, null); return; }
            long mem = pMem.get(0);
            vkBindBufferMemory(vkDev, buf, mem, 0);

            // zero-initialise
            org.lwjgl.PointerBuffer pData = stack.mallocPointer(1);
            if (vkMapMemory(vkDev, mem, 0, CAMERA_FRAME_SIZE, 0, pData) == VK_SUCCESS) {
                MemoryUtil.memSet(pData.get(0), 0, CAMERA_FRAME_SIZE);
                vkUnmapMemory(vkDev, mem);
            }

            cameraFrameBuffer = buf;
            cameraFrameMemory = mem;
            LOGGER.debug("[RTAO] CameraFrame UBO allocated: buf={}", buf);
        } catch (Exception e) {
            LOGGER.error("[RTAO] allocateCameraFrameUBO failed", e);
        }
    }

    /**
     * Writes invVP + prevInvVP + camera position + sun direction + AO parameters
     * into the CameraFrame UBO (set 2 binding 0).
     */
    public void updateCameraFrame(Matrix4f invVP, Matrix4f prevInvVP,
                                   float cx, float cy, float cz,
                                   float lx, float ly, float lz,
                                   long frameIndex) {
        if (cameraFrameMemory == 0L) return;
        VkDevice vkDev = BRVulkanDevice.getVkDeviceObj();
        if (vkDev == null) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.PointerBuffer pData = stack.mallocPointer(1);
            if (vkMapMemory(vkDev, cameraFrameMemory, 0, CAMERA_FRAME_SIZE, 0, pData) != VK_SUCCESS)
                return;
            long b = pData.get(0);
            // offset 0: mat4 invViewProj (column-major)
            writeMat4(b, 0, invVP);
            // offset 64: mat4 prevInvViewProj
            writeMat4(b, 64, prevInvVP != null ? prevInvVP : invVP);
            // offset 128: vec3 camPos + float _p0
            MemoryUtil.memPutFloat(b + 128, cx);
            MemoryUtil.memPutFloat(b + 132, cy);
            MemoryUtil.memPutFloat(b + 136, cz);
            MemoryUtil.memPutFloat(b + 140, 0.0f);
            // offset 144: vec3 sunDir + float _p1
            MemoryUtil.memPutFloat(b + 144, lx);
            MemoryUtil.memPutFloat(b + 148, ly);
            MemoryUtil.memPutFloat(b + 152, lz);
            MemoryUtil.memPutFloat(b + 156, 0.0f);
            // offset 160: vec3 sunColor (default white) + _p2
            MemoryUtil.memPutFloat(b + 160, 1.0f);
            MemoryUtil.memPutFloat(b + 164, 0.95f);
            MemoryUtil.memPutFloat(b + 168, 0.9f);
            MemoryUtil.memPutFloat(b + 172, 0.0f);
            // offset 176: vec3 skyColor (default sky blue) + _p3
            MemoryUtil.memPutFloat(b + 176, 0.5f);
            MemoryUtil.memPutFloat(b + 180, 0.7f);
            MemoryUtil.memPutFloat(b + 184, 1.0f);
            MemoryUtil.memPutFloat(b + 188, 0.0f);
            // offset 192: uint frameIndex (written as int bits)
            MemoryUtil.memPutInt(b + 192, (int)(frameIndex & 0xFFFFFFFFL));
            // offset 196: float aoRadius
            MemoryUtil.memPutFloat(b + 196, aoRadius);
            // offset 200: float aoStrength
            MemoryUtil.memPutFloat(b + 200, aoStrength);
            // offset 204: float reflectionRoughnessThreshold
            MemoryUtil.memPutFloat(b + 204, 0.4f);
            vkUnmapMemory(vkDev, cameraFrameMemory);
        }
    }

    private static void writeMat4(long base, int offset, Matrix4f m) {
        MemoryUtil.memPutFloat(base + offset +  0, m.m00());
        MemoryUtil.memPutFloat(base + offset +  4, m.m01());
        MemoryUtil.memPutFloat(base + offset +  8, m.m02());
        MemoryUtil.memPutFloat(base + offset + 12, m.m03());
        MemoryUtil.memPutFloat(base + offset + 16, m.m10());
        MemoryUtil.memPutFloat(base + offset + 20, m.m11());
        MemoryUtil.memPutFloat(base + offset + 24, m.m12());
        MemoryUtil.memPutFloat(base + offset + 28, m.m13());
        MemoryUtil.memPutFloat(base + offset + 32, m.m20());
        MemoryUtil.memPutFloat(base + offset + 36, m.m21());
        MemoryUtil.memPutFloat(base + offset + 40, m.m22());
        MemoryUtil.memPutFloat(base + offset + 44, m.m23());
        MemoryUtil.memPutFloat(base + offset + 48, m.m30());
        MemoryUtil.memPutFloat(base + offset + 52, m.m31());
        MemoryUtil.memPutFloat(base + offset + 56, m.m32());
        MemoryUtil.memPutFloat(base + offset + 60, m.m33());
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Compute pipeline creation
    // ─────────────────────────────────────────────────────────────────────────

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
            LongBuffer pPipeline = stack.mallocLong(1);

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
            if (r != VK_SUCCESS) {
                LOGGER.error("[RTAO] vkCreateComputePipelines failed: {}", r);
                return 0L;
            }
            LOGGER.info("[RTAO] compute pipeline created: {}", pPipeline.get(0));
            return pPipeline.get(0);
        }
    }

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

    // ─────────────────────────────────────────────────────────────────────────
    //  Dispatch
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Dispatches the RTAO compute shader.
     *
     * @param depthTex    GL depth texture (currently unused; future: export via GL/VK interop)
     * @param normalTex   GL normal texture (currently unused)
     * @param invProjView inverse view-projection for world-pos reconstruction
     * @param tlas        Vulkan TLAS handle (updated in descriptor set if changed)
     * @param frameIndex  current frame number (Halton seed)
     * @return GL texture ID containing the AO result (R8)
     */
    public int dispatchAO(int depthTex, int normalTex, Matrix4f invProjView, long tlas, long frameIndex) {
        if (!initialized || computePipeline == 0L) return outputAoTex;

        long device = BRVulkanDevice.getVkDevice();
        if (device == 0L || tlas == 0L) return outputAoTex;

        // Update TLAS binding in set 0 each frame (TLAS may be rebuilt)
        updateDescriptorSets(device, tlas);

        // Update CameraFrame UBO
        updateCameraFrame(invProjView, null, 0, 0, 0, 0, -1, 0, frameIndex);

        long cmd = BRVulkanDevice.beginSingleTimeCommands(device);
        if (cmd == 0L) return outputAoTex;

        VkDevice vkDev = BRVulkanDevice.getVkDeviceObj();
        if (vkDev == null) { BRVulkanDevice.endSingleTimeCommands(device, cmd); return outputAoTex; }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cb = new VkCommandBuffer(cmd, vkDev);

            // Bind compute pipeline
            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, computePipeline);

            // Bind all 3 descriptor sets
            vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout,
                0, stack.longs(aoSet0, aoSet1, aoSet2), null);

            // Dispatch 8×8 workgroups
            int groupsX = (width  + 7) / 8;
            int groupsY = (height + 7) / 8;
            vkCmdDispatch(cb, groupsX, groupsY, 1);
        }

        BRVulkanDevice.endSingleTimeCommands(device, cmd);
        return outputAoTex;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Cleanup
    // ─────────────────────────────────────────────────────────────────────────

    public void cleanup() {
        long device = BRVulkanDevice.getVkDevice();

        if (outputAoTex != 0) { GL11.glDeleteTextures(outputAoTex); outputAoTex = 0; }

        if (device != 0L) {
            if (computePipeline != 0L) BRVulkanDevice.destroyPipeline(device, computePipeline);
            if (pipelineLayout  != 0L) BRVulkanDevice.destroyPipelineLayout(device, pipelineLayout);
            // Descriptor pool frees its sets automatically
            if (descriptorPool  != 0L) BRVulkanDevice.destroyDescriptorPool(device, descriptorPool);
            if (set0Layout != 0L) BRVulkanDevice.destroyDescriptorSetLayout(device, set0Layout);
            if (set1Layout != 0L) BRVulkanDevice.destroyDescriptorSetLayout(device, set1Layout);
            if (set2Layout != 0L) BRVulkanDevice.destroyDescriptorSetLayout(device, set2Layout);

            BRVulkanDevice.destroyImage2D(device, aoOutputImage, aoOutputMem, aoOutputView);
            BRVulkanDevice.destroyImage2D(device, aoHistImage,   aoHistMem,   aoHistView);
            BRVulkanDevice.destroyImage2D(device, dummyDepthImage, dummyDepthMem, dummyDepthView);
            BRVulkanDevice.destroyImage2D(device, dummyNormImage,  dummyNormMem,  dummyNormView);

            if (gbufSampler != 0L) BRVulkanDevice.destroySampler(device, gbufSampler);

            VkDevice vkDev = BRVulkanDevice.getVkDeviceObj();
            if (vkDev != null) {
                if (cameraFrameBuffer != 0L) vkDestroyBuffer(vkDev, cameraFrameBuffer, null);
                if (cameraFrameMemory != 0L) vkFreeMemory(vkDev, cameraFrameMemory, null);
            }
        }

        initialized = false;
        LOGGER.info("[RTAO] cleaned up");
    }
}
