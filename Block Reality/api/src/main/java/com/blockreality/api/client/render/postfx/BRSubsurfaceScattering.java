package com.blockreality.api.client.render.postfx;

import com.blockreality.api.client.render.BRRenderConfig;
import com.blockreality.api.client.render.shader.BRShaderEngine;
import com.blockreality.api.client.render.shader.BRShaderProgram;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 螢幕空間次表面散射（Screen-Space Subsurface Scattering）。
 *
 * 技術架構：
 *   - 可分離高斯模糊近似（Separable SSS — Jimenez et al. 2015）
 *   - GBuffer material ID 標記 SSS 材質（樹葉、蜂蜜塊、冰塊、黏液塊等）
 *   - 雙 pass（水平 + 垂直）扩散光照，模擬光線穿透半透明介質
 *   - SSS 剖面由 Burley diffusion profile 近似（Christensen-Burley 2015）
 *   - 深度感知核心：避免跨物體邊界出血
 *
 * 適用方塊：
 *   - 樹葉（綠色 back-scattering）
 *   - 冰塊（青色 translucency）
 *   - 蜂蜜塊（琥珀色 deep scattering）
 *   - 黏液塊（綠色 jelly scattering）
 *   - 蠟燭（暖色 wax scattering）
 *
 * @author Block Reality Team
 * @version 1.0
 */
@OnlyIn(Dist.CLIENT)
public class BRSubsurfaceScattering {

    private static final Logger LOGGER = LoggerFactory.getLogger(BRSubsurfaceScattering.class);

    /** 中間 FBO（水平 pass 寫入，垂直 pass 讀取） */
    private static int intermediateFbo;
    private static int intermediateColorTex;
    private static int screenWidth, screenHeight;

    private static boolean initialized = false;

    // ========================= 初始化 =========================

    public static void init(int w, int h) {
        if (initialized) return;
        screenWidth = w;
        screenHeight = h;
        createFBO(w, h);
        initialized = true;
        LOGGER.info("[BRSubsurfaceScattering] SSS 初始化完成 — {}x{}", w, h);
    }

    public static void cleanup() {
        if (!initialized) return;
        destroyFBO();
        initialized = false;
    }

    public static void onResize(int w, int h) {
        if (!initialized) return;
        screenWidth = w;
        screenHeight = h;
        destroyFBO();
        createFBO(w, h);
    }

    // ========================= FBO 管理 =========================

    private static void createFBO(int w, int h) {
        intermediateFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, intermediateFbo);

        intermediateColorTex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, intermediateColorTex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, w, h, 0,
            GL11.GL_RGBA, GL11.GL_FLOAT, (java.nio.ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
            GL11.GL_TEXTURE_2D, intermediateColorTex, 0);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    private static void destroyFBO() {
        if (intermediateFbo != 0) { GL30.glDeleteFramebuffers(intermediateFbo); intermediateFbo = 0; }
        if (intermediateColorTex != 0) { GL11.glDeleteTextures(intermediateColorTex); intermediateColorTex = 0; }
    }

    // ========================= 渲染 =========================

    /**
     * 雙 pass SSS 擴散。
     * 在 deferred lighting 之後、composite chain 之前呼叫。
     *
     * Pass 1: 水平擴散（讀 composite → 寫 intermediate）
     * Pass 2: 垂直擴散（讀 intermediate → 寫 composite）
     */
    public static void render(float gameTime) {
        if (!initialized || !BRRenderConfig.SSS_ENABLED) return;

        BRShaderProgram shader = BRShaderEngine.getSSSShader();
        if (shader == null) return;

        // Get composite read texture from main render target
        int readTex = Minecraft.getInstance().getMainRenderTarget().getColorTextureId();

        // ── Pass 1: 水平 ──
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, intermediateFbo);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

        shader.bind();

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, readTex);
        shader.setUniformInt("u_inputTex", 0);

        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, Minecraft.getInstance().getMainRenderTarget().getDepthTextureId());
        shader.setUniformInt("u_depthTex", 1);

        // GBuffer material（SSS mask 在 alpha 通道）— deprecated GBuffer doesn't exist, use 0
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        shader.setUniformInt("u_materialTex", 2);

        shader.setUniformFloat("u_sssWidth", BRRenderConfig.SSS_WIDTH);
        shader.setUniformFloat("u_sssStrength", BRRenderConfig.SSS_STRENGTH);
        shader.setUniformVec2("u_direction", 1.0f / screenWidth, 0.0f); // 水平
        shader.setUniformFloat("u_screenWidth", (float) screenWidth);
        shader.setUniformFloat("u_screenHeight", (float) screenHeight);

        renderFullScreenQuad();

        // ── Pass 2: 垂直 ──
        // Render to default FBO (screen) instead of deprecated composite write FBO
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, intermediateColorTex);
        shader.setUniformInt("u_inputTex", 0);

        shader.setUniformVec2("u_direction", 0.0f, 1.0f / screenHeight); // 垂直

        renderFullScreenQuad();

        shader.unbind();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    // ─── 全螢幕 quad ───
    private static void renderFullScreenQuad() {
        GL30.glBindVertexArray(getEmptyVao());
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GL30.glBindVertexArray(0);
    }

    private static int emptyVao = 0;
    private static int getEmptyVao() {
        if (emptyVao == 0) { emptyVao = GL30.glGenVertexArrays(); }
        return emptyVao;
    }
}

