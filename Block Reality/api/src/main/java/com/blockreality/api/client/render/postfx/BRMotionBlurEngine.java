package com.blockreality.api.client.render.postfx;

import com.blockreality.api.client.render.BRRenderConfig;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;

/**
 * Per-Pixel Velocity Buffer 引擎 — 動態模糊的基礎設施。
 *
 * 技術融合：
 *   - Guerrilla Games: "The Rendering of Killzone 2" velocity buffer 方案
 *   - Jimenez 2014: "Next Generation Post Processing in Call of Duty"
 *   - Iris/BSL: 運動向量 pass
 *
 * 設計要點：
 *   - 獨立 FBO 存放 per-pixel 運動向量（RG16F — x/y 速度）
 *   - 使用 current vs previous frame viewProj 矩陣計算 screen-space velocity
 *   - 支援相機運動 + 物件運動
 *   - Cinematic shader 消費 velocity buffer 進行方向性模糊
 *   - 可獨立開關，不影響其他 pass
 */
@OnlyIn(Dist.CLIENT)
public final class BRMotionBlurEngine {
    private BRMotionBlurEngine() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("BR-MotionBlur");

    // ─── GL 資源 ────────────────────────────────────────────
    /** Velocity FBO + RG16F 紋理 */
    private static int velocityFbo;
    private static int velocityTex;
    private static int fboWidth;
    private static int fboHeight;

    /** 上一幀 view-projection 矩陣 */
    private static final Matrix4f prevViewProj = new Matrix4f();
    /** 當前幀 view-projection 矩陣 */
    private static final Matrix4f currViewProj = new Matrix4f();
    /** 逆當前 viewProj（用於重建世界位置） */
    private static final Matrix4f invCurrViewProj = new Matrix4f();

    private static boolean firstFrame = true;
    private static boolean initialized = false;

    // ═══════════════════════════════════════════════════════
    //  初始化 / 清除
    // ═══════════════════════════════════════════════════════

    public static void init(int width, int height) {
        if (initialized) return;

        fboWidth = width;
        fboHeight = height;

        // Velocity FBO（RG16F — 每像素 x/y 速度向量）
        velocityFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, velocityFbo);

        velocityTex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, velocityTex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RG16F,
            width, height, 0, GL30.GL_RG, GL11.GL_FLOAT, (FloatBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);

        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
            GL11.GL_TEXTURE_2D, velocityTex, 0);

        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            LOGGER.error("Velocity FBO 不完整: 0x{}", Integer.toHexString(status));
        }

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        initialized = true;
        LOGGER.info("BRMotionBlurEngine 初始化完成 — Velocity Buffer {}x{}", width, height);
    }

    public static void cleanup() {
        if (velocityFbo != 0) { GL30.glDeleteFramebuffers(velocityFbo); velocityFbo = 0; }
        if (velocityTex != 0) { GL11.glDeleteTextures(velocityTex); velocityTex = 0; }
        initialized = false;
        firstFrame = true;
    }

    public static void onResize(int width, int height) {
        if (!initialized) return;
        fboWidth = width;
        fboHeight = height;

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, velocityTex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RG16F,
            width, height, 0, GL30.GL_RG, GL11.GL_FLOAT, (FloatBuffer) null);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    // ═══════════════════════════════════════════════════════
    //  每幀更新
    // ═══════════════════════════════════════════════════════

    /**
     * 更新矩陣並生成 velocity buffer。
     * 在 GBuffer pass 之後、composite chain 之前呼叫。
     *
     * @param viewMatrix 當前幀 view 矩陣
     * @param projMatrix 當前幀 projection 矩陣
     */
    public static void updateAndRender(Matrix4f viewMatrix, Matrix4f projMatrix) {
        if (!initialized) return;

        // 儲存上一幀
        if (!firstFrame) {
            prevViewProj.set(currViewProj);
        }

        // 計算當前幀 viewProj
        projMatrix.mul(viewMatrix, currViewProj);
        currViewProj.invert(invCurrViewProj);

        if (firstFrame) {
            prevViewProj.set(currViewProj);
            firstFrame = false;
        }

        // 渲染 velocity buffer（全螢幕 pass）
        renderVelocityPass();
    }

    /**
     * 全螢幕 pass：使用 depth buffer 重建世界位置，
     * 再用 prevViewProj 投影計算 screen-space velocity。
     */
    private static void renderVelocityPass() {
        com.blockreality.api.client.render.shader.BRShaderProgram velocityShader =
            com.blockreality.api.client.render.shader.BRShaderEngine.getVelocityShader();
        if (velocityShader == null) return;

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, velocityFbo);
        GL11.glViewport(0, 0, fboWidth, fboHeight);
        GL11.glClearColor(0, 0, 0, 0);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

        velocityShader.bind();

        // 綁定 main render target 深度
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, Minecraft.getInstance().getMainRenderTarget().getDepthTextureId());
        velocityShader.setUniformInt("u_depthTex", 0);

        // 矩陣 uniforms
        velocityShader.setUniformMat4("u_invViewProj", invCurrViewProj);
        velocityShader.setUniformMat4("u_prevViewProj", prevViewProj);

        // 繪製全螢幕 quad
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);

        velocityShader.unbind();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    // ─── Accessors ──────────────────────────────────────────

    /** 取得 velocity 紋理 ID（Cinematic shader 消費） */
    public static int getVelocityTexture() { return velocityTex; }
    public static int getVelocityFbo() { return velocityFbo; }
    public static Matrix4f getPrevViewProj() { return prevViewProj; }
    public static boolean isInitialized() { return initialized; }
}

