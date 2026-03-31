package com.blockreality.api.client.rendering.vulkan;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL14.GL_FUNC_ADD;
import static org.lwjgl.opengl.GL14.glBlendEquation;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * GL composite pass：將 Vulkan RT 輸出貼圖以 50% 透明度疊加到 Minecraft 畫面（Phase 3）。
 *
 * 渲染策略：
 *   - Full-screen quad（GL_TRIANGLE_STRIP, 4 vertices, no VBO — 使用 gl_VertexID trick）
 *   - 空 VAO（glDrawArrays 不需要 VBO；GL3.3+ core profile 需要 VAO 綁定）
 *   - SRC_ALPHA / ONE_MINUS_SRC_ALPHA 混合，alpha = 0.5 → 50% 覆蓋
 *   - 深度測試關閉（composite 層不應影響深度緩衝）
 *
 * 使用方式：
 * <pre>
 *   // 模組初始化時：
 *   RTCompositePass.init();
 *
 *   // 每幀（AFTER_TRANSLUCENT_BLOCKS）：
 *   RTCompositePass.render(vkSwapchain.getGLTextureId());
 *
 *   // 模組卸載時：
 *   RTCompositePass.cleanup();
 * </pre>
 *
 * @see VkSwapchain
 * @see com.blockreality.api.client.rendering.bridge.ForgeRenderEventBridge
 */
@OnlyIn(Dist.CLIENT)
public final class RTCompositePass {

    private static final Logger LOG = LoggerFactory.getLogger("BR-RTComposite");

    // ─── GL 資源 ───
    private static int shaderProgram = 0;
    private static int emptyVAO      = 0;
    private static int uTextureLocation = -1;

    private RTCompositePass() {}

    // ─── 嵌入式 GLSL（Phase 3 測試用，不需要獨立 shader 檔案） ───

    private static final String VERT_SRC =
        "#version 330 core\n" +
        "out vec2 uv;\n" +
        "void main() {\n" +
        "    const vec2 pos[4] = vec2[](\n" +
        "        vec2(-1.0, -1.0), vec2(1.0, -1.0),\n" +
        "        vec2(-1.0,  1.0), vec2(1.0,  1.0)\n" +
        "    );\n" +
        "    const vec2 tex[4] = vec2[](\n" +
        "        vec2(0.0, 0.0), vec2(1.0, 0.0),\n" +
        "        vec2(0.0, 1.0), vec2(1.0, 1.0)\n" +
        "    );\n" +
        "    gl_Position = vec4(pos[gl_VertexID], 0.0, 1.0);\n" +
        "    uv = tex[gl_VertexID];\n" +
        "}\n";

    /**
     * Fragment shader：採樣 RT RGBA16F texture，輸出 rgb + alpha=0.5。
     * 在 Minecraft 的 GL blend state 下會以 50% 透明度疊加到畫面。
     */
    private static final String FRAG_SRC =
        "#version 330 core\n" +
        "uniform sampler2D uRTTexture;\n" +
        "in vec2 uv;\n" +
        "out vec4 fragColor;\n" +
        "void main() {\n" +
        "    vec4 rt = texture(uRTTexture, uv);\n" +
        "    // tone-map: RT pipeline 輸出線性 HDR；簡單 clamp 到 SDR（Phase 4 升級為 ACES tonemapping）\n" +
        "    vec3 sdr = clamp(rt.rgb, 0.0, 1.0);\n" +
        "    fragColor = vec4(sdr, 0.5);\n" +
        "}\n";

    // ═══ 初始化 ═══

    /**
     * 建立 GL shader program 和空 VAO。
     *
     * 必須在 GL context 建立後呼叫（模組 ClientSetup 階段）。
     *
     * @return true 若成功
     */
    public static boolean init() {
        if (shaderProgram != 0) {
            LOG.warn("RTCompositePass already initialized");
            return true;
        }

        try {
            int vert = compileShader(GL_VERTEX_SHADER,   VERT_SRC, "rt_composite.vert");
            int frag = compileShader(GL_FRAGMENT_SHADER, FRAG_SRC, "rt_composite.frag");
            if (vert == 0 || frag == 0) {
                glDeleteShader(vert);
                glDeleteShader(frag);
                return false;
            }

            shaderProgram = glCreateProgram();
            glAttachShader(shaderProgram, vert);
            glAttachShader(shaderProgram, frag);
            glLinkProgram(shaderProgram);

            // 刪除 shader（已 linked，不再需要）
            glDeleteShader(vert);
            glDeleteShader(frag);

            if (glGetProgrami(shaderProgram, GL_LINK_STATUS) == GL_FALSE) {
                LOG.error("RTCompositePass link error: {}", glGetProgramInfoLog(shaderProgram));
                glDeleteProgram(shaderProgram);
                shaderProgram = 0;
                return false;
            }

            uTextureLocation = glGetUniformLocation(shaderProgram, "uRTTexture");

            // 空 VAO（GL3.3 core profile 要求有 VAO 綁定才能 glDrawArrays）
            emptyVAO = glGenVertexArrays();

            LOG.info("RTCompositePass initialized (program={}, VAO={})", shaderProgram, emptyVAO);
            return true;

        } catch (Exception e) {
            LOG.error("RTCompositePass.init() failed: {}", e.getMessage(), e);
            return false;
        }
    }

    // ═══ 渲染 ═══

    /**
     * 執行 RT composite pass（在 AFTER_TRANSLUCENT_BLOCKS 中呼叫）。
     *
     * GL state 管理：
     *   1. 儲存 depth test / blend / program 狀態
     *   2. 執行全螢幕 quad draw
     *   3. 還原 GL 狀態
     *
     * @param glTextureId  VkSwapchain.getGLTextureId() 返回的 GL texture ID
     */
    public static void render(int glTextureId) {
        if (shaderProgram == 0 || glTextureId == 0) return;

        // ─── 儲存 GL 狀態 ───
        boolean wasDepthTest    = glIsEnabled(GL_DEPTH_TEST);
        boolean wasBlend        = glIsEnabled(GL_BLEND);
        int[] prevBlendSrc      = new int[1];
        int[] prevBlendDst      = new int[1];
        int[] prevProgram       = new int[1];
        int[] prevVAO           = new int[1];
        int[] prevActiveTex     = new int[1];
        int[] prevTex2D         = new int[1];

        glGetIntegerv(GL_BLEND_SRC_ALPHA, prevBlendSrc);
        glGetIntegerv(GL_BLEND_DST_ALPHA, prevBlendDst);
        glGetIntegerv(GL_CURRENT_PROGRAM,  prevProgram);
        glGetIntegerv(GL_VERTEX_ARRAY_BINDING, prevVAO);
        glGetIntegerv(GL_ACTIVE_TEXTURE, prevActiveTex);
        glGetIntegerv(GL_TEXTURE_BINDING_2D, prevTex2D);

        try {
            // ─── 設定 composite 狀態 ───
            glDisable(GL_DEPTH_TEST);
            glDepthMask(false);
            glEnable(GL_BLEND);
            glBlendEquation(GL_FUNC_ADD);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

            glUseProgram(shaderProgram);

            // 綁定 RT texture 到 unit 0
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, glTextureId);
            if (uTextureLocation >= 0) {
                glUniform1i(uTextureLocation, 0);
            }

            // 全螢幕 quad（4 vertices, TRIANGLE_STRIP；頂點位置來自 gl_VertexID 常量陣列）
            glBindVertexArray(emptyVAO);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

        } finally {
            // ─── 還原 GL 狀態 ───
            glBindVertexArray(prevVAO[0]);
            glActiveTexture(prevActiveTex[0]);
            glBindTexture(GL_TEXTURE_2D, prevTex2D[0]);
            glUseProgram(prevProgram[0]);

            glBlendFunc(prevBlendSrc[0], prevBlendDst[0]);
            if (!wasBlend)    glDisable(GL_BLEND);
            if (wasDepthTest) glEnable(GL_DEPTH_TEST);
            glDepthMask(true);
        }
    }

    // ═══ 清理 ═══

    /**
     * 釋放 GL 資源（模組卸載時呼叫）。
     */
    public static void cleanup() {
        if (emptyVAO != 0) {
            glDeleteVertexArrays(emptyVAO);
            emptyVAO = 0;
        }
        if (shaderProgram != 0) {
            glDeleteProgram(shaderProgram);
            shaderProgram = 0;
        }
        uTextureLocation = -1;
        LOG.info("RTCompositePass cleanup complete");
    }

    // ═══ 工具 ═══

    private static int compileShader(int type, String src, String name) {
        int shader = glCreateShader(type);
        glShaderSource(shader, src);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            LOG.error("Shader compile error [{}]: {}", name, glGetShaderInfoLog(shader));
            glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    public static boolean isInitialized() { return shaderProgram != 0; }
}
