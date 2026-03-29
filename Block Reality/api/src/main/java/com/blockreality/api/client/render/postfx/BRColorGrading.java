package com.blockreality.api.client.render.postfx;

import com.blockreality.api.client.render.BRRenderConfig;
import com.blockreality.api.client.render.shader.BRShaderEngine;
import com.blockreality.api.client.render.shader.BRShaderProgram;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;

/**
 * 色彩分級引擎 — 程序化 3D LUT 生成 + 色調/飽和/亮度/色彩平衡調整。
 *
 * 技術融合：
 *   - Unreal Engine: "Color Grading and Filmic Tonemapper"
 *   - Radiance/Iris: 後處理色彩校正
 *   - FilmGrain: ASC CDL (American Society of Cinematographers Color Decision List)
 *
 * 設計要點：
 *   - 程序化生成 32³ 3D LUT 紋理（GL_TEXTURE_3D）
 *   - 支援：亮度 (Lift) / 灰度 (Gamma) / 高光 (Gain) 三區色彩校正
 *   - 色溫 (Color Temperature) + 色調 (Tint)
 *   - 飽和度 (Saturation) + 對比度 (Contrast)
 *   - 時段自適應（日出偏暖金、正午中性、黃昏偏紫粉、夜晚偏冷藍）
 *   - LUT 在參數變化時才重新生成（lazy update）
 */
@OnlyIn(Dist.CLIENT)
public final class BRColorGrading {
    private BRColorGrading() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("BR-ColorGrade");

    // ─── LUT 參數 ──────────────────────────────────────────
    public static final int LUT_SIZE = 32; // 32³

    /** Lift / Gamma / Gain（RGB 偏移，預設中性白） */
    private static final Vector3f lift  = new Vector3f(0.0f, 0.0f, 0.0f);
    private static final Vector3f gamma = new Vector3f(1.0f, 1.0f, 1.0f);
    private static final Vector3f gain  = new Vector3f(1.0f, 1.0f, 1.0f);

    /** 色溫（Kelvin, 6500K=中性, <6500 偏暖, >6500 偏冷） */
    private static float colorTemperature = 6500.0f;

    /** 色調偏移（-1=綠, 0=中性, +1=洋紅） */
    private static float tint = 0.0f;

    /** 飽和度乘數（1.0=原始） */
    private static float saturation = 1.05f;

    /** 對比度乘數（1.0=原始） */
    private static float contrast = 1.05f;

    // ─── GL 資源 ────────────────────────────────────────────
    private static int lutTexture;    // GL_TEXTURE_3D
    private static boolean lutDirty = true;
    private static boolean initialized = false;

    /** 時段色彩偏移快取 */
    private static float currentDayFactor = 0.5f;

    // ═══════════════════════════════════════════════════════
    //  初始化 / 清除
    // ═══════════════════════════════════════════════════════

    public static void init() {
        if (initialized) return;

        // 建立 3D LUT 紋理（32 × 32 × 32 × RGBA16F）
        lutTexture = GL11.glGenTextures();
        GL13.glActiveTexture(GL13.GL_TEXTURE15); // 使用 unit 15 避免衝突
        GL11.glBindTexture(GL30.GL_TEXTURE_3D, lutTexture);

        // 先分配空間
        GL30.glTexImage3D(GL30.GL_TEXTURE_3D, 0, GL30.GL_RGBA16F,
            LUT_SIZE, LUT_SIZE, LUT_SIZE, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (FloatBuffer) null);

        GL11.glTexParameteri(GL30.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL30.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL30.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL30.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL30.GL_TEXTURE_3D, GL30.GL_TEXTURE_WRAP_R, GL13.GL_CLAMP_TO_EDGE);

        GL11.glBindTexture(GL30.GL_TEXTURE_3D, 0);

        // 初始 LUT 生成
        regenerateLUT();

        initialized = true;
        LOGGER.info("BRColorGrading 初始化完成 — {}³ 3D LUT", LUT_SIZE);
    }

    public static void cleanup() {
        if (lutTexture != 0) { GL11.glDeleteTextures(lutTexture); lutTexture = 0; }
        initialized = false;
    }

    // ═══════════════════════════════════════════════════════
    //  LUT 生成
    // ═══════════════════════════════════════════════════════

    /**
     * 程序化生成 3D LUT。
     * 每個 texel 代表一個 RGB 輸入色 → 輸出色的映射。
     */
    private static void regenerateLUT() {
        int totalPixels = LUT_SIZE * LUT_SIZE * LUT_SIZE;
        float[] lutData = new float[totalPixels * 4]; // RGBA

        int idx = 0;
        for (int b = 0; b < LUT_SIZE; b++) {
            for (int g = 0; g < LUT_SIZE; g++) {
                for (int r = 0; r < LUT_SIZE; r++) {
                    // 原始色（0~1 線性）
                    float cr = (float) r / (LUT_SIZE - 1);
                    float cg = (float) g / (LUT_SIZE - 1);
                    float cb = (float) b / (LUT_SIZE - 1);

                    // 1. 色溫調整（簡化 Planckian locus）
                    float tempOffset = (colorTemperature - 6500.0f) / 6500.0f;
                    cr += tempOffset * 0.1f;
                    cb -= tempOffset * 0.1f;

                    // 2. Tint（綠/洋紅軸）
                    cg += tint * 0.05f;

                    // 3. Lift / Gamma / Gain (ASC CDL)
                    cr = applyLGG(cr, lift.x, gamma.x, gain.x);
                    cg = applyLGG(cg, lift.y, gamma.y, gain.y);
                    cb = applyLGG(cb, lift.z, gamma.z, gain.z);

                    // 4. 對比度（以 0.5 為中心）
                    cr = (cr - 0.5f) * contrast + 0.5f;
                    cg = (cg - 0.5f) * contrast + 0.5f;
                    cb = (cb - 0.5f) * contrast + 0.5f;

                    // 5. 飽和度
                    float luma = cr * 0.2126f + cg * 0.7152f + cb * 0.0722f;
                    cr = luma + (cr - luma) * saturation;
                    cg = luma + (cg - luma) * saturation;
                    cb = luma + (cb - luma) * saturation;

                    // 6. 時段色偏 — 在 GLSL shader 中動態處理（COLOR_GRADING_FRAG 的 applyTimeTint）
                    // 因為 dayFactor 每幀變化，靜態 LUT 無法預烘焙時段色偏。
                    // 架構決策：LUT 只處理靜態色彩分級（溫度/LGG/對比/飽和），
                    // 時段色偏由 shader 在 LUT 查詢後即時疊加。

                    // Clamp
                    cr = Math.max(0, Math.min(1, cr));
                    cg = Math.max(0, Math.min(1, cg));
                    cb = Math.max(0, Math.min(1, cb));

                    lutData[idx++] = cr;
                    lutData[idx++] = cg;
                    lutData[idx++] = cb;
                    lutData[idx++] = 1.0f;
                }
            }
        }

        // 上傳到 GPU
        GL11.glBindTexture(GL30.GL_TEXTURE_3D, lutTexture);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // MemoryStack 可能不夠大，用 heap buffer
        }
        FloatBuffer buf = org.lwjgl.BufferUtils.createFloatBuffer(lutData.length);
        buf.put(lutData).flip();
        GL30.glTexImage3D(GL30.GL_TEXTURE_3D, 0, GL30.GL_RGBA16F,
            LUT_SIZE, LUT_SIZE, LUT_SIZE, 0, GL11.GL_RGBA, GL11.GL_FLOAT, buf);
        GL11.glBindTexture(GL30.GL_TEXTURE_3D, 0);

        lutDirty = false;
    }

    /**
     * ASC CDL: Lift / Gamma / Gain 計算。
     * output = (gain * (input + lift * (1 - input))) ^ (1/gamma)
     */
    private static float applyLGG(float input, float liftVal, float gammaVal, float gainVal) {
        float lifted = input + liftVal * (1.0f - input);
        float gained = gainVal * lifted;
        if (gammaVal <= 0.001f) gammaVal = 0.001f;
        return (float) Math.pow(Math.max(0, gained), 1.0f / gammaVal);
    }

    // 注意：時段色偏（applyTimeTint）由 GLSL shader 動態處理，不在 Java LUT 生成中。
    // 原因：Java float 是 pass-by-value，無法修改呼叫端的 cr/cg/cb；
    // 且 dayFactor 每幀變化，靜態 LUT 無法正確預烘焙。
    // 完整實作見 BRShaderEngine.COLOR_GRADING_FRAG 的 applyTimeTint() GLSL 函數。

    // ═══════════════════════════════════════════════════════
    //  每幀更新
    // ═══════════════════════════════════════════════════════

    /**
     * 更新時段因子。如果參數變化，標記 LUT 為 dirty。
     */
    public static void updateDayFactor(float dayFactor) {
        if (Math.abs(dayFactor - currentDayFactor) > 0.01f) {
            currentDayFactor = dayFactor;
            lutDirty = true;
        }
    }

    /**
     * 每幀呼叫：如果 LUT dirty 則重新生成。
     */
    public static void tick() {
        if (lutDirty && initialized) {
            regenerateLUT();
        }
    }

    /**
     * 綁定 3D LUT 到指定 texture unit，並設定 shader uniforms。
     */
    public static void bindLUT(BRShaderProgram shader, int textureUnit) {
        if (!initialized) return;

        GL13.glActiveTexture(GL13.GL_TEXTURE0 + textureUnit);
        GL11.glBindTexture(GL30.GL_TEXTURE_3D, lutTexture);
        shader.setUniformInt("u_lutTex", textureUnit);
        shader.setUniformFloat("u_lutSize", LUT_SIZE);
        shader.setUniformFloat("u_dayFactor", currentDayFactor);
    }

    // ─── 參數設定（未來可連接 UI 或配置）──────────────────

    public static void setLift(float r, float g, float b) {
        lift.set(r, g, b); lutDirty = true;
    }
    public static void setGamma(float r, float g, float b) {
        gamma.set(r, g, b); lutDirty = true;
    }
    public static void setGain(float r, float g, float b) {
        gain.set(r, g, b); lutDirty = true;
    }
    public static void setColorTemperature(float kelvin) {
        colorTemperature = kelvin; lutDirty = true;
    }
    public static void setTint(float t) {
        tint = t; lutDirty = true;
    }
    public static void setSaturation(float s) {
        saturation = s; lutDirty = true;
    }
    public static void setContrast(float c) {
        contrast = c; lutDirty = true;
    }

    // ─── Accessors ──────────────────────────────────────────

    public static int getLutTexture() { return lutTexture; }
    public static float getSaturation() { return saturation; }
    public static float getContrast() { return contrast; }
    public static float getColorTemperature() { return colorTemperature; }
    public static boolean isInitialized() { return initialized; }
}
