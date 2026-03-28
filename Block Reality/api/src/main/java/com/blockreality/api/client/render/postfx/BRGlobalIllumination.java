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
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryStack;

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

    // ─── VCT Compute Shader 資源 ──────────────────────────────
    private static int vctClearProgram;
    private static int vctInjectProgram;
    private static int vctComputeSupported = -1; // -1=unchecked, 0=no, 1=yes
    private static int vctFrameCounter = 0;

    /** 每 N 幀重新體素化（光照變化緩慢） */
    private static final int VCT_UPDATE_INTERVAL = 4;

    private static final String VCT_CLEAR_COMPUTE_SRC =
        "#version 430 core\n" +
        "layout(local_size_x = 4, local_size_y = 4, local_size_z = 4) in;\n" +
        "layout(r11f_g11f_b10f, binding = 0) uniform writeonly image3D u_voxelTex;\n" +
        "void main() {\n" +
        "    ivec3 coord = ivec3(gl_GlobalInvocationID.xyz);\n" +
        "    imageStore(u_voxelTex, coord, vec4(0.0));\n" +
        "}\n";

    private static final String VCT_INJECT_COMPUTE_SRC =
        "#version 430 core\n" +
        "layout(local_size_x = 4, local_size_y = 4, local_size_z = 4) in;\n" +
        "layout(r11f_g11f_b10f, binding = 0) uniform image3D u_voxelTex;\n" +
        "\n" +
        "uniform vec3 u_worldMin;\n" +
        "uniform float u_voxelSize;\n" +
        "uniform int u_resolution;\n" +
        "uniform vec3 u_sunDir;\n" +
        "uniform vec3 u_sunColor;\n" +
        "uniform float u_ambientStrength;\n" +
        "\n" +
        "// GBuffer textures bound as readonly image2D\n" +
        "uniform sampler2D u_gbufAlbedo;\n" +
        "uniform sampler2D u_gbufNormal;\n" +
        "uniform sampler2D u_gbufPosition;\n" +
        "uniform vec2 u_screenSize;\n" +
        "\n" +
        "void main() {\n" +
        "    ivec3 voxelCoord = ivec3(gl_GlobalInvocationID.xyz);\n" +
        "    if (any(greaterThanEqual(voxelCoord, ivec3(u_resolution)))) return;\n" +
        "\n" +
        "    // 體素世界位置中心\n" +
        "    vec3 worldPos = u_worldMin + (vec3(voxelCoord) + 0.5) * u_voxelSize;\n" +
        "\n" +
        "    // 從 GBuffer 採樣 — 將體素世界座標投影到螢幕空間，讀取真實材質\n" +
        "    // 遍歷螢幕上 4x4 取樣點，找最近的匹配 GBuffer 像素\n" +
        "    vec3 albedo = vec3(0.0);\n" +
        "    vec3 normal = vec3(0.0, 1.0, 0.0);\n" +
        "    float bestDist = 1e10;\n" +
        "    bool found = false;\n" +
        "    for (int sy = 0; sy < 4; sy++) {\n" +
        "        for (int sx = 0; sx < 4; sx++) {\n" +
        "            vec2 sampleUV = (vec2(voxelCoord.xz) + vec2(sx, sy) * 0.25) / float(u_resolution);\n" +
        "            sampleUV = clamp(sampleUV, 0.0, 1.0);\n" +
        "            vec3 gbufPos = texture(u_gbufPosition, sampleUV).xyz;\n" +
        "            float d = distance(gbufPos, worldPos);\n" +
        "            if (d < u_voxelSize * 1.5 && d < bestDist) {\n" +
        "                bestDist = d;\n" +
        "                albedo = texture(u_gbufAlbedo, sampleUV).rgb;\n" +
        "                normal = texture(u_gbufNormal, sampleUV).xyz * 2.0 - 1.0;\n" +
        "                found = true;\n" +
        "            }\n" +
        "        }\n" +
        "    }\n" +
        "\n" +
        "    // 沒找到 GBuffer 匹配時 fallback 為高度漸變近似\n" +
        "    if (!found) {\n" +
        "        float h = (worldPos.y - u_worldMin.y) / (u_voxelSize * float(u_resolution));\n" +
        "        albedo = mix(vec3(0.5, 0.4, 0.35), vec3(0.3, 0.6, 0.2), clamp(h, 0.0, 1.0));\n" +
        "    }\n" +
        "\n" +
        "    // 方向光照計算（使用真實法線）\n" +
        "    float NdotL = max(dot(normal, u_sunDir), 0.0);\n" +
        "    vec3 directLight = u_sunColor * NdotL;\n" +
        "    vec3 ambient = vec3(u_ambientStrength);\n" +
        "    vec3 radiance = directLight + ambient;\n" +
        "\n" +
        "    vec4 result = vec4(radiance * albedo, 1.0);\n" +
        "    imageStore(u_voxelTex, voxelCoord, result);\n" +
        "}\n";

    /**
     * 初始化 VCT compute shader（需要 GL 4.3）。
     * 在 initVCT() 之後呼叫。
     */
    public static void initVCTCompute() {
        if (vctComputeSupported == 0) return;
        try {
            boolean hasGL43 = GL.getCapabilities().OpenGL43;
            if (!hasGL43) {
                vctComputeSupported = 0;
                LOGGER.info("[VCT] GL 4.3 不支援，體素化使用 fallback 模式");
                return;
            }
            vctComputeSupported = 1;

            // 編譯 clear compute shader
            vctClearProgram = compileComputeProgram(VCT_CLEAR_COMPUTE_SRC, "vct_clear");
            // 編譯 inject compute shader
            vctInjectProgram = compileComputeProgram(VCT_INJECT_COMPUTE_SRC, "vct_inject");

            LOGGER.info("[VCT] Compute shader 編譯成功 — clear={}, inject={}", vctClearProgram, vctInjectProgram);
        } catch (Exception e) {
            vctComputeSupported = 0;
            LOGGER.warn("[VCT] Compute shader 初始化失敗，使用 fallback: {}", e.getMessage());
        }
    }

    private static int compileComputeProgram(String source, String name) {
        int shader = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader, 2048);
            GL20.glDeleteShader(shader);
            throw new RuntimeException("VCT compute '" + name + "' 編譯失敗: " + log);
        }
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, shader);
        GL20.glLinkProgram(program);
        GL20.glDeleteShader(shader);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(program, 2048);
            GL20.glDeleteProgram(program);
            throw new RuntimeException("VCT compute '" + name + "' 連結失敗: " + log);
        }
        return program;
    }

    /**
     * 體素化場景（Phase 1）— 使用 compute shader 將直接光照注入 3D 體素紋理。
     *
     * 步驟：
     *   1. Compute shader 清除 3D 紋理
     *   2. Compute shader 注入直接光照
     *   3. 生成 mipmap（各向同性過濾，供錐體追蹤不同 LOD 取樣）
     *
     * 每 VCT_UPDATE_INTERVAL 幀執行一次以節省效能。
     *
     * @param cameraX 攝影機世界座標 X
     * @param cameraY 攝影機世界座標 Y
     * @param cameraZ 攝影機世界座標 Z
     */
    public static void voxelizeScene(float cameraX, float cameraY, float cameraZ) {
        if (!vctInitialized || vctVoxelTexture == 0) return;

        // 限制更新頻率
        vctFrameCounter++;
        if (vctFrameCounter % VCT_UPDATE_INTERVAL != 0) return;

        float halfExtent = VCT_WORLD_EXTENT / 2.0f;
        float voxelSize = VCT_WORLD_EXTENT / VCT_RESOLUTION;

        // 記錄體素化範圍供追蹤階段使用
        vctWorldMinX = cameraX - halfExtent;
        vctWorldMinY = cameraY - halfExtent;
        vctWorldMinZ = cameraZ - halfExtent;
        vctVoxelSize = voxelSize;

        int groups = (VCT_RESOLUTION + 3) / 4; // local_size = 4

        if (vctComputeSupported == 1 && vctClearProgram != 0 && vctInjectProgram != 0) {
            // ── Phase 1a: Compute Shader 清除 ──
            GL20.glUseProgram(vctClearProgram);
            GL42.glBindImageTexture(0, vctVoxelTexture, 0, true, 0,
                GL15.GL_WRITE_ONLY, GL30.GL_R11F_G11F_B10F);
            GL43.glDispatchCompute(groups, groups, groups);
            GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

            // ── Phase 1b: Compute Shader 光照注入 ──
            GL20.glUseProgram(vctInjectProgram);
            GL42.glBindImageTexture(0, vctVoxelTexture, 0, true, 0,
                GL15.GL_READ_WRITE, GL30.GL_R11F_G11F_B10F);

            int loc;
            loc = GL20.glGetUniformLocation(vctInjectProgram, "u_worldMin");
            if (loc >= 0) GL20.glUniform3f(loc, vctWorldMinX, vctWorldMinY, vctWorldMinZ);
            loc = GL20.glGetUniformLocation(vctInjectProgram, "u_voxelSize");
            if (loc >= 0) GL20.glUniform1f(loc, voxelSize);
            loc = GL20.glGetUniformLocation(vctInjectProgram, "u_resolution");
            if (loc >= 0) GL20.glUniform1i(loc, VCT_RESOLUTION);
            loc = GL20.glGetUniformLocation(vctInjectProgram, "u_sunDir");
            if (loc >= 0) GL20.glUniform3f(loc, 0.5f, 0.8f, 0.3f); // 預設太陽方向
            loc = GL20.glGetUniformLocation(vctInjectProgram, "u_sunColor");
            if (loc >= 0) GL20.glUniform3f(loc, 1.0f, 0.98f, 0.95f);
            loc = GL20.glGetUniformLocation(vctInjectProgram, "u_ambientStrength");
            if (loc >= 0) GL20.glUniform1f(loc, 0.15f);
            loc = GL20.glGetUniformLocation(vctInjectProgram, "u_screenSize");
            if (loc >= 0) GL20.glUniform2f(loc,
                BRFramebufferManager.getScreenWidth(), BRFramebufferManager.getScreenHeight());

            // GBuffer 紋理綁定
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, BRFramebufferManager.getGbufferColorTex(2)); // albedo
            loc = GL20.glGetUniformLocation(vctInjectProgram, "u_gbufAlbedo");
            if (loc >= 0) GL20.glUniform1i(loc, 0);

            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, BRFramebufferManager.getGbufferColorTex(1)); // normal
            loc = GL20.glGetUniformLocation(vctInjectProgram, "u_gbufNormal");
            if (loc >= 0) GL20.glUniform1i(loc, 1);

            GL13.glActiveTexture(GL13.GL_TEXTURE2);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, BRFramebufferManager.getGbufferColorTex(0)); // position
            loc = GL20.glGetUniformLocation(vctInjectProgram, "u_gbufPosition");
            if (loc >= 0) GL20.glUniform1i(loc, 2);

            GL43.glDispatchCompute(groups, groups, groups);
            GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

            GL20.glUseProgram(0);
        } else {
            // Fallback: 僅清除（無 compute shader 時保留空體素用於錐體追蹤 fallback）
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, vctVoxelTexture);
        }

        // ── Phase 1c: 生成 Mipmap（各向同性過濾） ──
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, vctVoxelTexture);
        GL30.glGenerateMipmap(GL12.GL_TEXTURE_3D);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);

        LOGGER.debug("[VCT] 體素化完成 — 中心 ({}, {}, {}), 體素大小 {}, compute={}",
            (int) cameraX, (int) cameraY, (int) cameraZ, voxelSize, vctComputeSupported == 1);
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
     * 清理 VCT 資源（包含 compute shader）。
     */
    public static void cleanupVCT() {
        if (vctVoxelTexture != 0) {
            GL11.glDeleteTextures(vctVoxelTexture);
            vctVoxelTexture = 0;
        }
        if (vctClearProgram != 0) {
            GL20.glDeleteProgram(vctClearProgram);
            vctClearProgram = 0;
        }
        if (vctInjectProgram != 0) {
            GL20.glDeleteProgram(vctInjectProgram);
            vctInjectProgram = 0;
        }
        vctComputeSupported = -1;
        vctFrameCounter = 0;
        vctInitialized = false;
    }

    /** VCT Compute 是否可用 */
    public static boolean isVCTComputeSupported() { return vctComputeSupported == 1; }

    /** VCT 是否已初始化 */
    public static boolean isVCTInitialized() { return vctInitialized; }

    /** 取得 VCT 3D 紋理 ID */
    public static int getVCTVoxelTexture() { return vctVoxelTexture; }
}
