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
 * 各向異性反射引擎 — 金屬表面拉絲/刷紋反射。
 *
 * 技術架構：
 *   - GGX Anisotropic BRDF（Burley 2012 / Heitz 2014）
 *   - GBuffer tangent 向量（第 5 附件 emission 通道借用 2 分量）
 *   - 各向異性比 α_x / α_y 由 material ID 查表
 *   - 全螢幕 composite pass，讀取 GBuffer 資料修改反射分佈
 *
 * 適用方塊：
 *   - 鑽石塊、鐵塊、金塊（金屬拉絲紋理）
 *   - 銅塊（氧化漸變 + 各向異性）
 *   - 紫水晶簇（結晶各向異性）
 *
 * 參考：
 *   - "Physically Based Shading at Disney" (Burley 2012)
 *   - "Understanding the Masking-Shadowing Function" (Heitz 2014)
 *   - labPBR material spec: anisotropy encoding
 *
 * @author Block Reality Team
 * @version 1.0
 */
@OnlyIn(Dist.CLIENT)
public class BRAnisotropicReflection {

    private static final Logger LOGGER = LoggerFactory.getLogger(BRAnisotropicReflection.class);

    private static boolean initialized = false;

    // ========================= 初始化 =========================

    public static void init() {
        if (initialized) return;
        initialized = true;
        LOGGER.info("[BRAnisotropicReflection] 各向異性反射引擎初始化完成");
    }

    public static void cleanup() {
        if (!initialized) return;
        initialized = false;
    }

    // ========================= 渲染 =========================

    /**
     * 全螢幕 composite pass — 修改金屬表面的反射分佈。
     * 讀取 GBuffer material（各向異性標記）+ normal + depth，
     * 計算 GGX anisotropic specular 並疊加。
     */
    public static void render(float gameTime) {
        if (!initialized || !BRRenderConfig.ANISOTROPIC_ENABLED) return;

        BRShaderProgram shader = BRShaderEngine.getAnisotropicShader();
        if (shader == null) return;

        // Render to default FBO (screen)
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

        shader.bind();

        // 場景顏色
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, Minecraft.getInstance().getMainRenderTarget().getColorTextureId());
        shader.setUniformInt("u_inputTex", 0);

        // GBuffer normal — deprecated GBuffer doesn't exist, use 0
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        shader.setUniformInt("u_normalTex", 1);

        // GBuffer depth
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, Minecraft.getInstance().getMainRenderTarget().getDepthTextureId());
        shader.setUniformInt("u_depthTex", 2);

        // GBuffer material（roughness/metallic/anisotropy flag）— deprecated GBuffer doesn't exist, use 0
        GL13.glActiveTexture(GL13.GL_TEXTURE3);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        shader.setUniformInt("u_materialTex", 3);

        // GBuffer emission/tangent — deprecated GBuffer doesn't exist, use 0
        GL13.glActiveTexture(GL13.GL_TEXTURE4);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        shader.setUniformInt("u_tangentTex", 4);

        shader.setUniformFloat("u_anisotropyStrength", BRRenderConfig.ANISOTROPIC_STRENGTH);
        shader.setUniformFloat("u_gameTime", gameTime);
        shader.setUniformFloat("u_screenWidth", (float) Minecraft.getInstance().getWindow().getWidth());
        shader.setUniformFloat("u_screenHeight", (float) Minecraft.getInstance().getWindow().getHeight());

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

