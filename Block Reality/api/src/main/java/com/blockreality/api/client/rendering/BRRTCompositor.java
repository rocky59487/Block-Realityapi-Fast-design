package com.blockreality.api.client.rendering;

import com.blockreality.api.client.render.pipeline.BRRenderTier;
import com.blockreality.api.client.render.rt.BRVulkanInterop;
import com.blockreality.api.client.render.rt.RTEffect;
import com.blockreality.api.client.rendering.vulkan.*;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BR RT Compositor — Phase 3 核心：整合 Vulkan RT 效果到 OpenGL 管線。
 *
 * <p>在 TIER_3 模式下，此類別協調：
 * <ol>
 *   <li>GBuffer 填充（由 LOD pass 寫入 g_Albedo / g_Normal / g_Material）</li>
 *   <li>Vulkan RT dispatch（raygen → RT shadows + reflections）</li>
 *   <li>SVGF/NRD 降噪</li>
 *   <li>GL/VK interop 匯出降噪結果為 OpenGL texture</li>
 *   <li>全螢幕 composite pass（將 RT 結果混合到現有後處理鏈）</li>
 * </ol>
 *
 * <p>在 TIER_0/1/2 模式下，完全跳過（降級至傳統 CSM + SSR）。
 *
 * @author Block Reality Team
 */
@OnlyIn(Dist.CLIENT)
public final class BRRTCompositor {

    private static final Logger LOG = LoggerFactory.getLogger("BR-RTCompositor");

    // ── 子系統 ────────────────────────────────────────────────────────
    private final VkContext            vkContext;
    private final VkAccelStructBuilder accelBuilder;
    private final VkRTPipeline         rtPipeline;
    private final VkGBuffer            gBuffer;
    private final BRNRDDenoiser        denoiser;
    private final com.blockreality.api.client.rendering.vulkan.VkRTAO rtaoPipeline;

    // ── 合成 shader（fullscreen quad） ────────────────────────────────
    private int compositeProgram  = 0;
    private int quadVAO           = 0;
    private int quadVBO           = 0;

    // ── 狀態 ──────────────────────────────────────────────────────────
    private boolean initialized   = false;
    private long    frameIndex     = 0L;
    private int     screenWidth    = 0;
    private int     screenHeight   = 0;

    // ── 單例 ──────────────────────────────────────────────────────────
    private static BRRTCompositor INSTANCE;

    public static BRRTCompositor getInstance() {
        if (INSTANCE == null) INSTANCE = new BRRTCompositor();
        return INSTANCE;
    }

    private BRRTCompositor() {
        this.vkContext    = new VkContext();
        this.accelBuilder = new VkAccelStructBuilder(vkContext);
        this.rtPipeline   = new VkRTPipeline(vkContext, accelBuilder);
        this.gBuffer      = new VkGBuffer();
        this.denoiser     = new BRNRDDenoiser(vkContext);
        this.rtaoPipeline = new com.blockreality.api.client.rendering.vulkan.VkRTAO();
    }

    // ─────────────────────────────────────────────────────────────────
    //  生命週期
    // ─────────────────────────────────────────────────────────────────

    /**
     * 初始化 RT Compositor。
     * 若 Vulkan RT 不可用（TIER < 3），靜默返回（不影響遊戲）。
     *
     * @param width  螢幕寬度
     * @param height 螢幕高度
     */
    public void init(int width, int height) {
        if (BRRenderTier.getCurrentTier().ordinal() < BRRenderTier.Tier.TIER_3.ordinal()) {
            LOG.info("BRRTCompositor: TIER < 3, RT disabled");
            return;
        }

        this.screenWidth  = width;
        this.screenHeight = height;

        // 初始化 Vulkan 上下文
        if (!vkContext.init()) {
            LOG.warn("BRRTCompositor: Vulkan RT not available, compositor disabled");
            return;
        }

        // 初始化 BLAS/TLAS 建構器
        accelBuilder.init();
        if (!accelBuilder.isInitialized()) {
            LOG.warn("BRRTCompositor: VkAccelStructBuilder init failed");
            vkContext.cleanup();
            return;
        }

        // 向 BRVoxelLODManager 注冊 BLAS 更新器
        com.blockreality.api.client.rendering.lod.BRVoxelLODManager.getInstance()
            .setBLASUpdater(accelBuilder);

        // 初始化 RT pipeline
        rtPipeline.init(width, height);

        // 初始化 GBuffer（OpenGL FBO）
        gBuffer.init(width, height);

        // 初始化降噪器
        denoiser.init(width, height);

        // 初始化 RTAO Ray Query pass
        rtaoPipeline.init(width, height);

        // 初始化合成 shader
        initCompositeShader();

        initialized = true;
        LOG.info("BRRTCompositor initialized ({}×{}, VK RT ready)", width, height);
    }

    public void cleanup() {
        if (!initialized) return;

        denoiser.cleanup();
        rtPipeline.cleanup();
        rtaoPipeline.cleanup();
        gBuffer.cleanup();
        accelBuilder.cleanup();
        vkContext.cleanup();

        if (compositeProgram != 0) { GL20.glDeleteProgram(compositeProgram); compositeProgram = 0; }
        if (quadVAO != 0) { org.lwjgl.opengl.GL30.glDeleteVertexArrays(quadVAO); quadVAO = 0; }
        if (quadVBO != 0) { org.lwjgl.opengl.GL15.glDeleteBuffers(quadVBO); quadVBO = 0; }

        initialized  = false;
        INSTANCE     = null;
        LOG.info("BRRTCompositor cleanup complete");
    }

    public void resize(int width, int height) {
        if (!initialized) return;
        this.screenWidth  = width;
        this.screenHeight = height;
        gBuffer.resize(width, height);
        rtPipeline.resize(width, height);
        denoiser.cleanup();
        denoiser.init(width, height);
        rtaoPipeline.cleanup();
        rtaoPipeline.init(width, height);
    }

    // ─────────────────────────────────────────────────────────────────
    //  每幀 RT 渲染流程
    // ─────────────────────────────────────────────────────────────────

    /**
     * 執行完整 RT 渲染流程。
     * 由 ForgeRenderEventBridge.AFTER_TRANSLUCENT_BLOCKS 觸發。
     *
     * @param projMatrix 投影矩陣
     * @param viewMatrix 視圖矩陣
     */
    public void executeRTPass(Matrix4f projMatrix, Matrix4f viewMatrix) {
        if (!initialized) return;

        frameIndex++;

        // Step 1: 更新 TLAS（BLAS dirty 已由 BRVoxelLODManager.updateBLAS() 處理）
        accelBuilder.updateTLAS();
        long tlas = accelBuilder.getTLASHandle();

        // Step 2: 發射 RT 光線（shadows + reflections）
        rtPipeline.dispatchRays(projMatrix, viewMatrix, tlas);

        // Step 2.5: 發射 RTAO Ray Query
        // 同時檢查 RTEffect.RTAO（預算控制）與 BRRTSettings（使用者設定）
        int aoTex = 0;
        if (rtPipeline.isEffectEnabled(RTEffect.RTAO)) {
            org.joml.Matrix4f vp    = new org.joml.Matrix4f(projMatrix).mul(viewMatrix);
            org.joml.Matrix4f invVP = vp.invert(new org.joml.Matrix4f());
            aoTex = rtaoPipeline.dispatchAO(
                gBuffer.getDepthBuffer(), gBuffer.getNormalTex(), invVP, tlas, frameIndex
            );
        }

        // Step 3: 同步 Vulkan → OpenGL（VK_KHR_external_memory barrier）
        BRVulkanInterop.syncVKToGL();

        // Step 4: 取得 RT 輸出紋理（已透過 interop 匯出的 GL texture）
        int rtOutputTex = BRVulkanInterop.getGLRTOutputTexture();

        // Step 5: 降噪
        // SVGF_DENOISE 關閉時跳過降噪（省 bandwidth，直接用原始 RT 輸出）
        int denoisedTex = rtOutputTex;
        if (rtPipeline.isEffectEnabled(RTEffect.SVGF_DENOISE) && gBuffer.isInitialized()) {
            denoisedTex = denoiser.denoise(
                rtOutputTex,
                gBuffer.getDepthBuffer(),
                gBuffer.getNormalTex(),
                gBuffer.getMotionTex(), // motion vector — g_Motion RG16F attachment
                rtOutputTex,
                frameIndex
            );
        }

        // Step 6: 合成 RT 結果到 OpenGL backbuffer
        // REFLECTIONS 關閉時 blend factor = 0（陰影仍可合成）
        if (denoisedTex != 0) {
            float reflBlend = rtPipeline.isEffectEnabled(RTEffect.REFLECTIONS) ? 1.0f : 0.0f;
            compositeRTResult(denoisedTex, reflBlend);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  全螢幕合成
    // ─────────────────────────────────────────────────────────────────

    /**
     * 使用全螢幕 quad，將 RT 結果以加法混合疊加到 backbuffer。
     *
     * <p>混合模式：ONE + ONE（RT 結果 = direct lighting 補充）
     * 或 ALPHA blend（RT shadow factor 調製現有顏色）。
     *
     * @param denoisedTex RT 輸出 GL texture（降噪後或原始）
     * @param reflBlend   反射混合係數（{@link RTEffect#REFLECTIONS} 關閉時傳 0.0f）
     */
    private void compositeRTResult(int denoisedTex, float reflBlend) {
        if (compositeProgram == 0 || quadVAO == 0) return;

        // 保存 GL 狀態
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        GL20.glUseProgram(compositeProgram);

        // 綁定降噪後的 RT 輸出
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, denoisedTex);
        GL20.glUniform1i(GL20.glGetUniformLocation(compositeProgram, "u_RTResult"), 0);
        GL20.glUniform1f(GL20.glGetUniformLocation(compositeProgram, "u_RTBlendFactor"), reflBlend);

        // 繪製全螢幕 quad
        org.lwjgl.opengl.GL30.glBindVertexArray(quadVAO);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
        org.lwjgl.opengl.GL30.glBindVertexArray(0);

        GL20.glUseProgram(0);

        // 還原 GL 狀態
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_BLEND);
    }

    // ─────────────────────────────────────────────────────────────────
    //  Getters（供 ForgeRenderEventBridge 使用）
    // ─────────────────────────────────────────────────────────────────

    public VkGBuffer      getGBuffer()      { return gBuffer; }
    public VkContext      getVkContext()    { return vkContext; }
    public boolean        isInitialized()  { return initialized; }

    /**
     * 取得底層 {@link VkRTPipeline}，供需要直接存取管線狀態的子系統使用。
     * 節點系統請優先使用 {@link #enableRTEffect} / {@link #disableRTEffect} facade。
     *
     * @return rtPipeline 實例（初始化前仍可取得，但操作無效）
     */
    public VkRTPipeline getRTPipeline() { return rtPipeline; }

    // ─────────────────────────────────────────────────────────────────
    //  RTEffect 預算控制 facade（供節點編輯器 VulkanRTConfigNode 使用）
    // ─────────────────────────────────────────────────────────────────

    /**
     * 啟用指定 RT 效果。
     * 若 Compositor 尚未初始化，操作靜默忽略（不影響遊戲）。
     *
     * @param effect 要啟用的 {@link RTEffect}
     */
    public void enableRTEffect(RTEffect effect) {
        if (initialized) rtPipeline.enableEffect(effect);
    }

    /**
     * 停用指定 RT 效果。
     * 若 Compositor 尚未初始化，操作靜默忽略。
     *
     * @param effect 要停用的 {@link RTEffect}
     */
    public void disableRTEffect(RTEffect effect) {
        if (initialized) rtPipeline.disableEffect(effect);
    }

    /**
     * 查詢指定 RT 效果是否啟用。
     * Compositor 未初始化時一律回傳 {@code false}。
     *
     * @param effect 要查詢的 {@link RTEffect}
     * @return {@code true} 若已啟用
     */
    public boolean isRTEffectEnabled(RTEffect effect) {
        return initialized && rtPipeline.isEffectEnabled(effect);
    }

    // ─────────────────────────────────────────────────────────────────
    //  內部：合成 shader 與 quad
    // ─────────────────────────────────────────────────────────────────

    private void initCompositeShader() {
        // 內嵌 GLSL（避免額外資源檔依賴）
        String vert = """
            #version 330 core
            const vec2 POSITIONS[6] = vec2[](
                vec2(-1,-1), vec2(1,-1), vec2(-1,1),
                vec2(-1,1),  vec2(1,-1), vec2(1,1)
            );
            const vec2 UVS[6] = vec2[](
                vec2(0,0), vec2(1,0), vec2(0,1),
                vec2(0,1), vec2(1,0), vec2(1,1)
            );
            out vec2 v_UV;
            void main() {
                gl_Position = vec4(POSITIONS[gl_VertexID], 0.0, 1.0);
                v_UV = UVS[gl_VertexID];
            }
            """;

        String frag = """
            #version 330 core
            in vec2 v_UV;
            uniform sampler2D u_RTResult;
            uniform float u_RTBlendFactor;
            out vec4 out_Color;
            void main() {
                vec4 rt = texture(u_RTResult, v_UV);
                // R = shadow factor (0=shadow, 1=lit)
                // GBA = reflection radiance
                float shadowFactor = rt.r;
                vec3  reflection   = rt.gba;
                // 輸出：調製陰影 + 加上反射（使用 alpha 作為反射強度）
                out_Color = vec4(reflection, (1.0 - shadowFactor) * u_RTBlendFactor * 0.5);
            }
            """;

        try {
            int vs = compileShader(GL20.GL_VERTEX_SHADER, vert);
            int fs = compileShader(GL20.GL_FRAGMENT_SHADER, frag);
            compositeProgram = GL20.glCreateProgram();
            GL20.glAttachShader(compositeProgram, vs);
            GL20.glAttachShader(compositeProgram, fs);
            GL20.glLinkProgram(compositeProgram);
            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);

            // 建立空 VAO（全螢幕 quad 用 gl_VertexID 產生，不需要 VBO）
            quadVAO = org.lwjgl.opengl.GL30.glGenVertexArrays();
            LOG.debug("Composite shader program created: {}", compositeProgram);
        } catch (Exception e) {
            LOG.error("Failed to create composite shader", e);
        }
    }

    private static int compileShader(int type, String src) {
        int id = GL20.glCreateShader(type);
        GL20.glShaderSource(id, src);
        GL20.glCompileShader(id);
        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            throw new RuntimeException("Shader compile error: " + GL20.glGetShaderInfoLog(id));
        }
        return id;
    }
}
