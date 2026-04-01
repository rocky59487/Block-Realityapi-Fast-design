package com.blockreality.api.client.render.pipeline;

import com.blockreality.api.client.render.BRRenderConfig;
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;

import java.util.EnumMap;
import java.util.Map;

/**
 * Framebuffer 管理器 — Iris 風格 main/alt buffer swap 機制。
 *
 * 管理所有固化管線使用的 FBO：
 *   - Shadow FBO（深度附件，2048²）
 *   - GBuffer FBO（5 色彩附件 + 深度）
 *   - Composite FBO（雙 ping-pong buffer）
 *   - Final FBO（輸出到螢幕）
 *
 * 採用 Iris 的 main/alt swap 模式：
 *   composite/deferred pass 從 "main" 讀取、寫入 "alt"，
 *   每個 pass 完成後 swap main ↔ alt。
 *
 * 生命週期：
 *   init() → 建立所有 FBO（在 ClientSetup 呼叫）
 *   resize(w,h) → 視窗大小改變時重建
 *   cleanup() → 釋放所有 GL 資源
 */
@OnlyIn(Dist.CLIENT)
@Deprecated(since = "Phase4", forRemoval = true)
public final class BRFramebufferManager {
    private BRFramebufferManager() {}

    // ─── FBO IDs ──────────────────────────────────────────
    private static int shadowFbo;
    private static int shadowDepthTex;

    private static int gbufferFbo;
    private static int[] gbufferColorTex; // [position, normal, albedo, material, emission]
    private static int gbufferDepthTex;

    // Ping-pong composite buffers (Iris main/alt swap)
    private static int compositeMainFbo;
    private static int compositeMainColorTex;
    private static int compositeAltFbo;
    private static int compositeAltColorTex;

    private static boolean isMainActive = true; // swap 狀態

    // TAA 歷史 buffer（保留前一幀渲染結果供 temporal reprojection）
    private static int taaHistoryFbo;
    private static int taaHistoryTex;

    private static int screenWidth, screenHeight;
    private static boolean initialized = false;

    // ─── 初始化 ─────────────────────────────────────────

    /**
     * 初始化所有 FBO。應在 GL context 可用後呼叫一次。
     */
    public static void init(int width, int height) {
        screenWidth = width;
        screenHeight = height;
        createShadowFbo();
        createGBufferFbo(width, height);
        createCompositeFbo(width, height);
        createTaaHistoryFbo(width, height);
        initialized = true;
    }

    /**
     * 視窗大小變更 — 重建尺寸相關的 FBO（shadow 不受影響）。
     */
    public static void resize(int width, int height) {
        if (width == screenWidth && height == screenHeight) return;
        screenWidth = width;
        screenHeight = height;
        deleteGBufferFbo();
        deleteCompositeFbo();
        deleteTaaHistoryFbo();
        createGBufferFbo(width, height);
        createCompositeFbo(width, height);
        createTaaHistoryFbo(width, height);
    }

    public static boolean isInitialized() { return initialized; }

    // ─── Shadow FBO ─────────────────────────────────────

    private static void createShadowFbo() {
        int res = BRRenderConfig.SHADOW_MAP_RESOLUTION;

        shadowFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, shadowFbo);

        shadowDepthTex = GlStateManager._genTexture();
        GlStateManager._bindTexture(shadowDepthTex);
        GL30.glTexImage2D(GL30.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT24,
            res, res, 0, GL30.GL_DEPTH_COMPONENT, GL30.GL_FLOAT, (java.nio.ByteBuffer) null);
        GL30.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_MIN_FILTER, GL30.GL_NEAREST);
        GL30.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_MAG_FILTER, GL30.GL_NEAREST);
        GL30.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_WRAP_S, GL30.GL_CLAMP_TO_EDGE);
        GL30.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_WRAP_T, GL30.GL_CLAMP_TO_EDGE);
        // 深度比較模式 — 陰影 PCF 取樣用
        GL30.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_COMPARE_MODE, GL30.GL_COMPARE_REF_TO_TEXTURE);
        GL30.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_COMPARE_FUNC, GL30.GL_LEQUAL);

        GL32.glFramebufferTexture(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, shadowDepthTex, 0);
        GL30.glDrawBuffers(GL30.GL_NONE);
        GL30.glReadBuffer(GL30.GL_NONE);

        checkFboStatus("Shadow");
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    // ─── GBuffer FBO ────────────────────────────────────

    private static void createGBufferFbo(int w, int h) {
        int count = BRRenderConfig.GBUFFER_ATTACHMENT_COUNT;
        gbufferFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, gbufferFbo);

        gbufferColorTex = new int[count];
        int[] drawBuffers = new int[count];

        for (int i = 0; i < count; i++) {
            gbufferColorTex[i] = GlStateManager._genTexture();
            GlStateManager._bindTexture(gbufferColorTex[i]);

            // attachment 0~2 使用 RGBA16F (HDR)，3~4 使用 RGBA8
            int internalFormat = (i < 3 && BRRenderConfig.HDR_ENABLED)
                ? GL30.GL_RGBA16F : GL30.GL_RGBA8;

            GL30.glTexImage2D(GL30.GL_TEXTURE_2D, 0, internalFormat,
                w, h, 0, GL30.GL_RGBA, GL30.GL_FLOAT, (java.nio.ByteBuffer) null);
            GL30.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_MIN_FILTER, GL30.GL_NEAREST);
            GL30.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_MAG_FILTER, GL30.GL_NEAREST);

            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,
                GL30.GL_COLOR_ATTACHMENT0 + i, GL30.GL_TEXTURE_2D, gbufferColorTex[i], 0);
            drawBuffers[i] = GL30.GL_COLOR_ATTACHMENT0 + i;
        }
        GL30.glDrawBuffers(drawBuffers);

        // 深度附件
        gbufferDepthTex = GlStateManager._genTexture();
        GlStateManager._bindTexture(gbufferDepthTex);
        GL30.glTexImage2D(GL30.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT24,
            w, h, 0, GL30.GL_DEPTH_COMPONENT, GL30.GL_FLOAT, (java.nio.ByteBuffer) null);
        GL30.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_MIN_FILTER, GL30.GL_NEAREST);
        GL30.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_MAG_FILTER, GL30.GL_NEAREST);
        GL32.glFramebufferTexture(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, gbufferDepthTex, 0);

        checkFboStatus("GBuffer");
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    // ─── Composite Ping-Pong FBO ───────────────────────

    private static void createCompositeFbo(int w, int h) {
        // Main
        compositeMainFbo = GL30.glGenFramebuffers();
        compositeMainColorTex = createSingleColorFbo(compositeMainFbo, w, h, "CompositeMain");

        // Alt
        compositeAltFbo = GL30.glGenFramebuffers();
        compositeAltColorTex = createSingleColorFbo(compositeAltFbo, w, h, "CompositeAlt");

        isMainActive = true;
    }

    private static int createSingleColorFbo(int fbo, int w, int h, String name) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);

        int tex = GlStateManager._genTexture();
        GlStateManager._bindTexture(tex);
        int fmt = BRRenderConfig.HDR_ENABLED ? GL30.GL_RGBA16F : GL30.GL_RGBA8;
        GL30.glTexImage2D(GL30.GL_TEXTURE_2D, 0, fmt,
            w, h, 0, GL30.GL_RGBA, GL30.GL_FLOAT, (java.nio.ByteBuffer) null);
        GL30.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_MIN_FILTER, GL30.GL_LINEAR);
        GL30.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_MAG_FILTER, GL30.GL_LINEAR);
        GL30.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_WRAP_S, GL30.GL_CLAMP_TO_EDGE);
        GL30.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_WRAP_T, GL30.GL_CLAMP_TO_EDGE);

        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,
            GL30.GL_COLOR_ATTACHMENT0, GL30.GL_TEXTURE_2D, tex, 0);

        checkFboStatus(name);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        return tex;
    }

    // ─── TAA 歷史 FBO ─────────────────────────────────

    private static void createTaaHistoryFbo(int w, int h) {
        taaHistoryFbo = GL30.glGenFramebuffers();
        taaHistoryTex = createSingleColorFbo(taaHistoryFbo, w, h, "TAAHistory");
    }

    private static void deleteTaaHistoryFbo() {
        GL30.glDeleteFramebuffers(taaHistoryFbo);
        GlStateManager._deleteTexture(taaHistoryTex);
    }

    /** 取得 TAA 歷史幀貼圖（前一幀渲染結果） */
    public static int getTaaHistoryTex() { return taaHistoryTex; }

    /**
     * 將來源貼圖的內容複製到 TAA 歷史 buffer。
     * 在 TAA pass 完成後呼叫，保留當前幀結果供下一幀 reprojection。
     * 使用 glBlitFramebuffer 進行 GPU-side 快速複製。
     * ★ FBO state restore fix: 儲存並復原之前的 FBO 綁定狀態，不要綁定到 0
     */
    public static void copyToTaaHistory(int srcTex) {
        // 儲存之前綁定的 FBO — 恢復時用
        int previousFbo = GL30.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);

        // 建立暫時 FBO 綁定來源貼圖作為 READ，歷史 FBO 作為 DRAW
        int tempReadFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, tempReadFbo);
        GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER,
            GL30.GL_COLOR_ATTACHMENT0, GL30.GL_TEXTURE_2D, srcTex, 0);

        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, taaHistoryFbo);

        GL30.glBlitFramebuffer(
            0, 0, screenWidth, screenHeight,
            0, 0, screenWidth, screenHeight,
            GL30.GL_COLOR_BUFFER_BIT, GL30.GL_NEAREST);

        // 復原之前的 FBO 綁定狀態，而非強制綁定到 0
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFbo);
        GL30.glDeleteFramebuffers(tempReadFbo);
    }

    // ─── Swap 機制（Iris main/alt pattern）──────────────

    /**
     * Swap main ↔ alt — composite pass 之間呼叫。
     * 上一個 pass 寫入的 alt 變成下一個 pass 的 main 輸入。
     */
    public static void swapCompositeBuffers() {
        isMainActive = !isMainActive;
    }

    /** 取得當前「讀取」端的 FBO 色彩貼圖 */
    public static int getCompositeReadTex() {
        return isMainActive ? compositeMainColorTex : compositeAltColorTex;
    }

    /** 取得當前「寫入」端的 FBO ID */
    public static int getCompositeWriteFbo() {
        return isMainActive ? compositeAltFbo : compositeMainFbo;
    }

    /** 取得當前「讀取」端的 FBO ID（用於 glBlitFramebuffer 降級路徑） */
    public static int getCompositeReadFbo() {
        return isMainActive ? compositeMainFbo : compositeAltFbo;
    }

    /** 取得當前「寫入」端的 FBO 色彩貼圖（用於 TAA 歷史 buffer 複製等場景） */
    public static int getCompositeWriteTex() {
        return isMainActive ? compositeAltColorTex : compositeMainColorTex;
    }

    // ─── 公開 Accessor ─────────────────────────────────

    public static int getShadowFbo() { return shadowFbo; }
    public static int getShadowDepthTex() { return shadowDepthTex; }
    public static int getGbufferFbo() { return gbufferFbo; }
    public static int getGbufferDepthTex() { return gbufferDepthTex; }
    /** GBuffer FBO 是否已建立且可用 */
    public static boolean isGbufferReady() { return initialized && gbufferFbo > 0; }
    public static int getScreenWidth() { return screenWidth; }
    public static int getScreenHeight() { return screenHeight; }

    /**
     * 取得 GBuffer 色彩附件。
     * @param index 0=position, 1=normal, 2=albedo, 3=material, 4=emission
     */
    public static int getGbufferColorTex(int index) {
        if (gbufferColorTex == null || index < 0 || index >= gbufferColorTex.length) return 0;
        return gbufferColorTex[index];
    }

    // ─── 清除 ───────────────────────────────────────────

    public static void cleanup() {
        if (!initialized) return;
        GL30.glDeleteFramebuffers(shadowFbo);
        GlStateManager._deleteTexture(shadowDepthTex);
        deleteGBufferFbo();
        deleteCompositeFbo();
        deleteTaaHistoryFbo();
        initialized = false;
    }

    private static void deleteGBufferFbo() {
        GL30.glDeleteFramebuffers(gbufferFbo);
        if (gbufferColorTex != null) {
            for (int tex : gbufferColorTex) GlStateManager._deleteTexture(tex);
        }
        GlStateManager._deleteTexture(gbufferDepthTex);
    }

    private static void deleteCompositeFbo() {
        GL30.glDeleteFramebuffers(compositeMainFbo);
        GlStateManager._deleteTexture(compositeMainColorTex);
        GL30.glDeleteFramebuffers(compositeAltFbo);
        GlStateManager._deleteTexture(compositeAltColorTex);
    }

    private static void checkFboStatus(String name) {
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException(
                "[BR Render] Framebuffer '" + name + "' 不完整: 0x" +
                Integer.toHexString(status));
        }
    }
}
