package com.blockreality.api.client.render.effect;

import com.blockreality.api.client.render.BRRenderConfig;
import com.blockreality.api.client.render.shader.BRShaderEngine;
import com.blockreality.api.client.render.shader.BRShaderProgram;
import com.blockreality.api.client.render.pipeline.BRFramebufferManager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 程序化極光渲染器 — 夜間高空帷幕光效。
 *
 * 技術架構：
 *   - Ray March 穿越極光層（高度 128~256，薄帷幕）
 *   - FBM 噪聲場驅動帷幕形狀（3D Simplex/Perlin）
 *   - 色彩漸變：綠→青→紫（磁場高度映射）
 *   - 動態飄動：風場偏移 + 時間演化
 *   - 亮度脈衝：正弦波 + 噪聲調制
 *
 * 參考：
 *   - "Real-Time Aurora Rendering" (Lawlor et al.)
 *   - Shadertoy "Aurora Borealis" by nimitz
 *   - BSL/Complementary shader aurora pass
 *
 * @author Block Reality Team
 * @version 1.0
 */
@OnlyIn(Dist.CLIENT)
public class BRAuroraRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger(BRAuroraRenderer.class);

    /** 極光動畫時間累積 */
    private static float auroraTime = 0.0f;

    /** 極光亮度（受天氣強度控制） */
    private static float auroraBrightness = 0.0f;

    /** 風偏移 X（慢速漂移） */
    private static float windOffsetX = 0.0f;

    /** 風偏移 Z */
    private static float windOffsetZ = 0.0f;

    private static boolean initialized = false;

    // ========================= 初始化 =========================

    public static void init() {
        if (initialized) return;
        auroraTime = 0.0f;
        auroraBrightness = 0.0f;
        windOffsetX = 0.0f;
        windOffsetZ = 0.0f;
        initialized = true;
        LOGGER.info("[BRAuroraRenderer] 極光渲染器初始化完成");
    }

    public static void cleanup() {
        if (!initialized) return;
        initialized = false;
    }

    // ========================= 每幀更新 =========================

    public static void tick(float deltaTime, float intensity, float gameTime) {
        if (!initialized) return;

        auroraTime += deltaTime;
        auroraBrightness = intensity;

        // 慢速風漂移
        windOffsetX += deltaTime * BRRenderConfig.AURORA_WIND_SPEED * 0.3f;
        windOffsetZ += deltaTime * BRRenderConfig.AURORA_WIND_SPEED * 0.15f;
    }

    // ========================= 渲染 =========================

    /**
     * 全螢幕 composite pass — Ray March 極光帷幕。
     */
    public static void render(float intensity, float gameTime) {
        if (!initialized || intensity <= 0.001f) return;

        BRShaderProgram shader = BRShaderEngine.getAuroraShader();
        if (shader == null) return;

        int writeFbo = BRFramebufferManager.getCompositeWriteFbo();
        int readTex = BRFramebufferManager.getCompositeReadTex();

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, writeFbo);

        shader.bind();

        // 場景顏色
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, readTex);
        shader.setUniformInt("u_inputTex", 0);

        // 深度（用於天空遮罩）
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, BRFramebufferManager.getGbufferDepthTex());
        shader.setUniformInt("u_depthTex", 1);

        // 極光參數
        shader.setUniformFloat("u_auroraTime", auroraTime);
        shader.setUniformFloat("u_auroraBrightness", auroraBrightness);
        shader.setUniformFloat("u_windOffsetX", windOffsetX);
        shader.setUniformFloat("u_windOffsetZ", windOffsetZ);
        shader.setUniformFloat("u_auroraHeight", BRRenderConfig.AURORA_HEIGHT);
        shader.setUniformFloat("u_auroraThickness", BRRenderConfig.AURORA_THICKNESS);
        shader.setUniformFloat("u_screenWidth", BRFramebufferManager.getScreenWidth());
        shader.setUniformFloat("u_screenHeight", BRFramebufferManager.getScreenHeight());

        // Additive blend（極光疊加到天空）
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);

        renderFullScreenQuad();

        GL11.glDisable(GL11.GL_BLEND);
        shader.unbind();

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        BRFramebufferManager.swapCompositeBuffers();
    }

    // ─── 全螢幕 quad ───
    private static void renderFullScreenQuad() {
        GL30.glBindVertexArray(getEmptyVao());
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GL30.glBindVertexArray(0);
    }

    private static int emptyVao = 0;
    private static int getEmptyVao() {
        if (emptyVao == 0) {
            emptyVao = GL30.glGenVertexArrays();
        }
        return emptyVao;
    }
}
