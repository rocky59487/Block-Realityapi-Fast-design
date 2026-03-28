package com.blockreality.api.client.render.postfx;

import com.blockreality.api.client.render.BRRenderConfig;
import com.blockreality.api.client.render.pipeline.BRFramebufferManager;
import com.blockreality.api.client.render.shader.BRShaderEngine;
import com.blockreality.api.client.render.shader.BRShaderProgram;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;

/**
 * Screen-Space Global Illumination（SSGI）— 螢幕空間全域光照近似。
 *
 * 技術融合：
 *   - Crassin 2012: "Interactive Indirect Illumination Using Voxel Cone Tracing"（簡化版）
 *   - Jimenez 2016: "Practical Real-Time Strategies for Accurate Indirect Occlusion" (GTAO)
 *   - Iris/BSL/Complementary: SSGI composite pass
 *
 * 設計要點：
 *   - 半解析度運算（效能友好）
 *   - 從 GBuffer albedo + normal + depth 取樣
 *   - 射線 march 從每個像素向半球方向取樣間接光
 *   - Temporal accumulation 減少噪聲
 *   - 可獨立啟用/關閉，疊加在直接光照之上
 *   - 單獨 FBO 儲存 GI 結果（RGBA16F）
 */
@OnlyIn(Dist.CLIENT)
public final class BRGlobalIllumination {
    private BRGlobalIllumination() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("BR-SSGI");

    // ─── GL 資源 ────────────────────────────────────────────
    /** 半解析度 GI FBO */
    private static int giFbo;
    private static int giColorTex;
    /** 歷史幀 GI（temporal accumulation） */
    private static int giHistoryTex;
    private static int giFboWidth;
    private static int giFboHeight;

    /** 幀計數（交替 jitter 模式） */
    private static int frameIndex = 0;

    private static boolean initialized = false;

    // ═══════════════════════════════════════════════════════
    //  初始化 / 清除
    // ═══════════════════════════════════════════════════════

    public static void init(int screenWidth, int screenHeight) {
        if (initialized) return;

        giFboWidth = Math.max(1, screenWidth / 2);
        giFboHeight = Math.max(1, screenHeight / 2);

        // GI FBO
        giFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, giFbo);

        giColorTex = createHalfResTexture(giFboWidth, giFboHeight);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
            GL11.GL_TEXTURE_2D, giColorTex, 0);

        // History texture（temporal accumulation）
        giHistoryTex = createHalfResTexture(giFboWidth, giFboHeight);

        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            LOGGER.error("SSGI FBO 不完整: 0x{}", Integer.toHexString(status));
        }

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

        initialized = true;
        LOGGER.info("BRGlobalIllumination 初始化完成 — 半解析度 {}x{}", giFboWidth, giFboHeight);
    }

    private static int createHalfResTexture(int w, int h) {
        int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F,
            w, h, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (FloatBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return tex;
    }

    public static void cleanup() {
        if (giFbo != 0) { GL30.glDeleteFramebuffers(giFbo); giFbo = 0; }
        if (giColorTex != 0) { GL11.glDeleteTextures(giColorTex); giColorTex = 0; }
        if (giHistoryTex != 0) { GL11.glDeleteTextures(giHistoryTex); giHistoryTex = 0; }
        initialized = false;
    }

    public static void onResize(int width, int height) {
        if (!initialized) return;
        giFboWidth = Math.max(1, width / 2);
        giFboHeight = Math.max(1, height / 2);

        // 重建半解析度紋理
        rebuildTexture(giColorTex, giFboWidth, giFboHeight);
        rebuildTexture(giHistoryTex, giFboWidth, giFboHeight);
    }

    private static void rebuildTexture(int tex, int w, int h) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F,
            w, h, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (FloatBuffer) null);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    // ═══════════════════════════════════════════════════════
    //  渲染
    // ═══════════════════════════════════════════════════════

    /**
     * 執行 SSGI pass。
     * 在 deferred lighting 之後、composite chain 之前呼叫。
     *
     * @param gameTime 遊戲時間（用於 jitter 偏移）
     */
    public static void render(float gameTime) {
        if (!initialized) return;

        BRShaderProgram shader = BRShaderEngine.getSSGIShader();
        if (shader == null) return;

        frameIndex++;

        // 渲染到半解析度 FBO
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, giFbo);
        GL11.glViewport(0, 0, giFboWidth, giFboHeight);
        GL11.glClearColor(0, 0, 0, 0);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

        shader.bind();

        // 綁定 GBuffer 紋理
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, BRFramebufferManager.getGbufferColorTex(0)); // albedo
        shader.setUniformInt("u_albedoTex", 0);

        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, BRFramebufferManager.getGbufferColorTex(1)); // normal
        shader.setUniformInt("u_normalTex", 1);

        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, BRFramebufferManager.getGbufferDepthTex());
        shader.setUniformInt("u_depthTex", 2);

        // 歷史幀（temporal accumulation）
        GL13.glActiveTexture(GL13.GL_TEXTURE3);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, giHistoryTex);
        shader.setUniformInt("u_historyTex", 3);

        // Uniforms
        shader.setUniformFloat("u_gameTime", gameTime);
        shader.setUniformInt("u_frameIndex", frameIndex);
        shader.setUniformFloat("u_giIntensity", BRRenderConfig.SSGI_INTENSITY);
        shader.setUniformFloat("u_giRadius", BRRenderConfig.SSGI_RADIUS);
        shader.setUniformInt("u_giSamples", BRRenderConfig.SSGI_SAMPLES);

        // 繪製全螢幕 quad
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);

        shader.unbind();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

        // Swap history：當前 → 歷史
        int temp = giHistoryTex;
        giHistoryTex = giColorTex;
        giColorTex = temp;

        // 重新綁定新的 color 到 FBO
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, giFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
            GL11.GL_TEXTURE_2D, giColorTex, 0);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    // ─── Accessors ──────────────────────────────────────────

    public static int getGITexture() { return giColorTex; }
    public static int getHistoryTexture() { return giHistoryTex; }
    public static boolean isInitialized() { return initialized; }

    // ═══════════════════════════════════════════════════════════════
    //  Voxel Cone Tracing（VCT）— 體素錐體追蹤全域光照
    //  參考 Crassin 2012: "Interactive Indirect Illumination Using
    //  Voxel Cone Tracing" (NVIDIA GTC 2012)
    // ═══════════════════════════════════════════════════════════════

    /** VCT 體素化解析度（64³ 或 128³ — 用於低解析度 GI volume） */
    private static final int VCT_RESOLUTION = 64;

    /** VCT 體素化範圍（世界空間方塊數，以攝影機為中心） */
    private static final float VCT_WORLD_EXTENT = 128.0f;

    /** VCT 3D 紋理（R11F_G11F_B10F — 存儲注入的直接光照） */
    private static int vctVoxelTexture = 0;

    /** VCT mipmap 鏈（用於不同角度的錐體追蹤） */
    private static int vctMipLevels = 0;

    /** VCT 是否已初始化 */
    private static boolean vctInitialized = false;

    /** VCT 追蹤參數 */
    public static float vctConeAngle = 0.5f;      // 錐體半角（弧度）
    public static int vctConeCount = 5;            // 每像素追蹤的錐體數
    public static float vctMaxDistance = 64.0f;    // 最大追蹤距離
    public static float vctStepMultiplier = 1.5f;  // 步進距離乘數（越大越快但越不精確）

    /**
     * 初始化 VCT 體素化資源。
     * 建立 3D 紋理用於儲存場景直接光照的體素化結果。
     */
    public static void initVCT() {
        if (vctInitialized) return;

        vctMipLevels = (int)(Math.log(VCT_RESOLUTION) / Math.log(2)) + 1;

        // 建立 3D 紋理
        vctVoxelTexture = GL11.glGenTextures();
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, vctVoxelTexture);

        // 分配所有 mip 等級
        int size = VCT_RESOLUTION;
        for (int level = 0; level < vctMipLevels; level++) {
            GL12.glTexImage3D(GL12.GL_TEXTURE_3D, level, GL30.GL_R11F_G11F_B10F,
                size, size, size, 0, GL11.GL_RGB, GL11.GL_FLOAT, (java.nio.FloatBuffer) null);
            size = Math.max(1, size / 2);
        }

        // 線性 mipmap 過濾（錐體追蹤需要平滑取樣）
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL13.GL_CLAMP_TO_EDGE);

        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);

        vctInitialized = true;
        LOGGER.info("[VCT] 體素錐體追蹤初始化 — {}³ 體素, {} mip levels, 範圍 {} 方塊",
            VCT_RESOLUTION, vctMipLevels, VCT_WORLD_EXTENT);
    }

    /**
     * 體素化場景（Phase 1）— 將直接光照注入 3D 體素紋理。
     *
     * 步驟：
     *   1. 清除 3D 紋理
     *   2. 使用正交投影從 3 軸渲染場景到 3D 紋理（使用 geometry shader 展開）
     *   3. 在每個體素中儲存直接光照顏色 + 遮蔽
     *   4. 生成 mipmap（各向同性過濾）
     *
     * 此步驟可每 N 幀執行一次以節省效能（光照變化通常緩慢）。
     *
     * @param cameraX 攝影機世界座標 X
     * @param cameraY 攝影機世界座標 Y
     * @param cameraZ 攝影機世界座標 Z
     */
    public static void voxelizeScene(float cameraX, float cameraY, float cameraZ) {
        if (!vctInitialized || vctVoxelTexture == 0) return;

        // 清除 3D 紋理（全部歸零）
        // 注意：GL 4.4+ 支援 glClearTexImage，此處使用相容方式
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, vctVoxelTexture);
        // TODO: 使用 compute shader 或 imageStore 清除
        // GL44.glClearTexImage(vctVoxelTexture, 0, GL11.GL_RGB, GL11.GL_FLOAT, new float[]{0,0,0});

        // 體素化範圍（以攝影機為中心）
        float halfExtent = VCT_WORLD_EXTENT / 2.0f;
        float voxelSize = VCT_WORLD_EXTENT / VCT_RESOLUTION;

        // 三軸正交投影體素化
        // X 軸投影、Y 軸投影、Z 軸投影
        // 每軸使用 geometry shader 將三角形展開到對應的 3D 紋理層
        // 使用 imageStore 寫入體素（需要 GL_ARB_shader_image_load_store）

        // 框架實作：記錄體素化範圍供追蹤階段使用
        vctWorldMinX = cameraX - halfExtent;
        vctWorldMinY = cameraY - halfExtent;
        vctWorldMinZ = cameraZ - halfExtent;
        vctVoxelSize = voxelSize;

        // 生成 mipmap
        GL30.glGenerateMipmap(GL12.GL_TEXTURE_3D);

        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);

        LOGGER.debug("[VCT] 場景體素化完成 — 中心 ({:.0f}, {:.0f}, {:.0f}), 體素大小 {:.2f}",
            cameraX, cameraY, cameraZ, voxelSize);
    }

    /** 體素化範圍（世界座標）— 追蹤階段使用 */
    private static float vctWorldMinX, vctWorldMinY, vctWorldMinZ;
    private static float vctVoxelSize = 1.0f;

    /**
     * 錐體追蹤（Phase 2）— 從每個螢幕像素發射錐體，採樣 3D 體素紋理。
     *
     * 在 SSGI shader 中執行（替代或疊加現有的 screen-space ray march）。
     * 此方法設定 VCT 相關的 uniforms 並綁定 3D 體素紋理。
     *
     * @param shader SSGI/VCT shader
     * @param textureUnit 綁定 3D 紋理的 texture unit
     */
    public static void bindVCTUniforms(BRShaderProgram shader, int textureUnit) {
        if (!vctInitialized || shader == null) return;

        // 綁定 3D 體素紋理
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + textureUnit);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, vctVoxelTexture);
        shader.setUniformInt("u_vctVoxelTex", textureUnit);

        // VCT 座標轉換 uniforms
        shader.setUniformFloat("u_vctWorldMinX", vctWorldMinX);
        shader.setUniformFloat("u_vctWorldMinY", vctWorldMinY);
        shader.setUniformFloat("u_vctWorldMinZ", vctWorldMinZ);
        shader.setUniformFloat("u_vctVoxelSize", vctVoxelSize);
        shader.setUniformFloat("u_vctExtent", VCT_WORLD_EXTENT);
        shader.setUniformInt("u_vctResolution", VCT_RESOLUTION);

        // 錐體追蹤參數
        shader.setUniformFloat("u_vctConeAngle", vctConeAngle);
        shader.setUniformInt("u_vctConeCount", vctConeCount);
        shader.setUniformFloat("u_vctMaxDistance", vctMaxDistance);
        shader.setUniformFloat("u_vctStepMultiplier", vctStepMultiplier);
    }

    /**
     * 清理 VCT 資源。
     */
    public static void cleanupVCT() {
        if (vctVoxelTexture != 0) {
            GL11.glDeleteTextures(vctVoxelTexture);
            vctVoxelTexture = 0;
        }
        vctInitialized = false;
    }

    /** VCT 是否已初始化 */
    public static boolean isVCTInitialized() { return vctInitialized; }

    /** 取得 VCT 3D 紋理 ID */
    public static int getVCTVoxelTexture() { return vctVoxelTexture; }
}
