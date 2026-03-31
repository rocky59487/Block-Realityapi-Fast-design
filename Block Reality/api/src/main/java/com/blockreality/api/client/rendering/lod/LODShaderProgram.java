package com.blockreality.api.client.rendering.lod;

import com.blockreality.api.config.BRConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.GL_FALSE;
import static org.lwjgl.opengl.GL20.*;

/**
 * LOD 地形 OpenGL Shader 程式（Phase 1-C）。
 *
 * 封裝 lod.vert + lod.frag 的編譯、連結、uniform 設定。
 * 與 {@link LODTerrainBuffer} 配合：
 *   1. {@link #compile()} — 載入 & 編譯（init 階段呼叫）
 *   2. {@link #use()} + {@link #setUniforms(Camera, float)} + LODTerrainBuffer.render()
 *   3. {@link #unuse()} — 渲染結束後恢復 GL 狀態
 *
 * 頂點屬性綁定（與 LODTerrainBuffer VAO 對應）：
 *   location=0 → a_Position (xyz)
 *   location=1 → a_Normal   (xyz)
 *   location=2 → a_Color    (rgba)
 *
 * Shader 路徑：assets/blockreality/shaders/lod/lod.vert / lod.frag
 *
 * @see LODTerrainBuffer
 * @see LODRenderDispatcher
 */
@OnlyIn(Dist.CLIENT)
public final class LODShaderProgram {

    private static final Logger LOG = LoggerFactory.getLogger("BR-LODShader");

    private static final String VERT_PATH = "/assets/blockreality/shaders/lod/lod.vert";
    private static final String FRAG_PATH = "/assets/blockreality/shaders/lod/lod.frag";

    // ─── GL 物件 ───
    private int programId = 0;
    private boolean linked = false;

    // ─── Uniform locations（-1 = 不存在/未查詢） ───
    private int uModelViewProj     = -1;
    private int uModelView         = -1;
    private int uFogStart          = -1;
    private int uFogEnd            = -1;
    private int uFogColor          = -1;
    private int uLodFadeDistance   = -1;
    private int uCameraPos         = -1;
    private int uSunDirection      = -1;
    private int uSunColor          = -1;
    private int uSunIntensity      = -1;
    private int uAmbientColor      = -1;
    private int uDebugLodLevel     = -1;

    public LODShaderProgram() {}

    // ═══ 初始化 ═══

    /**
     * 從 classpath 載入、編譯並連結 LOD shader。
     *
     * 在 {@link LODTerrainBuffer#init()} 之後、首次 render 之前呼叫。
     *
     * @return true 若成功
     */
    public boolean compile() {
        if (linked) {
            LOG.warn("LODShaderProgram already compiled");
            return true;
        }

        int vert = compileShader(GL_VERTEX_SHADER,   VERT_PATH, "lod.vert");
        int frag = compileShader(GL_FRAGMENT_SHADER, FRAG_PATH, "lod.frag");

        if (vert == 0 || frag == 0) {
            if (vert != 0) glDeleteShader(vert);
            if (frag != 0) glDeleteShader(frag);
            return false;
        }

        programId = glCreateProgram();
        glAttachShader(programId, vert);
        glAttachShader(programId, frag);

        // 在連結前綁定屬性位置（lod.vert 使用 'in' 而非 layout(location=...)）
        glBindAttribLocation(programId, 0, "a_Position");
        glBindAttribLocation(programId, 1, "a_Normal");
        glBindAttribLocation(programId, 2, "a_Color");

        glLinkProgram(programId);

        // Shader modules 連結後即可釋放
        glDeleteShader(vert);
        glDeleteShader(frag);

        if (glGetProgrami(programId, GL_LINK_STATUS) == GL_FALSE) {
            LOG.error("LOD shader link error:\n{}", glGetProgramInfoLog(programId));
            glDeleteProgram(programId);
            programId = 0;
            return false;
        }

        // 查詢 uniform locations
        uModelViewProj   = glGetUniformLocation(programId, "u_ModelViewProj");
        uModelView       = glGetUniformLocation(programId, "u_ModelView");
        uFogStart        = glGetUniformLocation(programId, "u_FogStart");
        uFogEnd          = glGetUniformLocation(programId, "u_FogEnd");
        uFogColor        = glGetUniformLocation(programId, "u_FogColor");
        uLodFadeDistance = glGetUniformLocation(programId, "u_LodFadeDistance");
        uCameraPos       = glGetUniformLocation(programId, "u_CameraPos");
        uSunDirection    = glGetUniformLocation(programId, "u_SunDirection");
        uSunColor        = glGetUniformLocation(programId, "u_SunColor");
        uSunIntensity    = glGetUniformLocation(programId, "u_SunIntensity");
        uAmbientColor    = glGetUniformLocation(programId, "u_AmbientColor");
        uDebugLodLevel   = glGetUniformLocation(programId, "u_DebugLodLevel");

        linked = true;
        LOG.info("LODShaderProgram compiled (id={}, MVP={}, MV={}, sun={}/{})",
            programId, uModelViewProj, uModelView, uSunDirection, uSunIntensity);
        return true;
    }

    // ═══ 渲染 ═══

    /** 綁定 LOD shader program */
    public void use() {
        if (linked) glUseProgram(programId);
    }

    /** 解除綁定（還原 GL state） */
    public void unuse() {
        glUseProgram(0);
    }

    /**
     * 設定所有 uniforms（在 {@link #use()} 之後呼叫）。
     *
     * 矩陣計算：
     *   - view = lookAt(camPos, camPos + look, up)（JOML 處理 OpenGL 慣例）
     *   - MVP  = proj × view（model = identity，頂點已在世界空間）
     *
     * @param cam         Minecraft 主相機
     * @param partialTick 插值係數（0.0–1.0）
     */
    public void setUniforms(Camera cam, float partialTick) {
        if (!linked) return;

        Minecraft mc = Minecraft.getInstance();

        // ─── 相機位置 ───
        float px = (float) cam.getPosition().x;
        float py = (float) cam.getPosition().y;
        float pz = (float) cam.getPosition().z;

        // ─── 視圖矩陣（JOML lookAt，OpenGL 慣例：相機看向 -Z） ───
        Vector3f look = cam.getLookVector();
        Vector3f up   = cam.getUpVector();

        Matrix4f viewMatrix = new Matrix4f().lookAt(
            px, py, pz,
            px + look.x, py + look.y, pz + look.z,
            up.x, up.y, up.z);

        // ─── 投影矩陣 ───
        Matrix4f projMatrix = new Matrix4f(RenderSystem.getProjectionMatrix());

        // ─── MVP = proj × view ───
        Matrix4f mvp = projMatrix.mul(viewMatrix, new Matrix4f());

        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buf = stack.mallocFloat(16);

            if (uModelViewProj >= 0) {
                buf.clear();
                mvp.get(buf);
                glUniformMatrix4fv(uModelViewProj, false, buf);
            }

            if (uModelView >= 0) {
                buf.clear();
                viewMatrix.get(buf);
                glUniformMatrix4fv(uModelView, false, buf);
            }
        }

        // ─── 相機位置 ───
        if (uCameraPos >= 0) glUniform3f(uCameraPos, px, py, pz);

        // ─── 太陽方向 & 強度（從 Minecraft 時間計算） ───
        float sunAngle = (mc.level != null)
            ? mc.level.getSunAngle(partialTick)
            : 0.0f;

        // 太陽繞 X 軸旋轉：角度 0 = 正午（最高點）、π = 午夜
        float sunDirY = (float) Math.cos(sunAngle);   // 高度
        float sunDirZ = -(float) Math.sin(sunAngle);  // 深度分量
        float sunDirX = 0.15f;                         // 輕微側移（避免正頂入射）
        if (uSunDirection >= 0) glUniform3f(uSunDirection, sunDirX, sunDirY, sunDirZ);

        // 日照強度（夜晚歸零）
        float sunIntensity = Math.max(0.0f, sunDirY);
        if (uSunIntensity >= 0) glUniform1f(uSunIntensity, sunIntensity);

        // 太陽色（白天暖白，日落偏橙）
        float sunR = 1.0f;
        float sunG = 0.95f - 0.25f * (1.0f - sunIntensity); // 低時偏橙
        float sunB = 0.80f - 0.40f * (1.0f - sunIntensity);
        if (uSunColor >= 0) glUniform3f(uSunColor, sunR, sunG, sunB);

        // ─── 環境光（夜晚月光補充） ───
        float ambient = 0.25f + 0.25f * sunIntensity; // 0.25（夜）~ 0.50（正午）
        if (uAmbientColor >= 0) glUniform3f(uAmbientColor, ambient, ambient, ambient + 0.04f);

        // ─── 霧效（對齊 Minecraft 渲染距離） ───
        int renderDistChunks = (mc.options != null)
            ? mc.options.getEffectiveRenderDistance()
            : 12;
        float renderDistBlocks = renderDistChunks * 16.0f;
        if (uFogStart >= 0) glUniform1f(uFogStart, renderDistBlocks * 0.65f);
        if (uFogEnd   >= 0) glUniform1f(uFogEnd,   renderDistBlocks * 0.90f);

        // 霧色：晴天天空藍（Phase 4 升級：讀取 Minecraft 實際天空色）
        float fogR = 0.55f + 0.10f * sunIntensity;
        float fogG = 0.72f + 0.05f * sunIntensity;
        float fogB = 1.00f;
        if (uFogColor >= 0) glUniform4f(uFogColor, fogR, fogG, fogB, 1.0f);

        // ─── LOD 淡入距離 ───
        float fadeDist = BRConfig.INSTANCE.lodLevel1Threshold.get() * 16.0f * 0.8f;
        if (uLodFadeDistance >= 0) glUniform1f(uLodFadeDistance, fadeDist);

        // ─── LOD 偵錯模式（從 BRConfig，預設關閉） ───
        if (uDebugLodLevel >= 0) glUniform1i(uDebugLodLevel, 0);
    }

    // ═══ 清理 ═══

    /**
     * 刪除 GL program（模組卸載時呼叫）。
     */
    public void cleanup() {
        if (programId != 0) {
            glDeleteProgram(programId);
            programId = 0;
        }
        linked = false;
        LOG.info("LODShaderProgram cleanup complete");
    }

    // ═══ 工具 ═══

    /** 從 classpath 資源編譯 GLSL shader */
    private static int compileShader(int type, String resourcePath, String name) {
        String src = readResource(resourcePath);
        if (src == null) {
            LOG.error("Could not load shader resource: {}", resourcePath);
            return 0;
        }

        int shader = glCreateShader(type);
        glShaderSource(shader, src);
        glCompileShader(shader);

        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            LOG.error("Shader compile error [{}]:\n{}", name, glGetShaderInfoLog(shader));
            glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    /** 從 classpath 讀取文字資源 */
    private static String readResource(String path) {
        try (InputStream is = LODShaderProgram.class.getResourceAsStream(path)) {
            if (is == null) return null;
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.error("Error reading shader resource {}: {}", path, e.getMessage());
            return null;
        }
    }

    // ═══ Accessors ═══

    public boolean isLinked()  { return linked; }
    public int     getProgramId() { return programId; }
}
