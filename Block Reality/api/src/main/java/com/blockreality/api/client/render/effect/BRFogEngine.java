package com.blockreality.api.client.render.effect;

import com.blockreality.api.client.render.BRRenderConfig;
import com.blockreality.api.client.render.shader.BRShaderEngine;
import com.blockreality.api.client.render.shader.BRShaderProgram;
import com.blockreality.api.client.render.pipeline.BRFramebufferManager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 體積霧引擎 — 距離霧 + 高度霧 + 大氣散射整合。
 *
 * 技術融合：
 *   - Radiance/Iris: composite fog pass（距離指數霧）
 *   - Unreal Engine: "Exponential Height Fog"（高度指數衰減）
 *   - Bruneton 2017: 大氣散射與霧的統一模型
 *
 * 設計要點：
 *   - 距離霧：指數衰減（Beer-Lambert）
 *   - 高度霧：指數高度衰減（fog density = base * exp(-height * falloff)）
 *   - 霧色與大氣引擎整合（日出/日落偏暖色）
 *   - 支援霧內散射（inscattering — 光線穿透霧產生光暈）
 *   - 體積霧密度場（可選，用於洞穴/水下加濃）
 *   - 全螢幕 composite pass，讀取 depth buffer
  * @deprecated Since 2.0, replaced by Vulkan RT + Voxy LOD pipeline
*/
@Deprecated(since = "2.0", forRemoval = true)
@OnlyIn(Dist.CLIENT)
public final class BRFogEngine {
    private BRFogEngine() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("BR-Fog");

    // ─── 霧參數 ──────────────────────────────────────────
    /** 距離霧密度（越高越濃） */
    private static float distanceDensity = 0.002f;

    /** 高度霧基準密度 */
    private static float heightDensity = 0.01f;

    /** 高度霧衰減率（越高衰減越快） */
    private static float heightFalloff = 0.05f;

    /** 高度霧基準線（Y 座標以下才有效果） */
    private static float heightBase = 80.0f;

    /** 霧色（動態跟隨大氣引擎） */
    private static final Vector3f fogColor = new Vector3f(0.7f, 0.75f, 0.85f);

    /** Inscattering 強度（太陽方向的光暈） */
    private static float inscatteringStrength = 0.3f;

    /** 最大霧因子（避免全白） */
    private static float maxFogFactor = 0.95f;

    private static boolean initialized = false;

    // ═══════════════════════════════════════════════════════
    //  初始化 / 清除
    // ═══════════════════════════════════════════════════════

    public static void init() {
        if (initialized) return;
        initialized = true;
        LOGGER.info("BRFogEngine 初始化完成");
    }

    public static void cleanup() {
        initialized = false;
    }

    // ═══════════════════════════════════════════════════════
    //  每幀更新
    // ═══════════════════════════════════════════════════════

    /**
     * 更新霧色（從大氣引擎取得）。
     */
    public static void updateFogColor(Vector3f atmosphereFogColor) {
        fogColor.set(atmosphereFogColor);
    }

    /**
     * 設定霧參數（可由天氣系統動態調整）。
     */
    public static void setParameters(float distDensity, float htDensity, float htFalloff, float htBase) {
        distanceDensity = Math.max(0, distDensity);
        heightDensity = Math.max(0, htDensity);
        heightFalloff = Math.max(0.001f, htFalloff);
        heightBase = htBase;
    }

    // ═══════════════════════════════════════════════════════
    //  渲染
    // ═══════════════════════════════════════════════════════

    /**
     * 執行霧 composite pass。
     * 在 deferred lighting 之後、主 composite chain 中呼叫。
     *
     * @param cameraY     相機 Y 座標
     * @param sunDir      太陽方向
     * @param viewDir     相機朝向（用於 inscattering）
     * @param gameTime    遊戲時間
     */
    public static void renderFogPass(float cameraY, Vector3f sunDir, float gameTime) {
        if (!initialized) return;

        BRShaderProgram shader = BRShaderEngine.getFogShader();
        if (shader == null) return;

        int writeFbo = BRFramebufferManager.getCompositeWriteFbo();
        int readTex = BRFramebufferManager.getCompositeReadTex();

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0); // 確保沒有殘留綁定
        org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, writeFbo);

        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        GL11.glViewport(0, 0, mc.getWindow().getWidth(), mc.getWindow().getHeight());
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

        shader.bind();

        // 綁定場景色
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, readTex);
        shader.setUniformInt("u_mainTex", 0);

        // 綁定深度
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, BRFramebufferManager.getGbufferDepthTex());
        shader.setUniformInt("u_depthTex", 1);

        // 霧參數
        shader.setUniformFloat("u_distanceDensity", distanceDensity);
        shader.setUniformFloat("u_heightDensity", heightDensity);
        shader.setUniformFloat("u_heightFalloff", heightFalloff);
        shader.setUniformFloat("u_heightBase", heightBase);
        shader.setUniformFloat("u_cameraY", cameraY);
        shader.setUniformFloat("u_maxFog", maxFogFactor);
        shader.setUniformFloat("u_inscattering", inscatteringStrength);
        shader.setUniformVec3("u_fogColor", fogColor.x, fogColor.y, fogColor.z);
        shader.setUniformVec3("u_sunDir", sunDir.x, sunDir.y, sunDir.z);
        shader.setUniformFloat("u_nearPlane", 0.1f);
        shader.setUniformFloat("u_farPlane", (float) BRRenderConfig.LOD_MAX_DISTANCE);

        // 繪製全螢幕 quad
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);

        shader.unbind();
        org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, 0);
        BRFramebufferManager.swapCompositeBuffers();
    }

    // ─── Accessors ──────────────────────────────────────────

    public static Vector3f getFogColor() { return new Vector3f(fogColor); }
    public static float getDistanceDensity() { return distanceDensity; }
    public static float getHeightDensity() { return heightDensity; }
    public static boolean isInitialized() { return initialized; }
}
