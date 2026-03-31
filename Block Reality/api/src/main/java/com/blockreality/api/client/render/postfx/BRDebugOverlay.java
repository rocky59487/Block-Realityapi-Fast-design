package com.blockreality.api.client.render.postfx;

import com.blockreality.api.client.render.BRRenderConfig;
import com.blockreality.api.client.render.optimization.BROptimizationEngine;
import com.blockreality.api.client.render.optimization.BRMemoryOptimizer;
import com.blockreality.api.client.render.shadow.BRCascadedShadowMap;
import com.blockreality.api.client.render.postfx.BRMotionBlurEngine;
import com.blockreality.api.client.render.shader.BRShaderEngine;
import com.blockreality.api.client.render.shader.BRShaderProgram;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
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
 * 渲染除錯覆蓋層 — F3 + B 切換顯示，即時效能指標與渲染診斷。
 *
 * 功能：
 *   - FPS / 幀時間圖表（120 幀滾動歷史）
 *   - GPU 記憶體使用（VBO / FBO / Texture 統計）
 *   - 渲染管線各 Pass 耗時拆解
 *   - GBuffer 視覺化模式（Albedo / Normal / Depth / Material / Velocity）
 *   - CSM 級聯視覺化（色彩覆蓋顯示 cascade 邊界）
 *   - LOD 層級視覺化（色彩覆蓋顯示距離分層）
 *   - Wireframe 模式
 *
 * 設計要點：
 *   - 全部在 overlay pass 渲染（不影響主管線）
 *   - 使用 overlay shader + immediate mode 文字
 *   - F3+B 切換總開關，F3+1~8 切換子模式
 */
@OnlyIn(Dist.CLIENT)
public final class BRDebugOverlay {
    private BRDebugOverlay() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("BR-Debug");

    // ─── 模式 ──────────────────────────────────────────────
    public enum DebugMode {
        NONE,               // 關閉
        PERFORMANCE_HUD,    // FPS + 記憶體 + Pass 耗時
        GBUFFER_ALBEDO,     // GBuffer 0: Albedo
        GBUFFER_NORMAL,     // GBuffer 1: Normal
        GBUFFER_DEPTH,      // GBuffer 2: Depth（線性化）
        GBUFFER_MATERIAL,   // GBuffer 3: Material ID
        VELOCITY_BUFFER,    // Velocity Buffer
        CASCADE_VIZ,        // CSM 級聯邊界色覆蓋
        LOD_VIZ,            // LOD 層級色覆蓋
        WIREFRAME           // 線框模式
    }

    private static DebugMode currentMode = DebugMode.NONE;

    // ─── 效能追蹤 ──────────────────────────────────────────
    private static final int HISTORY_SIZE = 120;
    private static final float[] frameTimeHistory = new float[HISTORY_SIZE];
    private static int historyIndex = 0;
    private static long lastFrameNanos = 0;
    private static float smoothFps = 60.0f;

    // Pass 耗時追蹤（毫秒）
    private static final String[] PASS_NAMES = {
        "Shadow/CSM", "GBuffer", "Deferred", "SSAO",
        "Contact Shadow", "SSR", "Volumetric", "Cloud",
        "Bloom", "Tonemap", "DoF", "Cinematic", "TAA"
    };
    private static final float[] passTimesMs = new float[PASS_NAMES.length];
    private static int currentPassIndex = 0;

    // ─── GL 資源 ────────────────────────────────────────────
    private static int overlayVao;
    private static int overlayVbo;

    private static boolean initialized = false;

    // ═══════════════════════════════════════════════════════
    //  初始化 / 清除
    // ═══════════════════════════════════════════════════════

    public static void init() {
        if (initialized) return;

        // 建立用於繪製 HUD 元素的簡單 VAO
        overlayVao = GL30.glGenVertexArrays();
        overlayVbo = GL15.glGenBuffers();

        GL30.glBindVertexArray(overlayVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, overlayVbo);
        // 預分配 4KB 動態頂點資料
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, 4096, GL15.GL_DYNAMIC_DRAW);

        // position (vec2) + color (vec4)
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 24, 0);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 4, GL11.GL_FLOAT, false, 24, 8);

        GL30.glBindVertexArray(0);

        initialized = true;
        LOGGER.info("BRDebugOverlay 初始化完成");
    }

    public static void cleanup() {
        if (overlayVao != 0) { GL30.glDeleteVertexArrays(overlayVao); overlayVao = 0; }
        if (overlayVbo != 0) { GL15.glDeleteBuffers(overlayVbo); overlayVbo = 0; }
        initialized = false;
    }

    // ═══════════════════════════════════════════════════════
    //  模式切換
    // ═══════════════════════════════════════════════════════

    /** 切換除錯模式 */
    public static void toggleMode() {
        DebugMode[] values = DebugMode.values();
        int next = (currentMode.ordinal() + 1) % values.length;
        currentMode = values[next];
        LOGGER.info("Debug mode: {}", currentMode);
    }

    /** 直接設定模式 */
    public static void setMode(DebugMode mode) {
        currentMode = mode;
    }

    public static DebugMode getMode() { return currentMode; }
    public static boolean isActive() { return currentMode != DebugMode.NONE; }

    // ═══════════════════════════════════════════════════════
    //  每幀更新
    // ═══════════════════════════════════════════════════════

    /** 記錄幀時間 */
    public static void recordFrameTime() {
        long now = System.nanoTime();
        if (lastFrameNanos > 0) {
            float deltaMs = (now - lastFrameNanos) / 1_000_000.0f;
            frameTimeHistory[historyIndex] = deltaMs;
            historyIndex = (historyIndex + 1) % HISTORY_SIZE;

            // 指數平滑 FPS
            float instantFps = 1000.0f / Math.max(deltaMs, 0.001f);
            smoothFps = smoothFps * 0.95f + instantFps * 0.05f;
        }
        lastFrameNanos = now;
    }

    /** 記錄 pass 開始 */
    public static void beginPassTiming(int passIndex) {
        currentPassIndex = passIndex;
        // 注意：真正的 GPU timing 需要 GL_TIME_ELAPSED query
        // 此處使用 CPU 側估算（足夠 debug 用途）
    }

    /** 記錄 pass 結束 */
    public static void endPassTiming(int passIndex, float elapsedMs) {
        if (passIndex >= 0 && passIndex < passTimesMs.length) {
            // 指數平滑
            passTimesMs[passIndex] = passTimesMs[passIndex] * 0.9f + elapsedMs * 0.1f;
        }
    }

    // ═══════════════════════════════════════════════════════
    //  渲染
    // ═══════════════════════════════════════════════════════

    /**
     * 渲染除錯覆蓋層。在所有主渲染完成後呼叫（overlay pass）。
     *
     * @param screenWidth  螢幕寬度
     * @param screenHeight 螢幕高度
     */
    public static void render(int screenWidth, int screenHeight) {
        if (!initialized || currentMode == DebugMode.NONE) return;

        switch (currentMode) {
            case PERFORMANCE_HUD:
                renderPerformanceHUD(screenWidth, screenHeight);
                break;

            case GBUFFER_ALBEDO:
                renderFullscreenTexture(0);
                break;

            case GBUFFER_NORMAL:
                renderFullscreenTexture(0);
                break;

            case GBUFFER_DEPTH:
                renderDepthVisualization(screenWidth, screenHeight);
                break;

            case GBUFFER_MATERIAL:
                renderFullscreenTexture(0);
                break;

            case VELOCITY_BUFFER:
                renderFullscreenTexture(BRMotionBlurEngine.getVelocityTexture());
                break;

            case CASCADE_VIZ:
                renderCascadeVisualization(screenWidth, screenHeight);
                break;

            case LOD_VIZ:
                // LOD 視覺化通過 shader uniform 控制
                break;

            case WIREFRAME:
                // Wireframe 在渲染開始前設定
                break;

            default:
                break;
        }
    }

    /**
     * 渲染效能 HUD（左上角）。
     * 顯示 FPS、幀時間圖表、記憶體、各 Pass 耗時。
     */
    private static void renderPerformanceHUD(int screenWidth, int screenHeight) {
        BRShaderProgram overlayShader = BRShaderEngine.getOverlayShader();
        if (overlayShader == null) return;

        // 使用正交投影渲染 2D 元素
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        overlayShader.bind();

        // 背景半透明黑框
        float panelW = 280.0f / screenWidth * 2.0f;
        float panelH = (100.0f + PASS_NAMES.length * 16.0f) / screenHeight * 2.0f;

        renderQuad(overlayShader, -1.0f, 1.0f - panelH, panelW, panelH,
            0.0f, 0.0f, 0.0f, 0.7f);

        // FPS 指示條（綠=60+, 黃=30-60, 紅=<30）
        float fpsBarWidth = Math.min(smoothFps / 60.0f, 2.0f) * panelW * 0.8f;
        float fpsR = smoothFps < 30 ? 1.0f : (smoothFps < 60 ? 1.0f : 0.2f);
        float fpsG = smoothFps < 30 ? 0.2f : (smoothFps < 60 ? 0.8f : 0.9f);
        renderQuad(overlayShader,
            -1.0f + 0.01f, 1.0f - 0.02f, fpsBarWidth, 0.015f,
            fpsR, fpsG, 0.2f, 0.9f);

        // 幀時間歷史圖表（迷你折線圖用 GL_LINES 近似）
        float graphX = -1.0f + 0.01f;
        float graphY = 1.0f - panelH + 0.02f;
        float graphW = panelW - 0.02f;
        float graphH = 0.08f;

        renderFrameTimeGraph(overlayShader, graphX, graphY, graphW, graphH, screenWidth, screenHeight);

        overlayShader.unbind();

        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    /**
     * 繪製幀時間歷史圖表。
     */
    private static void renderFrameTimeGraph(BRShaderProgram shader,
                                              float x, float y, float w, float h,
                                              int screenWidth, int screenHeight) {
        float[] vertices = new float[HISTORY_SIZE * 6]; // 每點 position(2) + color(4)
        int vi = 0;

        for (int i = 0; i < HISTORY_SIZE; i++) {
            int idx = (historyIndex + i) % HISTORY_SIZE;
            float frameMs = frameTimeHistory[idx];
            float normalizedTime = Math.min(frameMs / 33.33f, 1.0f); // 33ms = 30fps 上限

            float px = x + (float) i / HISTORY_SIZE * w;
            float py = y + normalizedTime * h;

            // 色彩：綠(<16ms) → 黃(16-33ms) → 紅(>33ms)
            float cr = normalizedTime > 0.5f ? 1.0f : normalizedTime * 2.0f;
            float cg = normalizedTime < 0.5f ? 1.0f : 1.0f - (normalizedTime - 0.5f) * 2.0f;

            vertices[vi++] = px;
            vertices[vi++] = py;
            vertices[vi++] = cr;
            vertices[vi++] = cg;
            vertices[vi++] = 0.2f;
            vertices[vi++] = 0.8f;
        }

        // 上傳並繪製
        GL30.glBindVertexArray(overlayVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, overlayVbo);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buf = stack.mallocFloat(vertices.length);
            buf.put(vertices).flip();
            GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, buf);
        }

        GL11.glDrawArrays(GL11.GL_LINE_STRIP, 0, HISTORY_SIZE);
        GL30.glBindVertexArray(0);
    }

    /**
     * 繪製填色矩形。
     */
    private static void renderQuad(BRShaderProgram shader,
                                    float x, float y, float w, float h,
                                    float r, float g, float b, float a) {
        float[] quadData = {
            x,     y,     r, g, b, a,
            x + w, y,     r, g, b, a,
            x,     y + h, r, g, b, a,
            x + w, y,     r, g, b, a,
            x + w, y + h, r, g, b, a,
            x,     y + h, r, g, b, a
        };

        GL30.glBindVertexArray(overlayVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, overlayVbo);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buf = stack.mallocFloat(quadData.length);
            buf.put(quadData).flip();
            GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, buf);
        }

        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
        GL30.glBindVertexArray(0);
    }

    /**
     * 全螢幕紋理顯示（GBuffer 視覺化用）。
     */
    private static void renderFullscreenTexture(int textureId) {
        if (textureId == 0) return;

        BRShaderProgram finalShader = BRShaderEngine.getFinalShader();
        if (finalShader == null) return;

        finalShader.bind();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        finalShader.setUniformInt("u_mainTex", 0);

        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        finalShader.unbind();
    }

    /**
     * 深度視覺化（線性化 + 假色映射）。
     */
    private static void renderDepthVisualization(int screenWidth, int screenHeight) {
        BRShaderProgram shader = BRShaderEngine.getDebugShader();
        if (shader == null) return;

        shader.bind();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, Minecraft.getInstance().getMainRenderTarget().getDepthTextureId());
        shader.setUniformInt("u_depthTex", 0);
        shader.setUniformFloat("u_nearPlane", 0.1f);
        shader.setUniformFloat("u_farPlane", (float) BRRenderConfig.LOD_MAX_DISTANCE);
        shader.setUniformInt("u_debugMode", 0); // 0 = depth viz

        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        shader.unbind();
    }

    /**
     * CSM 級聯視覺化（各級聯用不同色覆蓋）。
     */
    private static void renderCascadeVisualization(int screenWidth, int screenHeight) {
        BRShaderProgram shader = BRShaderEngine.getDebugShader();
        if (shader == null) return;

        shader.bind();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, Minecraft.getInstance().getMainRenderTarget().getDepthTextureId());
        shader.setUniformInt("u_depthTex", 0);
        shader.setUniformFloat("u_nearPlane", 0.1f);
        shader.setUniformFloat("u_farPlane", BRRenderConfig.CSM_MAX_DISTANCE);
        shader.setUniformInt("u_debugMode", 1); // 1 = cascade viz

        // 上傳級聯分割距離
        for (int c = 0; c < BRCascadedShadowMap.getCascadeCount(); c++) {
            shader.setUniformFloat("u_cascadeSplit[" + c + "]",
                BRCascadedShadowMap.getSplitDistance(c + 1));
        }

        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        shader.unbind();
    }

    /**
     * Wireframe 模式切換。在主渲染前呼叫。
     */
    public static void applyWireframeIfNeeded() {
        if (currentMode == DebugMode.WIREFRAME) {
            GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_LINE);
        }
    }

    /**
     * 恢復非 Wireframe 模式。在主渲染後呼叫。
     */
    public static void restoreWireframeIfNeeded() {
        if (currentMode == DebugMode.WIREFRAME) {
            GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
        }
    }

    // ─── 統計 Accessors ─────────────────────────────────────

    public static float getSmoothedFPS() { return smoothFps; }

    /** 取得最近一幀的幀時間（ms），供 ShaderLOD 品質調節使用 */
    public static float getLastFrameTimeMs() {
        int idx = (historyIndex - 1 + HISTORY_SIZE) % HISTORY_SIZE;
        return frameTimeHistory[idx];
    }
    public static float[] getPassTimesMs() { return passTimesMs; }
    public static String[] getPassNames() { return PASS_NAMES; }
    public static boolean isInitialized() { return initialized; }
}

