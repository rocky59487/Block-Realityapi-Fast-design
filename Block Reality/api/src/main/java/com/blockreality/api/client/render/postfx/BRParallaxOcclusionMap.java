package com.blockreality.api.client.render.postfx;

import com.blockreality.api.client.render.BRRenderConfig;
import com.blockreality.api.client.render.shader.BRShaderEngine;
import com.blockreality.api.client.render.shader.BRShaderProgram;
import com.blockreality.api.client.render.pipeline.BRFramebufferManager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 視差遮蔽映射引擎（Parallax Occlusion Mapping — POM）。
 *
 * 技術架構：
 *   - Screen-space POM composite pass（後處理風格，非幾何置換）
 *   - 步進式光線行進（16 步基礎 + 二分精修 4 步）
 *   - 高度圖從 GBuffer material 的 height 通道讀取
 *   - 自遮蔽（self-shadowing）計算增加深度感
 *   - 視角越平越多步數（自適應步進）
 *   - LOD 漸隱：距離遠時衰減 POM 效果（避免遠處 aliasing）
 *
 * 適用方塊：
 *   - 石磚（凹凸磚縫）
 *   - 鵝卵石（不規則凹凸）
 *   - 深板岩（層紋 parallax）
 *   - 木板（木紋凸起）
 *   - 沙子/礫石（顆粒感）
 *
 * 參考：
 *   - "Steep Parallax Mapping" (McGuire & McGuire 2005)
 *   - "Parallax Occlusion Mapping" (Tatarchuk 2006, GDC)
 *   - labPBR height-map POM spec
 *
 * @author Block Reality Team
 * @version 1.0
 */
@OnlyIn(Dist.CLIENT)
public class BRParallaxOcclusionMap {

    private static final Logger LOGGER = LoggerFactory.getLogger(BRParallaxOcclusionMap.class);

    private static boolean initialized = false;

    // ========================= 初始化 =========================

    public static void init() {
        if (initialized) return;
        initialized = true;
        LOGGER.info("[BRParallaxOcclusionMap] 視差遮蔽映射初始化完成");
    }

    public static void cleanup() {
        if (!initialized) return;
        initialized = false;
    }

    // ========================= 渲染 =========================

    /**
     * 全螢幕 composite pass — POM 高度位移 + 自遮蔽。
     * 讀取 GBuffer 深度 + material(height) + normal，
     * 在螢幕空間對每像素做光線行進修正 UV。
     */
    public static void render(float gameTime) {
        if (!initialized || !BRRenderConfig.POM_ENABLED) return;

        BRShaderProgram shader = BRShaderEngine.getPOMShader();
        if (shader == null) return;

        int writeFbo = BRFramebufferManager.getCompositeWriteFbo();
        int readTex = BRFramebufferManager.getCompositeReadTex();

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, writeFbo);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

        shader.bind();

        // 場景顏色
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, readTex);
        shader.setUniformInt("u_inputTex", 0);

        // GBuffer depth
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, BRFramebufferManager.getGbufferDepthTex());
        shader.setUniformInt("u_depthTex", 1);

        // GBuffer normal
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, BRFramebufferManager.getGbufferColorTex(1));
        shader.setUniformInt("u_normalTex", 2);

        // GBuffer material（height in alpha）
        GL13.glActiveTexture(GL13.GL_TEXTURE3);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, BRFramebufferManager.getGbufferColorTex(3));
        shader.setUniformInt("u_materialTex", 3);

        // GBuffer albedo（用於重採樣）
        GL13.glActiveTexture(GL13.GL_TEXTURE4);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, BRFramebufferManager.getGbufferColorTex(2));
        shader.setUniformInt("u_albedoTex", 4);

        shader.setUniformFloat("u_pomScale", BRRenderConfig.POM_SCALE);
        shader.setUniformInt("u_pomSteps", BRRenderConfig.POM_STEPS);
        shader.setUniformInt("u_pomRefinementSteps", BRRenderConfig.POM_REFINEMENT_STEPS);
        shader.setUniformFloat("u_pomFadeDistance", BRRenderConfig.POM_FADE_DISTANCE);
        shader.setUniformFloat("u_gameTime", gameTime);
        shader.setUniformFloat("u_screenWidth", BRFramebufferManager.getScreenWidth());
        shader.setUniformFloat("u_screenHeight", BRFramebufferManager.getScreenHeight());

        renderFullScreenQuad();

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
        if (emptyVao == 0) { emptyVao = GL30.glGenVertexArrays(); }
        return emptyVao;
    }
}
