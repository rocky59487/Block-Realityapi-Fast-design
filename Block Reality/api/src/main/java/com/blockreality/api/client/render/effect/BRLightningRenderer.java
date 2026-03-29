package com.blockreality.api.client.render.effect;

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
 * 程序化閃電渲染器 — 暴風雨期間的閃電效果。
 *
 * 技術架構：
 *   - 閃電 bolt：遞迴 L-system 分支生成（GPU 端程序化）
 *   - 全螢幕白色閃光疊加（淡入瞬間 → 指數衰減）
 *   - 環境光瞬間提升（deferred lighting 讀取 lightningFlash uniform）
 *   - 閃電位置隨機偏移（螢幕空間 UV）
 *
 * 參考：
 *   - "Real-time Lightning Rendering" (GDC 2012)
 *   - BSL Shader 暴風雨模組
 *
 * @author Block Reality Team
 * @version 1.0
 */
@OnlyIn(Dist.CLIENT)
public class BRLightningRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger(BRLightningRenderer.class);

    // ─── 閃電 bolt 狀態 ───
    private static final int MAX_BOLTS = 3;

    /** 每道 bolt 的觸發時間（用於 shader 計算衰減） */
    private static float[] boltTriggerTime = new float[MAX_BOLTS];

    /** 每道 bolt 的螢幕空間 X 偏移（-1~1） */
    private static float[] boltOffsetX = new float[MAX_BOLTS];

    /** 每道 bolt 的螢幕空間 Y 起點（0~1，通常在上方） */
    private static float[] boltOffsetY = new float[MAX_BOLTS];

    /** 每道 bolt 的隨機種子（shader 用於分支生成） */
    private static float[] boltSeed = new float[MAX_BOLTS];

    /** 當前使用的 bolt 索引（循環） */
    private static int nextBoltIdx = 0;

    /** 最近一次觸發時間 */
    private static float lastTriggerTime = -100.0f;

    private static boolean initialized = false;

    // ========================= 初始化 =========================

    public static void init() {
        if (initialized) return;

        for (int i = 0; i < MAX_BOLTS; i++) {
            boltTriggerTime[i] = -100.0f;
            boltOffsetX[i] = 0.0f;
            boltOffsetY[i] = 0.0f;
            boltSeed[i] = 0.0f;
        }
        nextBoltIdx = 0;
        lastTriggerTime = -100.0f;

        initialized = true;
        LOGGER.info("[BRLightningRenderer] 閃電渲染器初始化完成");
    }

    public static void cleanup() {
        if (!initialized) return;
        if (emptyVao != 0) { GL30.glDeleteVertexArrays(emptyVao); emptyVao = 0; }
        initialized = false;
    }

    // ========================= 觸發 =========================

    /**
     * 觸發一道新閃電 bolt。
     * @param gameTime 當前遊戲時間
     */
    public static void triggerBolt(float gameTime) {
        int idx = nextBoltIdx;
        boltTriggerTime[idx] = gameTime;
        boltOffsetX[idx] = (float)(Math.random() * 1.6 - 0.8); // -0.8 ~ 0.8
        boltOffsetY[idx] = 0.8f + (float)(Math.random() * 0.15f); // 0.8 ~ 0.95
        boltSeed[idx] = (float)(Math.random() * 1000.0);
        lastTriggerTime = gameTime;
        nextBoltIdx = (nextBoltIdx + 1) % MAX_BOLTS;
    }

    // ========================= 每幀更新 =========================

    public static void tick(float deltaTime, float gameTime) {
        // 閃電狀態由 shader 的 time-since-trigger 衰減控制
        // CPU 端不需額外更新
    }

    // ========================= 渲染 =========================

    /**
     * 全螢幕 composite pass — 渲染閃電 bolt + 螢幕閃光。
     */
    public static void render(float gameTime) {
        if (!initialized) return;

        // 只在最近 2 秒內有閃電時才渲染
        if (gameTime - lastTriggerTime > 2.0f) return;

        BRShaderProgram shader = BRShaderEngine.getLightningShader();
        if (shader == null) return;

        int writeFbo = BRFramebufferManager.getCompositeWriteFbo();
        int readTex = BRFramebufferManager.getCompositeReadTex();

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, writeFbo);

        shader.bind();

        // 場景顏色
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, readTex);
        shader.setUniformInt("u_inputTex", 0);

        // 閃電參數
        shader.setUniformFloat("u_gameTime", gameTime);
        shader.setUniformFloat("u_flashIntensity", BRWeatherEngine.getLightningFlash());
        shader.setUniformFloat("u_screenWidth", BRFramebufferManager.getScreenWidth());
        shader.setUniformFloat("u_screenHeight", BRFramebufferManager.getScreenHeight());

        // 上傳 bolt 資料（最多 3 道）
        for (int i = 0; i < MAX_BOLTS; i++) {
            String prefix = "u_bolt[" + i + "].";
            shader.setUniformFloat(prefix + "triggerTime", boltTriggerTime[i]);
            shader.setUniformFloat(prefix + "offsetX", boltOffsetX[i]);
            shader.setUniformFloat(prefix + "offsetY", boltOffsetY[i]);
            shader.setUniformFloat(prefix + "seed", boltSeed[i]);
        }

        // Additive blend（閃光疊加）
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE);

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
