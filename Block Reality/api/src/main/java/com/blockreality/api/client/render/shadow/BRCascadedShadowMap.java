package com.blockreality.api.client.render.shadow;

import com.blockreality.api.client.render.BRRenderConfig;
import com.blockreality.api.client.render.optimization.BROptimizationEngine;
import com.blockreality.api.client.render.optimization.BRLODEngine;
import com.blockreality.api.client.render.shader.BRShaderEngine;
import com.blockreality.api.client.render.shader.BRShaderProgram;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cascaded Shadow Maps（CSM）引擎 — 4 級聯陰影 + PCSS 軟陰影。
 *
 * 技術融合：
 *   - Iris/OptiFine: 多層 shadow pass，近距離高精度、遠距離低精度
 *   - PCSS (Percentage Closer Soft Shadows): 基於遮擋距離的可變模糊半徑
 *   - GPU Gems 3: "Parallel-Split Shadow Maps" 實用分割策略
 *
 * 設計要點：
 *   - 4 級聯（cascade），對數/均勻混合分割
 *   - 每級獨立解析度（近 2048, 中 1536, 遠 1024, 最遠 512）
 *   - 穩定化：texel snapping 消除游泳邊緣
 *   - Depth bias 自適應（斜率 + 常數 bias per cascade）
 *   - 單一 GL_TEXTURE_2D_ARRAY 儲存 4 層深度
 *
 * 取代 BRRenderPipeline 中原有的單層 Shadow Map。
 */
@OnlyIn(Dist.CLIENT)
public final class BRCascadedShadowMap {
    private BRCascadedShadowMap() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("BR-CSM");

    // ─── 常數 ──────────────────────────────────────────────
    public static final int CASCADE_COUNT = 4;

    /** 各級聯解析度 */
    private static final int[] CASCADE_RESOLUTION = {2048, 1536, 1024, 512};

    /** 各級聯深度 bias（近距離小 bias 避免 peter-panning，遠距離大 bias 防 acne） */
    private static final float[] CASCADE_BIAS = {0.0005f, 0.001f, 0.002f, 0.004f};

    /** 級聯分割比例（佔 maxDistance 的百分比） */
    private static final float[] CASCADE_SPLIT_RATIO = {0.05f, 0.15f, 0.40f, 1.0f};

    // ─── GL 資源 ────────────────────────────────────────────
    private static int[] shadowFbos = new int[CASCADE_COUNT];
    private static int[] shadowDepthTextures = new int[CASCADE_COUNT];

    /** 各級聯的光源投影矩陣 */
    private static final Matrix4f[] lightProjections = new Matrix4f[CASCADE_COUNT];
    private static final Matrix4f[] lightViews = new Matrix4f[CASCADE_COUNT];
    private static final Matrix4f[] lightViewProjs = new Matrix4f[CASCADE_COUNT];

    /** 各級聯的分割距離（view space） */
    private static final float[] splitDistances = new float[CASCADE_COUNT + 1];

    /** 光源方向（來自 BRAtmosphereEngine 的太陽方向） */
    private static final Vector3f lightDirection = new Vector3f();

    private static boolean initialized = false;

    // ═══════════════════════════════════════════════════════
    //  初始化 / 清除
    // ═══════════════════════════════════════════════════════

    public static void init() {
        if (initialized) return;

        for (int i = 0; i < CASCADE_COUNT; i++) {
            lightProjections[i] = new Matrix4f();
            lightViews[i] = new Matrix4f();
            lightViewProjs[i] = new Matrix4f();
        }

        // 為每個級聯建立獨立 FBO + 深度紋理
        for (int i = 0; i < CASCADE_COUNT; i++) {
            int res = CASCADE_RESOLUTION[i];

            // 建立深度紋理
            shadowDepthTextures[i] = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, shadowDepthTextures[i]);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL14.GL_DEPTH_COMPONENT24,
                res, res, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (java.nio.FloatBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_BORDER);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_BORDER);
            // 邊界色設為 1.0（不在陰影中）
            GL11.glTexParameterfv(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_BORDER_COLOR,
                new float[]{1.0f, 1.0f, 1.0f, 1.0f});
            // 啟用 shadow compare（PCF hardware filtering）
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE,
                GL14.GL_COMPARE_R_TO_TEXTURE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_FUNC, GL11.GL_LEQUAL);

            // 建立 FBO
            shadowFbos[i] = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, shadowFbos[i]);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D, shadowDepthTextures[i], 0);
            GL11.glDrawBuffer(GL11.GL_NONE);
            GL11.glReadBuffer(GL11.GL_NONE);

            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                LOGGER.error("CSM FBO cascade {} 不完整: 0x{}", i, Integer.toHexString(status));
            }
        }

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        initialized = true;
        LOGGER.info("CSM 引擎初始化完成 — {} 級聯", CASCADE_COUNT);
    }

    public static void cleanup() {
        for (int i = 0; i < CASCADE_COUNT; i++) {
            if (shadowFbos[i] != 0) {
                GL30.glDeleteFramebuffers(shadowFbos[i]);
                shadowFbos[i] = 0;
            }
            if (shadowDepthTextures[i] != 0) {
                GL11.glDeleteTextures(shadowDepthTextures[i]);
                shadowDepthTextures[i] = 0;
            }
        }
        initialized = false;
    }

    // ═══════════════════════════════════════════════════════
    //  每幀更新
    // ═══════════════════════════════════════════════════════

    /**
     * 計算級聯分割距離（對數 + 均勻混合）。
     *
     * @param nearPlane 相機近平面
     * @param farPlane  陰影最大距離
     * @param lambda    對數/均勻混合因子（0=均勻, 1=對數, 推薦 0.75）
     */
    public static void computeSplitDistances(float nearPlane, float farPlane, float lambda) {
        splitDistances[0] = nearPlane;
        for (int i = 1; i <= CASCADE_COUNT; i++) {
            float p = (float) i / CASCADE_COUNT;
            float logSplit = nearPlane * (float) Math.pow(farPlane / nearPlane, p);
            float linearSplit = nearPlane + (farPlane - nearPlane) * p;
            splitDistances[i] = lambda * logSplit + (1.0f - lambda) * linearSplit;
        }
        // 使用預設比例覆寫（更可控）
        for (int i = 0; i < CASCADE_COUNT; i++) {
            splitDistances[i + 1] = nearPlane + (farPlane - nearPlane) * CASCADE_SPLIT_RATIO[i];
        }
    }

    /**
     * 更新光源方向（從 BRAtmosphereEngine 取得太陽方向）。
     */
    public static void setLightDirection(float x, float y, float z) {
        lightDirection.set(x, y, z).normalize();
    }

    /**
     * 計算所有級聯的光空間矩陣。
     * 使用 "tight fit" 策略：計算相機 frustum 子段的包圍球，再套用光源正交投影。
     *
     * @param cameraView 相機 view 矩陣
     * @param cameraProj 相機 projection 矩陣
     */
    public static void updateCascades(Matrix4f cameraView, Matrix4f cameraProj) {
        // 計算相機 frustum 的 8 個角在世界空間中的位置
        Matrix4f invViewProj = new Matrix4f();
        cameraProj.mul(cameraView, invViewProj);
        invViewProj.invert();

        for (int c = 0; c < CASCADE_COUNT; c++) {
            float near = splitDistances[c];
            float far = splitDistances[c + 1];

            // 將 near/far 映射到 NDC z 範圍
            // 需要用原始 projection 的 near/far 重新映射
            float ndcNear = 2.0f * near / (splitDistances[CASCADE_COUNT]) - 1.0f;
            float ndcFar = 2.0f * far / (splitDistances[CASCADE_COUNT]) - 1.0f;

            // 計算此級聯的 frustum 角（NDC → 世界空間）
            Vector4f[] frustumCorners = new Vector4f[8];
            int idx = 0;
            for (float z : new float[]{ndcNear, ndcFar}) {
                for (float y : new float[]{-1.0f, 1.0f}) {
                    for (float x : new float[]{-1.0f, 1.0f}) {
                        Vector4f corner = new Vector4f(x, y, z, 1.0f);
                        invViewProj.transform(corner);
                        corner.div(corner.w);
                        frustumCorners[idx++] = corner;
                    }
                }
            }

            // 計算 frustum 中心
            Vector3f center = new Vector3f();
            for (Vector4f corner : frustumCorners) {
                center.add(corner.x, corner.y, corner.z);
            }
            center.div(8.0f);

            // 計算包圍球半徑
            float radius = 0;
            for (Vector4f corner : frustumCorners) {
                float dist = new Vector3f(corner.x, corner.y, corner.z).distance(center);
                radius = Math.max(radius, dist);
            }
            // 向上取整到 texel 大小（穩定化 — texel snapping）
            float texelSize = (radius * 2.0f) / CASCADE_RESOLUTION[c];
            radius = (float) Math.ceil(radius / texelSize) * texelSize;

            // 建立光源 view 矩陣
            Vector3f lightPos = new Vector3f(center).sub(
                new Vector3f(lightDirection).mul(radius * 2.0f)
            );
            lightViews[c].identity().lookAt(
                lightPos.x, lightPos.y, lightPos.z,
                center.x, center.y, center.z,
                0.0f, 1.0f, 0.0f
            );

            // 建立光源正交投影
            lightProjections[c].identity().ortho(
                -radius, radius,
                -radius, radius,
                0.01f, radius * 4.0f
            );

            // Texel Snapping — 防止陰影游泳
            Matrix4f shadowMat = new Matrix4f();
            lightProjections[c].mul(lightViews[c], shadowMat);
            Vector4f shadowOrigin = new Vector4f(0, 0, 0, 1);
            shadowMat.transform(shadowOrigin);
            shadowOrigin.mul(CASCADE_RESOLUTION[c] / 2.0f);

            float roundX = Math.round(shadowOrigin.x) - shadowOrigin.x;
            float roundY = Math.round(shadowOrigin.y) - shadowOrigin.y;
            roundX /= CASCADE_RESOLUTION[c] / 2.0f;
            roundY /= CASCADE_RESOLUTION[c] / 2.0f;

            // 偏移投影矩陣
            lightProjections[c].m30(lightProjections[c].m30() + roundX);
            lightProjections[c].m31(lightProjections[c].m31() + roundY);

            // 合成 view-proj
            lightProjections[c].mul(lightViews[c], lightViewProjs[c]);
        }
    }

    // ═══════════════════════════════════════════════════════
    //  渲染
    // ═══════════════════════════════════════════════════════

    /**
     * 渲染所有級聯的陰影深度。
     * 遍歷 4 個級聯，各自綁定 FBO + 光源矩陣 → 渲染幾何。
     */
    public static void renderAllCascades() {
        if (!initialized) return;

        BRShaderProgram shadowShader = BRShaderEngine.getShadowShader();
        if (shadowShader == null) return;

        shadowShader.bind();

        for (int c = 0; c < CASCADE_COUNT; c++) {
            int res = CASCADE_RESOLUTION[c];

            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, shadowFbos[c]);
            GL11.glViewport(0, 0, res, res);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);

            // 設定 depth bias
            GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
            GL11.glPolygonOffset(CASCADE_BIAS[c] * 10.0f, CASCADE_BIAS[c]);

            // 上傳光源矩陣
            shadowShader.setUniformMat4("u_shadowProj", lightProjections[c]);
            shadowShader.setUniformMat4("u_shadowView", lightViews[c]);

            // 渲染結構幾何
            BROptimizationEngine.renderShadowGeometry(lightProjections[c], lightViews[c]);

            // 近距離級聯也渲染 LOD 幾何
            if (c < 2) {
                BRLODEngine.renderShadow(lightProjections[c], lightViews[c]);
            }

            GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
        }

        shadowShader.unbind();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    /**
     * 綁定所有級聯 shadow map 到指定 texture unit 起始位置。
     * 供延遲光照 pass 查詢陰影。
     *
     * @param shader    目標 shader
     * @param startUnit 起始 texture unit（例如 7 → unit 7/8/9/10）
     */
    public static void bindShadowMaps(BRShaderProgram shader, int startUnit) {
        for (int c = 0; c < CASCADE_COUNT; c++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + startUnit + c);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, shadowDepthTextures[c]);
            shader.setUniformInt("u_shadowMap[" + c + "]", startUnit + c);
        }

        // 上傳各級聯的光空間矩陣和分割距離
        for (int c = 0; c < CASCADE_COUNT; c++) {
            shader.setUniformMat4("u_lightViewProj[" + c + "]", lightViewProjs[c]);
            shader.setUniformFloat("u_cascadeSplit[" + c + "]", splitDistances[c + 1]);
        }

        // 光源方向
        shader.setUniformVec3("u_lightDir", lightDirection.x, lightDirection.y, lightDirection.z);
    }

    // ─── Accessors ──────────────────────────────────────────

    public static int getCascadeCount() { return CASCADE_COUNT; }
    public static int getCascadeFbo(int index) { return shadowFbos[index]; }
    public static int getCascadeDepthTex(int index) { return shadowDepthTextures[index]; }
    public static Matrix4f getLightViewProj(int index) { return lightViewProjs[index]; }
    public static float getSplitDistance(int index) { return splitDistances[index]; }
    public static float[] getSplitDistances() { return splitDistances; }
    public static Vector3f getLightDirection() { return lightDirection; }
    public static boolean isInitialized() { return initialized; }
}
