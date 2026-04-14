package com.blockreality.api.client.render.effect;

import com.blockreality.api.client.render.BRRenderConfig;
import com.blockreality.api.client.render.shader.BRShaderEngine;
import com.blockreality.api.client.render.shader.BRShaderProgram;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 進階水體渲染器 — PBR 水面效果。
 *
 * 特性：
 * - 平面反射（Planar Reflection via 剪裁平面 + 反射 FBO）
 * - 螢幕空間折射（Screen-Space Refraction）
 * - Gerstner 波浪動畫（多頻疊加）
 * - 水下焦散（Caustics — 程序化紋理投射）
 * - 菲涅爾反射率（Schlick 近似）
 * - 深度吸收著色（Beer-Lambert — 越深越暗越綠）
 * - 泡沫邊緣（岸邊 / 物體交接處白色泡沫）
 * - 法線貼圖擾動（多層 UV 滾動）
 *
 * 水面幾何由 Minecraft 原生水方塊提供，
 * 本引擎僅替換 fragment shader 效果。
 *
 * @author Block Reality Team
 * @version 1.0
  * @deprecated Since 2.0, superseded by Vulkan RT + Voxy LOD pipeline on capable hardware.
  *             Still used as GL fallback when RT is unavailable; do not remove until
  *             a fully equivalent GL replacement is provided.
*/
@Deprecated(since = "2.0")
@OnlyIn(Dist.CLIENT)
public class BRWaterRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger(BRWaterRenderer.class);

    // ========================= 水體物理參數 =========================

    /** 水面基準 Y 座標（世界空間，預設海平面） */
    private static float waterLevel = 63.0f;

    /** Gerstner 波浪參數（最多 4 組疊加） */
    private static final float[][] WAVE_PARAMS = {
        // { 振幅, 波長, 速度, 方向角(rad) }
        { 0.08f, 4.0f,  1.5f, 0.0f },
        { 0.05f, 2.5f,  2.0f, 1.2f },
        { 0.03f, 1.8f,  2.8f, 2.7f },
        { 0.02f, 1.2f,  3.5f, 4.1f },
    };

    /** 水體顏色（深水） */
    private static final Vector3f DEEP_WATER_COLOR = new Vector3f(0.02f, 0.08f, 0.15f);

    /** 水體顏色（淺水） */
    private static final Vector3f SHALLOW_WATER_COLOR = new Vector3f(0.1f, 0.4f, 0.5f);

    /** 吸收係數（Beer-Lambert） */
    private static final Vector3f ABSORPTION_COEFF = new Vector3f(0.45f, 0.06f, 0.03f);

    /** 泡沫閾值（深度差小於此值顯示泡沫；可由節點圖動態調整） */
    private static volatile float foamDepthThreshold = 0.8f;

    /** 泡沫強度 */
    private static volatile float foamIntensity = 0.7f;

    /** 焦散強度 */
    private static volatile float causticsIntensity = 0.3f;

    /** 焦散紋理縮放 */
    private static volatile float causticsScale = 0.05f;

    // ========================= PFSF-Fluid 物理驅動狀態 =========================

    /** 物理速度量值（由 FluidRenderBridge 每 tick 更新，驅動 Gerstner 波振幅） */
    private static volatile float physicsVelocityMagnitude = 0.0f;

    /** 物理壓縮比（localPressure / 101325 Pa；> 1 表示高壓，驅動水色偏白） */
    private static volatile float physicsCompressionRatio = 1.0f;

    /** 焦散動畫速度倍率（由 WaterCausticsNode 每幀更新） */
    private static volatile float causticsAnimSpeedMult = 1.0f;

    /** 高壓壓縮時的水色目標（淡藍白） */
    private static final Vector3f COMPRESSION_WHITE = new Vector3f(0.9f, 0.95f, 1.0f);

    /** 物理法線貼圖紋理 ID（由 GPU 端 fluid_surface_normal.comp 寫入；0 表示未初始化） */
    private static int physicsNormalMapTex = 0;

    /** 物理法線混合權重（0 = 純 Gerstner，1 = 純物理法線；預設 40% 物理） */
    private static final float PHYSICS_NORMAL_BLEND = 0.4f;

    // ========================= GL 資源 =========================

    /** 反射 FBO（用於平面反射） */
    private static int reflectionFBO = 0;
    private static int reflectionColorTex = 0;
    private static int reflectionDepthTex = 0;
    private static int reflectionWidth = 0;
    private static int reflectionHeight = 0;

    private static boolean initialized = false;

    // ========================= 狀態 =========================

    /** 動畫時間（持續累加） */
    private static float animTime = 0.0f;

    // ========================= 初始化 =========================

    public static void init(int screenWidth, int screenHeight) {
        // 反射 FBO（半解析度以節省效能）
        reflectionWidth = screenWidth / 2;
        reflectionHeight = screenHeight / 2;

        reflectionFBO = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, reflectionFBO);

        // 反射顏色紋理
        reflectionColorTex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, reflectionColorTex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F,
            reflectionWidth, reflectionHeight, 0, GL11.GL_RGBA, GL11.GL_FLOAT, 0);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
            GL11.GL_TEXTURE_2D, reflectionColorTex, 0);

        // 反射深度紋理
        reflectionDepthTex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, reflectionDepthTex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT24,
            reflectionWidth, reflectionHeight, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, 0);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
            GL11.GL_TEXTURE_2D, reflectionDepthTex, 0);

        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            LOGGER.error("反射 FBO 建立失敗: 0x{}", Integer.toHexString(status));
        }

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

        initialized = true;
        LOGGER.info("BRWaterRenderer 初始化完成（反射 FBO {}x{}）", reflectionWidth, reflectionHeight);
    }

    public static void cleanup() {
        if (reflectionColorTex != 0) { GL11.glDeleteTextures(reflectionColorTex); reflectionColorTex = 0; }
        if (reflectionDepthTex != 0) { GL11.glDeleteTextures(reflectionDepthTex); reflectionDepthTex = 0; }
        if (reflectionFBO != 0) { GL30.glDeleteFramebuffers(reflectionFBO); reflectionFBO = 0; }
        initialized = false;
        LOGGER.info("BRWaterRenderer 已清理");
    }

    public static void onResize(int width, int height) {
        if (!initialized) return;
        cleanup();
        init(width, height);
    }

    // ========================= 每幀更新 =========================

    /**
     * 更新水面動畫和 Gerstner 波浪。
     *
     * @param deltaMs 幀間隔（ms）
     */
    public static void tick(float deltaMs) {
        animTime += deltaMs * 0.001f; // 轉秒
    }

    // ========================= 反射渲染 =========================

    /**
     * 開始反射 pass — 將渲染目標切換到反射 FBO。
     * 調用者需要翻轉相機（Y 座標對稱於水面）後渲染場景。
     */
    public static void beginReflectionPass() {
        if (!initialized) return;
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, reflectionFBO);
        GL11.glViewport(0, 0, reflectionWidth, reflectionHeight);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
    }

    /** 結束反射 pass — 恢復預設 FBO。 */
    public static void endReflectionPass() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    /**
     * 計算反射相機的 view 矩陣。
     *
     * @param viewMatrix 原始相機 view 矩陣
     * @param cameraY    相機 Y 座標
     * @return 翻轉後的 view 矩陣
     */
    public static Matrix4f computeReflectionMatrix(Matrix4f viewMatrix, float cameraY) {
        // 對稱翻轉：Y' = 2 * waterLevel - Y
        float reflectY = 2.0f * waterLevel - cameraY;
        Matrix4f reflectionMatrix = new Matrix4f(viewMatrix);
        // 在 view matrix 上施加 Y 軸翻轉
        reflectionMatrix.m10(-reflectionMatrix.m10());
        reflectionMatrix.m11(-reflectionMatrix.m11());
        reflectionMatrix.m12(-reflectionMatrix.m12());
        reflectionMatrix.m13(-reflectionMatrix.m13());
        // 平移矩陣以反射的相機 Y 位置（相對於水面）
        float yTranslation = 2.0f * (waterLevel - cameraY);
        reflectionMatrix.m31(reflectionMatrix.m31() + yTranslation * reflectionMatrix.m11());
        return reflectionMatrix;
    }

    // ========================= 水面渲染 =========================

    /**
     * 設定水面 shader uniform（在水面渲染前呼叫）。
     *
     * @param shader      水面 shader program
     * @param projMatrix  投影矩陣
     * @param viewMatrix  觀察矩陣
     * @param cameraPos   相機位置
     * @param gameTime    遊戲時間
     */
    public static void setupWaterUniforms(BRShaderProgram shader, Matrix4f projMatrix,
                                           Matrix4f viewMatrix, Vector3f cameraPos, float gameTime) {
        if (shader == null) return;

        shader.setUniformFloat("u_waterLevel", waterLevel);
        shader.setUniformFloat("u_animTime", animTime);
        shader.setUniformVec3("u_cameraPos", cameraPos.x, cameraPos.y, cameraPos.z);

        // Gerstner 波浪參數
        for (int i = 0; i < WAVE_PARAMS.length; i++) {
            String prefix = "u_wave[" + i + "]";
            shader.setUniformFloat(prefix + ".amplitude", WAVE_PARAMS[i][0]);
            shader.setUniformFloat(prefix + ".wavelength", WAVE_PARAMS[i][1]);
            shader.setUniformFloat(prefix + ".speed", WAVE_PARAMS[i][2]);
            shader.setUniformFloat(prefix + ".direction", WAVE_PARAMS[i][3]);
        }
        shader.setUniformInt("u_waveCount", WAVE_PARAMS.length);

        // 水體顏色（高壓時線性插值至壓縮白色，最大混合 100%）
        float compressionTint = Math.min(1.0f, (physicsCompressionRatio - 1.0f) * 0.3f);
        shader.setUniformVec3("u_deepWaterColor",
            lerp(DEEP_WATER_COLOR.x, COMPRESSION_WHITE.x, compressionTint),
            lerp(DEEP_WATER_COLOR.y, COMPRESSION_WHITE.y, compressionTint),
            lerp(DEEP_WATER_COLOR.z, COMPRESSION_WHITE.z, compressionTint));
        shader.setUniformVec3("u_shallowWaterColor",
            SHALLOW_WATER_COLOR.x, SHALLOW_WATER_COLOR.y, SHALLOW_WATER_COLOR.z);
        shader.setUniformVec3("u_absorptionCoeff",
            ABSORPTION_COEFF.x, ABSORPTION_COEFF.y, ABSORPTION_COEFF.z);

        // 泡沫 + 焦散（使用物理驅動的可變值）
        shader.setUniformFloat("u_foamThreshold", foamDepthThreshold);
        shader.setUniformFloat("u_foamIntensity", foamIntensity);
        shader.setUniformFloat("u_causticsIntensity", causticsIntensity);
        shader.setUniformFloat("u_causticsScale", causticsScale);
        shader.setUniformFloat("u_causticsAnimSpeedMult", causticsAnimSpeedMult);

        // 太陽資訊（預設值 — 大氣引擎已於 2.0 廢棄）
        Vector3f sunDir = new Vector3f(0.0f, 1.0f, 0.0f); // 預設正午太陽方向
        Vector3f sunCol = new Vector3f(1.0f, 0.95f, 0.8f); // 預設暖白太陽色
        shader.setUniformVec3("u_sunDir", sunDir.x, sunDir.y, sunDir.z);
        shader.setUniformVec3("u_sunColor", sunCol.x, sunCol.y, sunCol.z);

        // 綁定反射紋理
        GL13.glActiveTexture(GL13.GL_TEXTURE4);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, reflectionColorTex);
        shader.setUniformInt("u_reflectionTex", 4);

        // 綁定物理法線貼圖（fluid_surface_normal.comp 輸出）
        GL13.glActiveTexture(GL13.GL_TEXTURE5);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, physicsNormalMapTex);
        shader.setUniformInt("u_physicsNormalMap", 5);
        shader.setUniformFloat("u_physicsNormalBlend",
            physicsNormalMapTex != 0 ? PHYSICS_NORMAL_BLEND : 0.0f);
    }

    // ========================= Gerstner 波浪 CPU 計算 =========================

    /**
     * 計算指定 XZ 位置的 Gerstner 波浪位移（CPU 端，用於碰撞/物理）。
     *
     * @param x 世界 X 座標
     * @param z 世界 Z 座標
     * @return Y 位移量
     */
    public static float computeWaveHeight(float x, float z) {
        float height = 0.0f;
        // 物理速度場驅動振幅放大（高速區最多增加 30%）
        float physicsAmpScale = 1.0f + physicsVelocityMagnitude * 0.3f;
        for (float[] wave : WAVE_PARAMS) {
            float amp = wave[0] * physicsAmpScale;
            float wavelength = wave[1];
            float speed = wave[2];
            float dirAngle = wave[3];

            float dx = (float) Math.cos(dirAngle);
            float dz = (float) Math.sin(dirAngle);
            float k = (float)(2.0 * Math.PI) / wavelength;
            float phase = k * (dx * x + dz * z) - speed * animTime;
            height += amp * (float) Math.sin(phase);
        }
        return height;
    }

    /**
     * 計算指定 XZ 位置的 Gerstner 法線（CPU 端）。
     */
    public static Vector3f computeWaveNormal(float x, float z) {
        float nx = 0.0f, nz = 0.0f;
        float physicsAmpScale = 1.0f + physicsVelocityMagnitude * 0.3f;
        for (float[] wave : WAVE_PARAMS) {
            float amp = wave[0] * physicsAmpScale;
            float wavelength = wave[1];
            float speed = wave[2];
            float dirAngle = wave[3];

            float dx = (float) Math.cos(dirAngle);
            float dz = (float) Math.sin(dirAngle);
            float k = (float)(2.0 * Math.PI) / wavelength;
            float phase = k * (dx * x + dz * z) - speed * animTime;
            float cosP = (float) Math.cos(phase);

            nx -= amp * k * dx * cosP;
            nz -= amp * k * dz * cosP;
        }
        Vector3f normal = new Vector3f(nx, 1.0f, nz);
        normal.normalize();
        return normal;
    }

    // ========================= 設定 =========================

    public static void setWaterLevel(float level) { waterLevel = level; }
    public static float getWaterLevel() { return waterLevel; }

    public static int getReflectionTexture() { return reflectionColorTex; }
    public static int getReflectionFBO() { return reflectionFBO; }

    public static boolean isInitialized() { return initialized; }

    // ========================= 物理驅動設定（由 FluidRenderBridge / 節點圖呼叫）=========================

    /**
     * 由 {@code FluidRenderBridge} 每 tick 推送流體最大速度量值（m/s）。
     * 驅動 Gerstner 波振幅放大（高速區最多 +30%）。
     */
    public static void setPhysicsVelocityMagnitude(float velMag) {
        physicsVelocityMagnitude = Math.max(0.0f, velMag);
    }

    /**
     * 由 {@code FluidRenderBridge} 每 tick 推送壓縮比（localPressure / 101325 Pa）。
     * > 1 表示高壓；驅動水色線性插值至淡藍白。
     */
    public static void setPhysicsCompressionRatio(float ratio) {
        physicsCompressionRatio = Math.max(1.0f, ratio);
    }

    /**
     * 由 {@code WaterCausticsNode} 推送焦散動畫速度倍率。
     * 傳入 {@code shader.u_causticsAnimSpeedMult}。
     */
    public static void setCausticsAnimSpeed(float speedMult) {
        causticsAnimSpeedMult = Math.max(0.1f, speedMult);
    }

    /**
     * 由 Vulkan 端 {@code fluid_surface_normal.comp} 輸出管線設定物理法線貼圖 ID。
     * 0 表示不可用（回退到純 Gerstner 法線）。
     */
    public static void setPhysicsNormalMapTex(int texId) {
        physicsNormalMapTex = texId;
    }

    public static int getPhysicsNormalMapTex() { return physicsNormalMapTex; }

    // ─── 工具 ───

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}

