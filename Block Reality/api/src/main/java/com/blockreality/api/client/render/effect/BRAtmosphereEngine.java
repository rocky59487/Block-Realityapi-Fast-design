package com.blockreality.api.client.render.effect;

import com.blockreality.api.client.render.BRRenderConfig;
import com.blockreality.api.client.render.shader.BRShaderEngine;
import com.blockreality.api.client.render.shader.BRShaderProgram;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import org.lwjgl.system.MemoryStack;

/**
 * 大氣渲染引擎 — Rayleigh / Mie 散射天空模型。
 *
 * 基於 Nishita 1993 / Bruneton 2008 的單次散射簡化模型，
 * 針對 Minecraft 即時渲染優化。
 *
 * 特性：
 * - Rayleigh 散射（藍天）+ Mie 散射（日落紅/金色光暈）
 * - 動態太陽位置（跟隨 Minecraft dayTime）
 * - 大氣密度隨高度指數衰減
 * - 太陽光盤 + 光暈
 * - 夜空星空 + 月亮（程序化雜訊）
 * - 霧色與大氣同步（遠處方塊自然融入天空）
 * - 全螢幕穹頂 quad 繪製
 *
 * @author Block Reality Team
 * @version 1.0
 */
@OnlyIn(Dist.CLIENT)
public class BRAtmosphereEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(BRAtmosphereEngine.class);

    // ========================= 大氣物理常數 =========================

    /** 地球半徑（km） */
    private static final float EARTH_RADIUS = 6371.0f;

    /** 大氣頂端高度（km） */
    private static final float ATMOSPHERE_HEIGHT = 100.0f;

    /** Rayleigh 散射係數（波長相關 — RGB 對應 680/550/440 nm） */
    private static final float[] RAYLEIGH_COEFF = { 5.5e-6f, 13.0e-6f, 22.4e-6f };

    /** Rayleigh 標高（km） */
    private static final float RAYLEIGH_SCALE_HEIGHT = 8.0f;

    /** Mie 散射係數 */
    private static final float MIE_COEFF = 21e-6f;

    /** Mie 標高（km） */
    private static final float MIE_SCALE_HEIGHT = 1.2f;

    /** Mie 各向異性因子（Henyey-Greenstein g 值） */
    private static final float MIE_G = 0.76f;

    /** 太陽強度 */
    private static final float SUN_INTENSITY = 20.0f;

    // ========================= GL 資源 =========================

    private static int skyVAO = 0;
    private static int skyVBO = 0;
    private static boolean initialized = false;

    // ========================= 狀態 =========================

    /** 目前太陽方向（歸一化） */
    private static final Vector3f sunDirection = new Vector3f(0.0f, 1.0f, 0.0f);

    /** 目前太陽顏色（經大氣衰減後） */
    private static final Vector3f sunColor = new Vector3f(1.0f, 1.0f, 1.0f);

    /** 目前天頂顏色（用於霧色） */
    private static final Vector3f zenithColor = new Vector3f(0.3f, 0.5f, 0.9f);

    /** 目前地平線顏色（用於霧色漸變） */
    private static final Vector3f horizonColor = new Vector3f(0.7f, 0.8f, 1.0f);

    /** 大氣霧色（供其他系統查詢） */
    private static final Vector3f fogColor = new Vector3f(0.7f, 0.8f, 1.0f);

    /** 日夜混合因子（0=夜, 1=日） */
    private static float dayFactor = 1.0f;

    // ========================= 初始化 =========================

    public static void init() {
        if (initialized) return;

        // 建立全螢幕 quad VAO（天穹繪製用）
        float[] quadVertices = {
            -1.0f, -1.0f, 0.0f, 0.0f,
             1.0f, -1.0f, 1.0f, 0.0f,
             1.0f,  1.0f, 1.0f, 1.0f,
            -1.0f, -1.0f, 0.0f, 0.0f,
             1.0f,  1.0f, 1.0f, 1.0f,
            -1.0f,  1.0f, 0.0f, 1.0f,
        };

        skyVAO = GL30.glGenVertexArrays();
        skyVBO = GL15.glGenBuffers();
        GL30.glBindVertexArray(skyVAO);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, skyVBO);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buf = stack.mallocFloat(quadVertices.length);
            buf.put(quadVertices).flip();
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buf, GL15.GL_STATIC_DRAW);
        }

        // layout(location=0) = position (vec2)
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 16, 0);

        // layout(location=1) = texCoord (vec2)
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 16, 8);

        GL30.glBindVertexArray(0);

        initialized = true;
        LOGGER.info("BRAtmosphereEngine 初始化完成");
    }

    public static void cleanup() {
        if (skyVAO != 0) { GL30.glDeleteVertexArrays(skyVAO); skyVAO = 0; }
        if (skyVBO != 0) { GL15.glDeleteBuffers(skyVBO); skyVBO = 0; }
        initialized = false;
        LOGGER.info("BRAtmosphereEngine 已清理");
    }

    // ========================= 太陽位置更新 =========================

    /**
     * 每幀更新太陽位置和大氣參數。
     *
     * @param gameTime Minecraft gameTime（0~24000 tick）
     */
    public static void updateSunPosition(float gameTime) {
        // Minecraft 時間轉太陽角度
        // 6000 tick = 正午（太陽在天頂），0/24000 = 午夜
        float dayProgress = (gameTime % 24000f) / 24000f;
        float sunAngle = dayProgress * (float)(Math.PI * 2.0) - (float)(Math.PI * 0.5);

        // 太陽方向（繞 X 軸旋轉的簡化模型）
        sunDirection.set(0.0f, (float) Math.sin(sunAngle), (float) Math.cos(sunAngle));
        sunDirection.normalize();

        // 日夜因子（太陽高度角）
        dayFactor = Math.max(0.0f, Math.min(1.0f, sunDirection.y * 3.0f + 0.5f));

        // 太陽顏色（低角度時偏紅 — Mie 散射主導）
        float sunHeight = Math.max(0.0f, sunDirection.y);
        float reddening = 1.0f - (float) Math.pow(sunHeight, 0.5);
        sunColor.set(
            1.0f,
            1.0f - reddening * 0.4f,
            1.0f - reddening * 0.7f
        );

        // 天頂顏色
        zenithColor.set(
            0.15f + 0.15f * dayFactor,
            0.3f + 0.3f * dayFactor,
            0.5f + 0.4f * dayFactor
        );

        // 地平線顏色（日落時金橙色）
        float sunsetFactor = (float) Math.pow(Math.max(0.0, 1.0 - Math.abs(sunDirection.y) * 4.0), 2.0);
        horizonColor.set(
            0.4f + 0.4f * dayFactor + 0.3f * sunsetFactor,
            0.4f + 0.35f * dayFactor + 0.15f * sunsetFactor,
            0.45f + 0.35f * dayFactor - 0.1f * sunsetFactor
        );

        // 霧色（天頂和地平線的混合）
        fogColor.set(
            (zenithColor.x + horizonColor.x * 2.0f) / 3.0f,
            (zenithColor.y + horizonColor.y * 2.0f) / 3.0f,
            (zenithColor.z + horizonColor.z * 2.0f) / 3.0f
        );
    }

    // ========================= 渲染 =========================

    /**
     * 繪製天穹。在 deferred lighting 之後、final blit 之前呼叫。
     * 只在深度 = 1.0（天空）的像素上繪製。
     */
    public static void renderSkyDome(Matrix4f invProjView, float gameTime) {
        if (!initialized) return;

        BRShaderProgram shader = BRShaderEngine.getAtmosphereShader();
        if (shader == null) return;

        shader.bind();

        // Uniforms
        shader.setUniformVec3("u_sunDir", sunDirection.x, sunDirection.y, sunDirection.z);
        shader.setUniformVec3("u_sunColor", sunColor.x * SUN_INTENSITY, sunColor.y * SUN_INTENSITY, sunColor.z * SUN_INTENSITY);
        shader.setUniformFloat("u_earthRadius", EARTH_RADIUS);
        shader.setUniformFloat("u_atmosphereHeight", ATMOSPHERE_HEIGHT);
        shader.setUniformVec3("u_rayleighCoeff", RAYLEIGH_COEFF[0], RAYLEIGH_COEFF[1], RAYLEIGH_COEFF[2]);
        shader.setUniformFloat("u_rayleighScale", RAYLEIGH_SCALE_HEIGHT);
        shader.setUniformFloat("u_mieCoeff", MIE_COEFF);
        shader.setUniformFloat("u_mieScale", MIE_SCALE_HEIGHT);
        shader.setUniformFloat("u_mieG", MIE_G);
        shader.setUniformFloat("u_dayFactor", dayFactor);
        shader.setUniformFloat("u_time", gameTime);

        shader.setUniformMat4("u_invProjView", invProjView);

        // 深度測試：只在天空像素繪製
        GL11.glDepthFunc(GL11.GL_EQUAL);
        GL11.glDepthMask(false);

        GL30.glBindVertexArray(skyVAO);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
        GL30.glBindVertexArray(0);

        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glDepthMask(true);

        shader.unbind();
    }

    // ========================= 公共查詢 =========================

    /** 取得目前太陽方向 */
    public static Vector3f getSunDirection() { return new Vector3f(sunDirection); }

    /** 取得目前太陽顏色（已衰減） */
    public static Vector3f getSunColor() { return new Vector3f(sunColor); }

    /** 取得目前大氣霧色（供 LOD / 遠景渲染用） */
    public static Vector3f getFogColor() { return new Vector3f(fogColor); }

    /** 取得天頂顏色 */
    public static Vector3f getZenithColor() { return new Vector3f(zenithColor); }

    /** 取得日夜因子 (0=夜, 1=日) */
    public static float getDayFactor() { return dayFactor; }

    /** 取得目前太陽高度角（弧度） */
    public static float getSunElevation() { return (float) Math.asin(sunDirection.y); }

    public static boolean isInitialized() { return initialized; }
}
