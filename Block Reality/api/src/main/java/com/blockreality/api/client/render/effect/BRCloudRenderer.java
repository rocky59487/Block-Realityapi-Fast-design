package com.blockreality.api.client.render.effect;

import com.blockreality.api.client.render.BRRenderConfig;
import com.blockreality.api.client.render.shader.BRShaderEngine;
import com.blockreality.api.client.render.shader.BRShaderProgram;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;

/**
 * 程序化體積雲渲染器 — 無需外部紋理的全程序化雲層。
 *
 * 技術融合：
 *   - Guerrilla Games (Horizon Zero Dawn): "Real-Time Volumetric Cloudscapes"
 *     - Perlin-Worley noise 密度場
 *     - Ray Marching + 光線散射
 *   - Shadertoy/IQ: "Clouds" 程序化噪聲技巧
 *   - Iris/BSL: 雲層 composite pass 整合
 *
 * 設計要點：
 *   - 雲層在 Y=128~256 的球殼區間
 *   - 主 march 64 步 + 光線 march 6 步
 *   - 密度 = FBM(Perlin) - Worley erosion
 *   - 散射：Henyey-Greenstein 雙瓣（銀邊 + 暗核）
 *   - 時間推移：UV 偏移模擬風場
 *   - 天氣控制：coverage / density / type 三參數
 *   - 低解析度預渲染 + 雙線性上採樣（效能友好）
 */
@OnlyIn(Dist.CLIENT)
public final class BRCloudRenderer {
    private BRCloudRenderer() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("BR-Cloud");

    // ─── 雲層幾何參數 ────────────────────────────────────
    /** 雲層底部高度（方塊） */
    private static final float CLOUD_BOTTOM = 192.0f;
    /** 雲層厚度（方塊） */
    private static final float CLOUD_THICKNESS = 96.0f;
    /** 雲層頂部 */
    private static final float CLOUD_TOP = CLOUD_BOTTOM + CLOUD_THICKNESS;

    // ─── 天氣參數（可動態調整）─────────────────────────────
    /** 雲量覆蓋率（0~1, 0=晴空, 1=全陰） */
    private static float coverage = 0.45f;
    /** 雲密度乘數 */
    private static float densityMultiplier = 1.0f;
    /** 雲類型（0=層雲, 1=積雲） */
    private static float cloudType = 0.6f;
    /** 風速（方塊/秒） */
    private static float windSpeedX = 2.0f;
    private static float windSpeedZ = 1.0f;

    // ─── GL 資源 ────────────────────────────────────────────
    /** 低解析度 FBO（1/4 解析度預渲染） */
    private static int cloudFbo;
    private static int cloudColorTex;
    private static int cloudFboWidth;
    private static int cloudFboHeight;

    /** 全螢幕 quad VAO */
    private static int quadVao;
    private static int quadVbo;

    /** 時間累積（風場偏移用） */
    private static float accumulatedTime = 0.0f;

    private static boolean initialized = false;

    // ═══════════════════════════════════════════════════════
    //  初始化 / 清除
    // ═══════════════════════════════════════════════════════

    public static void init(int screenWidth, int screenHeight) {
        if (initialized) return;

        // 1/4 解析度 FBO（效能友好）
        cloudFboWidth = Math.max(1, screenWidth / 4);
        cloudFboHeight = Math.max(1, screenHeight / 4);

        // 建立雲 FBO
        cloudFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, cloudFbo);

        cloudColorTex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, cloudColorTex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F,
            cloudFboWidth, cloudFboHeight, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (FloatBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);

        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
            GL11.GL_TEXTURE_2D, cloudColorTex, 0);

        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            LOGGER.error("Cloud FBO 不完整: 0x{}", Integer.toHexString(status));
        }

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

        // 全螢幕 quad
        initQuad();

        initialized = true;
        LOGGER.info("BRCloudRenderer 初始化完成 — FBO {}x{}", cloudFboWidth, cloudFboHeight);
    }

    private static void initQuad() {
        float[] quadVertices = {
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f,  1.0f,
             1.0f, -1.0f,
             1.0f,  1.0f,
            -1.0f,  1.0f
        };

        quadVao = GL30.glGenVertexArrays();
        quadVbo = GL15.glGenBuffers();

        GL30.glBindVertexArray(quadVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadVbo);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buf = stack.mallocFloat(quadVertices.length);
            buf.put(quadVertices).flip();
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buf, GL15.GL_STATIC_DRAW);
        }

        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 8, 0);

        GL30.glBindVertexArray(0);
    }

    public static void cleanup() {
        if (cloudFbo != 0) { GL30.glDeleteFramebuffers(cloudFbo); cloudFbo = 0; }
        if (cloudColorTex != 0) { GL11.glDeleteTextures(cloudColorTex); cloudColorTex = 0; }
        if (quadVao != 0) { GL30.glDeleteVertexArrays(quadVao); quadVao = 0; }
        if (quadVbo != 0) { GL15.glDeleteBuffers(quadVbo); quadVbo = 0; }
        initialized = false;
    }

    public static void onResize(int width, int height) {
        if (!initialized) return;
        cloudFboWidth = Math.max(1, width / 4);
        cloudFboHeight = Math.max(1, height / 4);

        // 重建雲紋理
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, cloudColorTex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F,
            cloudFboWidth, cloudFboHeight, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (FloatBuffer) null);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    // ═══════════════════════════════════════════════════════
    //  每幀更新
    // ═══════════════════════════════════════════════════════

    /**
     * 更新時間和天氣參數。
     *
     * @param deltaMs 幀間隔（毫秒）
     */
    public static void tick(float deltaMs) {
        accumulatedTime += deltaMs / 1000.0f;
    }

    /**
     * 設定天氣參數。
     */
    public static void setWeather(float newCoverage, float newDensity, float newType) {
        coverage = Math.max(0.0f, Math.min(1.0f, newCoverage));
        densityMultiplier = Math.max(0.1f, newDensity);
        cloudType = Math.max(0.0f, Math.min(1.0f, newType));
    }

    // ═══════════════════════════════════════════════════════
    //  渲染
    // ═══════════════════════════════════════════════════════

    /**
     * 渲染雲層到低解析度 FBO。
     * 在 composite chain 中，在體積光之後、bloom 之前呼叫。
     *
     * @param invProjView 逆投影-視圖矩陣（重建射線方向）
     * @param cameraPos   相機世界位置
     * @param sunDir      太陽方向
     * @param sunColor    太陽顏色
     * @param gameTime    遊戲時間
     */
    public static void renderClouds(Matrix4f invProjView, Vector3f cameraPos,
                                     Vector3f sunDir, Vector3f sunColor,
                                     float gameTime) {
        if (!initialized) return;

        BRShaderProgram shader = BRShaderEngine.getCloudShader();
        if (shader == null) return;

        // Pass 1: 在低解析度 FBO 渲染雲 ray march
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, cloudFbo);
        GL11.glViewport(0, 0, cloudFboWidth, cloudFboHeight);
        GL11.glClearColor(0, 0, 0, 0);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

        shader.bind();

        // 射線重建
        shader.setUniformMat4("u_invProjView", invProjView);
        shader.setUniformVec3("u_cameraPos", cameraPos.x, cameraPos.y, cameraPos.z);

        // 光源
        shader.setUniformVec3("u_sunDir", sunDir.x, sunDir.y, sunDir.z);
        shader.setUniformVec3("u_sunColor", sunColor.x, sunColor.y, sunColor.z);

        // 雲層參數
        shader.setUniformFloat("u_cloudBottom", CLOUD_BOTTOM);
        shader.setUniformFloat("u_cloudTop", CLOUD_TOP);
        shader.setUniformFloat("u_cloudThickness", CLOUD_THICKNESS);
        shader.setUniformFloat("u_coverage", coverage);
        shader.setUniformFloat("u_densityMul", densityMultiplier);
        shader.setUniformFloat("u_cloudType", cloudType);
        shader.setUniformFloat("u_time", accumulatedTime);
        shader.setUniformFloat("u_windX", windSpeedX * accumulatedTime);
        shader.setUniformFloat("u_windZ", windSpeedZ * accumulatedTime);

        // 繪製全螢幕 quad
        GL30.glBindVertexArray(quadVao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
        GL30.glBindVertexArray(0);

        shader.unbind();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    /**
     * 將雲 FBO 結果合成到主 composite buffer。
     * Alpha 混合：雲的 alpha 通道包含密度資訊。
     */
    public static void compositeToScreen(int mainCompositeFbo, int screenWidth, int screenHeight) {
        if (!initialized) return;

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, mainCompositeFbo);
        GL11.glViewport(0, 0, screenWidth, screenHeight);

        // Save GL blend state before modifying (Minecraft compatibility)
        boolean blendWasEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
        int srcBlend = GL11.glGetInteger(GL11.GL_BLEND_SRC);
        int dstBlend = GL11.glGetInteger(GL11.GL_BLEND_DST);

        // 啟用 alpha 混合（Pre-multiplied alpha）使用 RenderSystem 以確保 Minecraft 相容性
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);

        // 使用簡單 blit shader（或 final shader）進行上採樣合成
        BRShaderProgram blitShader = BRShaderEngine.getFinalShader();
        if (blitShader != null) {
            blitShader.bind();
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, cloudColorTex);
            blitShader.setUniformInt("u_mainTex", 0);

            GL30.glBindVertexArray(quadVao);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
            GL30.glBindVertexArray(0);

            blitShader.unbind();
        }

        // Restore GL blend state to pre-render condition
        if (blendWasEnabled) {
            RenderSystem.enableBlend();
            RenderSystem.blendFunc(srcBlend, dstBlend);
        } else {
            RenderSystem.disableBlend();
        }

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    // ─── Accessors ──────────────────────────────────────────

    public static int getCloudTexture() { return cloudColorTex; }
    public static float getCoverage() { return coverage; }
    public static float getCloudType() { return cloudType; }
    public static boolean isInitialized() { return initialized; }
}
