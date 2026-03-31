package com.blockreality.api.client.render.effect;

import com.blockreality.api.client.render.BRRenderConfig;
import com.blockreality.api.client.render.shader.BRShaderEngine;
import com.blockreality.api.client.render.shader.BRShaderProgram;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

/**
 * 體積霧渲染引擎 — 距離霧 + 高度霧 + 大氣散射（Inscattering）。
 * 在 deferred lighting 之後、主 composite chain 中作為一個 pass 執行。
 */
@OnlyIn(Dist.CLIENT)
public final class BRFogEngine {
    private BRFogEngine() {}

    private static boolean initialized        = false;
    private static final Vector3f fogColor    = new Vector3f(0.6f, 0.7f, 0.85f);
    private static float distanceDensity      = 0.008f;
    private static float heightDensity        = 0.04f;
    private static float heightFalloff        = 0.05f;
    private static float heightBase           = 64.0f;
    private static float maxFogFactor         = 0.92f;
    private static float inscatteringStrength = 0.35f;

    public static void init() { initialized = true; }

    public static void cleanup() { initialized = false; }

    public static void setFogColor(float r, float g, float b)  { fogColor.set(r, g, b); }
    /** 更新霧色（從大氣引擎傳入）*/
    public static void updateFogColor(Vector3f color)          { fogColor.set(color); }
    public static void setDistanceDensity(float v)             { distanceDensity = v; }
    public static void setHeightDensity(float v)               { heightDensity = v; }
    public static void setHeightFalloff(float v)               { heightFalloff = v; }
    public static void setHeightBase(float v)                  { heightBase = v; }
    public static void setMaxFogFactor(float v)                { maxFogFactor = v; }
    public static void setInscatteringStrength(float v)        { inscatteringStrength = v; }

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

        // Render to screen; read colour from Minecraft main render target
        int readTex = net.minecraft.client.Minecraft.getInstance().getMainRenderTarget().getColorTextureId();

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0); // 確保沒有殘留綁定
        org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, 0);

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
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, net.minecraft.client.Minecraft.getInstance().getMainRenderTarget().getDepthTextureId());
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
    }

    // ─── Accessors ──────────────────────────────────────────

    public static Vector3f getFogColor()        { return new Vector3f(fogColor); }
    public static float getDistanceDensity()    { return distanceDensity; }
    public static float getHeightDensity()      { return heightDensity; }
    public static boolean isInitialized()       { return initialized; }
}
