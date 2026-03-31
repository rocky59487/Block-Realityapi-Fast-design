package com.blockreality.api.client.render.effect;

import com.blockreality.api.client.render.BRRenderConfig;
import com.blockreality.api.client.render.shader.BRShaderEngine;
import com.blockreality.api.client.render.shader.BRShaderProgram;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
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
 * 程序化鏡頭光暈系統 — 太陽/強光源觸發的鏡頭折射效果。
 *
 * 技術融合：
 *   - John Chapman (2013): "Pseudo Lens Flare" 後處理技術
 *   - DICE/Frostbite: "Physically Based Lens Flare"
 *   - Iris/BSL: composite lens flare pass
 *
 * 設計要點：
 *   - 程序化生成（無外部紋理）
 *   - 太陽遮蔽偵測（depth buffer occlusion query）
 *   - 多元素組合：光環(halo)、鬼影(ghosts)、星芒(starburst)、漫射光暈(bloom ring)
 *   - Additive 混合到主 composite buffer
 *   - 平滑淡入淡出（避免閃爍）
 */
@OnlyIn(Dist.CLIENT)
public final class BRLensFlare {
    private BRLensFlare() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("BR-LensFlare");

    // ─── 常數 ──────────────────────────────────────────────
    private static final int MAX_GHOSTS = 5;
    private static final float GHOST_DISPERSAL = 0.3f;
    private static final float HALO_RADIUS = 0.4f;

    // ─── 狀態 ──────────────────────────────────────────────
    /** 太陽螢幕空間座標（-1~1, 超出螢幕時也計算） */
    private static float sunScreenX = 0.0f;
    private static float sunScreenY = 0.0f;
    /** 太陽是否在螢幕內 */
    private static boolean sunOnScreen = false;
    /** 太陽遮蔽度（0=完全遮蔽, 1=完全可見） */
    private static float sunVisibility = 0.0f;
    /** 平滑遮蔽度（防閃爍） */
    private static float smoothVisibility = 0.0f;

    // ─── GL 資源 ────────────────────────────────────────────
    private static int flareVao;
    private static int flareVbo;

    // ─── 遮蔽查詢 ─────────────────────────────────────────────
    /**
     * 太陽遮蔽偵測使用 GBuffer 深度採樣。
     * 在太陽螢幕位置讀取小區域（5×5）的深度值，
     * depth > 0.999 表示天空（無遮擋），否則為被幾何遮擋。
     * 多點採樣提供平滑的部分遮蔽過渡（如太陽被建築物邊緣半遮時）。
     */
    private static final int OCCLUSION_SAMPLE_SIZE = 5; // 5×5 像素採樣區域
    private static final float OCCLUSION_DEPTH_THRESHOLD = 0.999f;

    private static boolean initialized = false;

    // ═══════════════════════════════════════════════════════
    //  初始化 / 清除
    // ═══════════════════════════════════════════════════════

    public static void init() {
        if (initialized) return;

        // 簡單 quad VAO 用於光暈元素
        flareVao = GL30.glGenVertexArrays();
        flareVbo = GL15.glGenBuffers();

        GL30.glBindVertexArray(flareVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, flareVbo);

        float[] quadData = {
            -1.0f, -1.0f, 0.0f, 0.0f,
             1.0f, -1.0f, 1.0f, 0.0f,
            -1.0f,  1.0f, 0.0f, 1.0f,
             1.0f, -1.0f, 1.0f, 0.0f,
             1.0f,  1.0f, 1.0f, 1.0f,
            -1.0f,  1.0f, 0.0f, 1.0f
        };

        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buf = stack.mallocFloat(quadData.length);
            buf.put(quadData).flip();
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buf, GL15.GL_STATIC_DRAW);
        }

        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 16, 0);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 16, 8);

        GL30.glBindVertexArray(0);

        initialized = true;
        LOGGER.info("BRLensFlare 初始化完成");
    }

    public static void cleanup() {
        if (flareVao != 0) { GL30.glDeleteVertexArrays(flareVao); flareVao = 0; }
        if (flareVbo != 0) { GL15.glDeleteBuffers(flareVbo); flareVbo = 0; }
        initialized = false;
    }

    // ═══════════════════════════════════════════════════════
    //  每幀更新
    // ═══════════════════════════════════════════════════════

    /**
     * 更新太陽螢幕位置和遮蔽狀態。
     *
     * @param sunDir     太陽世界方向
     * @param viewProj   當前幀 view-projection 矩陣
     */
    public static void updateSunPosition(Vector3f sunDir, Matrix4f viewProj) {
        // 投影太陽方向到螢幕空間
        // 太陽是方向光，使用遠處虛擬位置
        Vector4f sunClip = new Vector4f(sunDir.x * 1000, sunDir.y * 1000, sunDir.z * 1000, 1.0f);
        viewProj.transform(sunClip);

        if (sunClip.w > 0.001f) {
            sunScreenX = sunClip.x / sunClip.w;
            sunScreenY = sunClip.y / sunClip.w;
            sunOnScreen = Math.abs(sunScreenX) < 1.2f && Math.abs(sunScreenY) < 1.2f;
        } else {
            sunOnScreen = false;
        }
    }

    /**
     * 檢測太陽遮蔽（深度測試）。
     * 採樣太陽螢幕位置的深度值，判斷是否被遮擋。
     */
    public static void updateOcclusion() {
        if (!sunOnScreen) {
            sunVisibility = 0.0f;
        } else {
            // ── 深度採樣遮蔽偵測（Iris/BSL 風格）──
            // 從 main render target depth buffer 讀取太陽螢幕位置的深度值
            // depth ≈ 1.0 = 天空（太陽可見），depth < 1.0 = 被幾何遮擋
            // 使用 5×5 像素多點採樣，提供平滑的部分遮蔽過渡

            int screenW = Minecraft.getInstance().getWindow().getWidth();
            int screenH = Minecraft.getInstance().getWindow().getHeight();

            // NDC [-1,1] → 像素座標 [0, screen]
            int sunPixelX = (int) ((sunScreenX * 0.5f + 0.5f) * screenW);
            int sunPixelY = (int) ((sunScreenY * 0.5f + 0.5f) * screenH);

            // 計算採樣區域（邊界裁切）
            int halfSize = OCCLUSION_SAMPLE_SIZE / 2;
            int startX = Math.max(0, sunPixelX - halfSize);
            int startY = Math.max(0, sunPixelY - halfSize);
            int endX = Math.min(screenW, sunPixelX + halfSize + 1);
            int endY = Math.min(screenH, sunPixelY + halfSize + 1);
            int w = endX - startX;
            int h = endY - startY;

            if (w > 0 && h > 0) {
                // 綁定主渲染目標 FBO 讀取其深度附件
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,
                    Minecraft.getInstance().getMainRenderTarget().frameBufferId);

                try (MemoryStack stack = MemoryStack.stackPush()) {
                    FloatBuffer depthBuf = stack.mallocFloat(w * h);
                    GL11.glReadPixels(startX, startY, w, h,
                        GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depthBuf);

                    // 統計天空像素（depth > threshold = 無遮擋）
                    int skyPixels = 0;
                    int totalPixels = w * h;
                    for (int i = 0; i < totalPixels; i++) {
                        if (depthBuf.get(i) > OCCLUSION_DEPTH_THRESHOLD) {
                            skyPixels++;
                        }
                    }
                    sunVisibility = (float) skyPixels / totalPixels;
                }

                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
            } else {
                sunVisibility = 0.0f;
            }
        }

        // 平滑過渡（防閃爍 — exponential moving average）
        float smoothRate = 0.05f;
        smoothVisibility += (sunVisibility - smoothVisibility) * smoothRate;
    }

    // ═══════════════════════════════════════════════════════
    //  渲染
    // ═══════════════════════════════════════════════════════

    /**
     * 渲染鏡頭光暈。
     * 使用 additive blend 疊加到主 composite buffer。
     */
    public static void render(float gameTime) {
        if (!initialized || smoothVisibility < 0.01f) return;

        BRShaderProgram shader = BRShaderEngine.getLensFlareShader();
        if (shader == null) return;

        // Additive blend
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE);
        GL11.glDisable(GL11.GL_DEPTH_TEST);

        shader.bind();

        // 太陽螢幕位置
        shader.setUniformVec2("u_sunPos", sunScreenX, sunScreenY);
        shader.setUniformFloat("u_visibility", smoothVisibility);
        shader.setUniformFloat("u_time", gameTime);
        shader.setUniformInt("u_ghostCount", MAX_GHOSTS);
        shader.setUniformFloat("u_ghostDispersal", GHOST_DISPERSAL);
        shader.setUniformFloat("u_haloRadius", HALO_RADIUS);
        shader.setUniformFloat("u_intensity", BRRenderConfig.LENS_FLARE_INTENSITY);

        // 繪製全螢幕 quad
        GL30.glBindVertexArray(flareVao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
        GL30.glBindVertexArray(0);

        shader.unbind();

        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    // ─── Accessors ──────────────────────────────────────────

    public static float getSunVisibility() { return smoothVisibility; }
    public static boolean isSunOnScreen() { return sunOnScreen; }
    public static boolean isInitialized() { return initialized; }
}

