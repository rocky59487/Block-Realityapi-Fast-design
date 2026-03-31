package com.blockreality.api.client.render.shader;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * 固化 Shader Program 封裝。
 *
 * 特性：
 *   - Uniform location 快取（避免每幀 glGetUniformLocation）
 *   - Matrix4f 上傳使用 stack allocation（零 heap 分配）
 *   - 編譯錯誤完整日誌
 *   - 不可修改 — 一旦連結即為最終狀態
 *
 * 靈感來源：
 *   - Iris glsl-transformer 的 shader 管理模式
 *   - Radiance 的 Vulkan pipeline 概念（在 GL 層模擬不可變管線）
 */
@OnlyIn(Dist.CLIENT)
public final class BRShaderProgram {

    private final String name;
    private final int programId;
    private final Map<String, Integer> uniformCache = new HashMap<>();
    private boolean bound = false;

    /**
     * 從已編譯的 vertex/fragment shader 建立程式。
     *
     * @param name     shader 名稱（用於日誌）
     * @param vertexSrc   GLSL vertex shader 原始碼
     * @param fragmentSrc GLSL fragment shader 原始碼
     * @throws IllegalStateException 編譯或連結失敗
     */
    public BRShaderProgram(String name, String vertexSrc, String fragmentSrc) {
        this.name = name;

        int vertId = compileShader(GL20.GL_VERTEX_SHADER, vertexSrc, name + ".vert");
        int fragId = compileShader(GL20.GL_FRAGMENT_SHADER, fragmentSrc, name + ".frag");

        programId = GL20.glCreateProgram();
        GL20.glAttachShader(programId, vertId);
        GL20.glAttachShader(programId, fragId);
        GL20.glLinkProgram(programId);

        if (GL20.glGetProgrami(programId, GL20.GL_LINK_STATUS) == 0) {
            String log = GL20.glGetProgramInfoLog(programId, 4096);
            GL20.glDeleteProgram(programId);
            throw new IllegalStateException("[BR Shader] 連結失敗 '" + name + "': " + log);
        }

        // 連結後 shader 可釋放
        GL20.glDetachShader(programId, vertId);
        GL20.glDetachShader(programId, fragId);
        GL20.glDeleteShader(vertId);
        GL20.glDeleteShader(fragId);
    }

    // ─── Bind / Unbind ──────────────────────────────────

    public void bind() {
        GL20.glUseProgram(programId);
        bound = true;
    }

    public void unbind() {
        GL20.glUseProgram(0);
        bound = false;
    }

    public boolean isBound() { return bound; }
    public String getName() { return name; }
    public int getProgramId() { return programId; }

    // ─── Uniform 上傳（全部快取 location）──────────────

    public void setUniformInt(String uniform, int value) {
        GL20.glUniform1i(loc(uniform), value);
    }

    public void setUniformFloat(String uniform, float value) {
        GL20.glUniform1f(loc(uniform), value);
    }

    public void setUniformVec2(String uniform, float x, float y) {
        GL20.glUniform2f(loc(uniform), x, y);
    }

    public void setUniformVec3(String uniform, float x, float y, float z) {
        GL20.glUniform3f(loc(uniform), x, y, z);
    }

    public void setUniformVec4(String uniform, float x, float y, float z, float w) {
        GL20.glUniform4f(loc(uniform), x, y, z, w);
    }

    /**
     * 上傳 Matrix4f — 使用 MemoryStack 避免 heap 分配。
     */
    public void setUniformMatrix4f(String uniform, Matrix4f mat) {
        setUniformMat4(uniform, mat);
    }

    public void setUniformMat4(String uniform, Matrix4f mat) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buf = stack.mallocFloat(16);
            mat.get(buf);
            GL20.glUniformMatrix4fv(loc(uniform), false, buf);
        }
    }

    /**
     * 上傳 float 陣列（用於骨骼矩陣等）。
     */
    public void setUniformMat4Array(String uniform, FloatBuffer matrices, int count) {
        GL20.glUniformMatrix4fv(loc(uniform), false, matrices);
    }

    // ─── Uniform Location 快取 ─────────────────────────

    private int loc(String uniform) {
        return uniformCache.computeIfAbsent(uniform,
            u -> GL20.glGetUniformLocation(programId, u));
    }

    // ─── 編譯工具 ──────────────────────────────────────

    private static int compileShader(int type, String source, String debugName) {
        int id = GL20.glCreateShader(type);
        GL20.glShaderSource(id, source);
        GL20.glCompileShader(id);

        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == 0) {
            String log = GL20.glGetShaderInfoLog(id, 4096);
            GL20.glDeleteShader(id);
            throw new IllegalStateException("[BR Shader] 編譯失敗 '" + debugName + "': " + log);
        }
        return id;
    }

    // ─── 清除 ───────────────────────────────────────────

    public void delete() {
        if (programId != 0) {
            GL20.glDeleteProgram(programId);
        }
        uniformCache.clear();
    }
}
