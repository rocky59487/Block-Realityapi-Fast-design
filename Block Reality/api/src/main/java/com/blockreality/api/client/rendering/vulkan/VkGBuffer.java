package com.blockreality.api.client.rendering.vulkan;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * VkGBuffer — 混合 GL+VK 架構的 GBuffer 管理。
 *
 * <p>在 TIER_3 模式下，GBuffer 使用 OpenGL FBO（與現有管線相容），
 * 透過 GL/VK interop 將紋理共享給 Vulkan RT pass 使用。
 *
 * <h3>GBuffer Layout</h3>
 * <pre>
 * Attachment 0: g_Albedo   — RGBA8  (RGB=albedo, A=AO)
 * Attachment 1: g_Normal   — RGBA16F (octahedron encoded normal in XY)
 * Attachment 2: g_Material — RGBA8  (roughness.r, metallic.g, matId.b, lod.a)
 * Attachment 3: g_Motion   — RG16F  (screen-space motion vector for SVGF/NRD)
 * Depth:        g_Depth    — DEPTH24_STENCIL8
 * </pre>
 *
 * <p>這些紋理對應 {@code lod.frag} 的 GBuffer 輸出。
 *
 * @author Block Reality Team
 */
@OnlyIn(Dist.CLIENT)
public final class VkGBuffer {

    private static final Logger LOG = LoggerFactory.getLogger("BR-VkGBuffer");

    // ── FBO 與 Attachment handles ─────────────────────────────────────
    private int fboId        = 0;
    private int albedoTex    = 0;  // RGBA8
    private int normalTex    = 0;  // RGBA16F — octahedron encoded
    private int materialTex  = 0;  // RGBA8   — roughness/metallic/matId/lod
    private int motionTex    = 0;  // RG16F   — screen-space motion vector
    private int depthBuffer  = 0;  // DEPTH24_STENCIL8

    private int width  = 0;
    private int height = 0;
    private boolean initialized = false;

    // ─────────────────────────────────────────────────────────────────
    //  生命週期
    // ─────────────────────────────────────────────────────────────────

    /**
     * 建立 GBuffer FBO 及所有 attachment 紋理。
     * 必須在 GL context 執行緒呼叫。
     *
     * @param width  螢幕寬度
     * @param height 螢幕高度
     */
    public void init(int width, int height) {
        cleanup(); // 釋放舊資源

        this.width  = width;
        this.height = height;

        try {
            // 建立 FBO
            fboId = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);

            // Attachment 0：Albedo RGBA8
            albedoTex = createTexture(GL11.GL_RGBA8, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,
                GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, albedoTex, 0);

            // Attachment 1：Normal RGBA16F
            normalTex = createTexture(GL30.GL_RGBA16F, GL11.GL_RGBA, GL11.GL_FLOAT);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,
                GL30.GL_COLOR_ATTACHMENT1, GL11.GL_TEXTURE_2D, normalTex, 0);

            // Attachment 2：Material RGBA8
            materialTex = createTexture(GL11.GL_RGBA8, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,
                GL30.GL_COLOR_ATTACHMENT2, GL11.GL_TEXTURE_2D, materialTex, 0);

            // Attachment 3：Motion Vector RG16F（供 SVGF/NRD 時域重投影）
            // GL_RG = 0x8227（GL30+ format constant，非 GL11，使用私有常數）
            motionTex = createTexture(GL30.GL_RG16F, GL_RG, GL11.GL_FLOAT);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,
                GL30.GL_COLOR_ATTACHMENT3, GL11.GL_TEXTURE_2D, motionTex, 0);

            // Depth+Stencil RBO
            depthBuffer = GL30.glGenRenderbuffers();
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depthBuffer);
            GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER,
                GL30.GL_DEPTH24_STENCIL8, width, height);
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER,
                GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL30.GL_RENDERBUFFER, depthBuffer);

            // 設定 draw buffers（4 個 color attachment）
            int[] drawBuffers = {
                GL30.GL_COLOR_ATTACHMENT0,
                GL30.GL_COLOR_ATTACHMENT1,
                GL30.GL_COLOR_ATTACHMENT2,
                GL30.GL_COLOR_ATTACHMENT3
            };
            GL30.glDrawBuffers(drawBuffers);

            // 檢查完整性
            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                LOG.error("GBuffer FBO incomplete: 0x{}", Integer.toHexString(status));
                cleanup();
                return;
            }

            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            initialized = true;
            LOG.info("VkGBuffer initialized ({}×{})", width, height);

        } catch (Exception e) {
            LOG.error("VkGBuffer init error", e);
            cleanup();
        }
    }

    /**
     * 釋放所有 GL 資源。
     */
    public void cleanup() {
        if (fboId != 0)       { GL30.glDeleteFramebuffers(fboId);      fboId = 0; }
        if (albedoTex != 0)   { GL11.glDeleteTextures(albedoTex);   albedoTex = 0; }
        if (normalTex != 0)   { GL11.glDeleteTextures(normalTex);   normalTex = 0; }
        if (materialTex != 0) { GL11.glDeleteTextures(materialTex); materialTex = 0; }
        if (motionTex != 0)   { GL11.glDeleteTextures(motionTex);   motionTex = 0; }
        if (depthBuffer != 0) { GL30.glDeleteRenderbuffers(depthBuffer); depthBuffer = 0; }
        initialized = false;
    }

    /**
     * 螢幕尺寸變更時重建 GBuffer。
     */
    public void resize(int newWidth, int newHeight) {
        if (newWidth == width && newHeight == height) return;
        init(newWidth, newHeight);
    }

    // ─────────────────────────────────────────────────────────────────
    //  渲染 API
    // ─────────────────────────────────────────────────────────────────

    /** 綁定 GBuffer FBO 進行 geometry pass 渲染。 */
    public void bind() {
        if (!initialized) return;
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
        GL11.glViewport(0, 0, width, height);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
    }

    /** 解綁 GBuffer，還原預設 FBO。 */
    public void unbind() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    // ─────────────────────────────────────────────────────────────────
    //  Getters
    // ─────────────────────────────────────────────────────────────────

    public boolean isInitialized() { return initialized; }
    public int getAlbedoTex()      { return albedoTex; }
    public int getNormalTex()      { return normalTex; }
    public int getMaterialTex()    { return materialTex; }
    /** Motion vector texture (RG16F) — screen-space velocity for SVGF/NRD temporal reprojection. */
    public int getMotionTex()      { return motionTex; }
    public int getDepthBuffer()    { return depthBuffer; }
    public int getFboId()          { return fboId; }
    public int getWidth()          { return width; }
    public int getHeight()         { return height; }

    // ─────────────────────────────────────────────────────────────────
    //  內部輔助
    // ─────────────────────────────────────────────────────────────────

    private int createTexture(int internalFormat, int format, int type) {
        int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat,
            width, height, 0, format, type, (java.nio.ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL14.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL14.GL_CLAMP_TO_EDGE);
        return tex;
    }

    /** GL_RG constant for RG16F motion vector texture (missing from GL11 in this version). */
    private static final int GL_RG = 0x8227;
}
