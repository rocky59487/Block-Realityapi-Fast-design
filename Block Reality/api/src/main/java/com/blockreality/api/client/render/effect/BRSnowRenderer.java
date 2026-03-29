package com.blockreality.api.client.render.effect;

import com.blockreality.api.client.render.BRRenderConfig;
import com.blockreality.api.client.render.shader.BRShaderEngine;
import com.blockreality.api.client.render.shader.BRShaderProgram;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;

/**
 * GPU Instanced 雪花渲染器。
 *
 * 技術架構：
 *   - 雪花為 billboard point sprite（圓形 + 6 分支結晶紋理程序化生成）
 *   - 飄落路徑：正弦擺動（x,z 方向）+ 重力下落（y）
 *   - 積雪效果：snowCoverage 係數傳入 GBuffer shader 修改法線 + albedo
 *   - 近距離 bokeh 失焦效果（距相機近的雪花放大 + 模糊）
 *
 * @author Block Reality Team
 * @version 1.0
 */
@OnlyIn(Dist.CLIENT)
public class BRSnowRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger(BRSnowRenderer.class);

    // 每粒子: posX, posY, posZ, velY, life, alpha, size, wobblePhase (8 floats)
    private static final int FLOATS_PER_FLAKE = 8;
    private static final int MAX_FLAKES = 4096;

    private static float[] flakeData;
    private static int aliveCount = 0;

    private static int vao;
    private static int quadVbo;
    private static int instanceVbo;

    private static volatile boolean initialized = false;

    // ========================= 初始化 =========================

    public static void init() {
        if (initialized) return;

        flakeData = new float[MAX_FLAKES * FLOATS_PER_FLAKE];
        aliveCount = 0;

        vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vao);

        // Point sprite quad
        float[] quadVerts = {
            -0.5f, -0.5f, 0.0f, 0.0f,
             0.5f, -0.5f, 1.0f, 0.0f,
             0.5f,  0.5f, 1.0f, 1.0f,
            -0.5f, -0.5f, 0.0f, 0.0f,
             0.5f,  0.5f, 1.0f, 1.0f,
            -0.5f,  0.5f, 0.0f, 1.0f
        };
        quadVbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadVbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, quadVerts, GL15.GL_STATIC_DRAW);
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 4, GL11.GL_FLOAT, false, 16, 0);

        // Instance VBO
        instanceVbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, instanceVbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) MAX_FLAKES * FLOATS_PER_FLAKE * 4, GL15.GL_STREAM_DRAW);

        // attrib 1: posXYZ (3 float)
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, FLOATS_PER_FLAKE * 4, 0);
        GL33.glVertexAttribDivisor(1, 1);

        // attrib 2: velY, life, alpha, size (4 float)
        GL20.glEnableVertexAttribArray(2);
        GL20.glVertexAttribPointer(2, 4, GL11.GL_FLOAT, false, FLOATS_PER_FLAKE * 4, 12);
        GL33.glVertexAttribDivisor(2, 1);

        // attrib 3: wobblePhase (1 float — padded to vec1)
        GL20.glEnableVertexAttribArray(3);
        GL20.glVertexAttribPointer(3, 1, GL11.GL_FLOAT, false, FLOATS_PER_FLAKE * 4, 28);
        GL33.glVertexAttribDivisor(3, 1);

        GL30.glBindVertexArray(0);

        initialized = true;
        LOGGER.info("[BRSnowRenderer] 雪花渲染器初始化完成 — 最大 {} 片", MAX_FLAKES);
    }

    public static void cleanup() {
        if (!initialized) return;
        if (vao != 0) { GL30.glDeleteVertexArrays(vao); vao = 0; }
        if (quadVbo != 0) { GL15.glDeleteBuffers(quadVbo); quadVbo = 0; }
        if (instanceVbo != 0) { GL15.glDeleteBuffers(instanceVbo); instanceVbo = 0; }
        flakeData = null;
        aliveCount = 0;
        initialized = false;
    }

    // ========================= 每幀更新 =========================

    public static void tick(float deltaTime, float intensity, float playerY) {
        if (!initialized) return;

        // ── 生成新雪花 ──
        int spawnCount = (int)(intensity * BRRenderConfig.SNOW_FLAKES_PER_TICK);
        for (int i = 0; i < spawnCount && aliveCount < MAX_FLAKES; i++) {
            int idx = aliveCount * FLOATS_PER_FLAKE;
            flakeData[idx]     = (float)(Math.random() * 64 - 32); // posX
            flakeData[idx + 1] = playerY + 15.0f + (float)(Math.random() * 15); // posY
            flakeData[idx + 2] = (float)(Math.random() * 64 - 32); // posZ
            flakeData[idx + 3] = -1.0f - (float)(Math.random() * 1.5f); // velY（慢速飄落）
            flakeData[idx + 4] = 2.0f + (float)(Math.random() * 3.0f); // life（秒）
            flakeData[idx + 5] = 0.5f + (float)(Math.random() * 0.3f) * intensity; // alpha
            flakeData[idx + 6] = 0.03f + (float)(Math.random() * 0.05f); // size
            flakeData[idx + 7] = (float)(Math.random() * Math.PI * 2); // wobblePhase
            aliveCount++;
        }

        // ── 更新 ──
        int write = 0;
        for (int i = 0; i < aliveCount; i++) {
            int idx = i * FLOATS_PER_FLAKE;
            float phase = flakeData[idx + 7];

            // 正弦擺動
            flakeData[idx]     += (float) Math.sin(phase + flakeData[idx + 4] * 2.0) * 0.3f * deltaTime;
            flakeData[idx + 1] += flakeData[idx + 3] * deltaTime;
            flakeData[idx + 2] += (float) Math.cos(phase + flakeData[idx + 4] * 1.5) * 0.2f * deltaTime;
            flakeData[idx + 4] -= deltaTime;

            if (flakeData[idx + 4] > 0.0f && flakeData[idx + 1] > 0.0f) {
                if (write != i) {
                    System.arraycopy(flakeData, idx, flakeData, write * FLOATS_PER_FLAKE, FLOATS_PER_FLAKE);
                }
                write++;
            }
        }
        aliveCount = write;
    }

    // ========================= 渲染 =========================

    public static void render(float intensity, float gameTime) {
        if (!initialized || aliveCount == 0) return;

        BRShaderProgram shader = BRShaderEngine.getSnowShader();
        if (shader == null) return;

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, instanceVbo);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buf = stack.mallocFloat(aliveCount * FLOATS_PER_FLAKE);
            buf.put(flakeData, 0, aliveCount * FLOATS_PER_FLAKE);
            buf.flip();
            GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, buf);
        }

        shader.bind();

        // viewProj + cameraPos（camera 在載入/切換世界時可能為 null）
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        if (camera == null) { shader.unbind(); return; }
        Vec3 camPos = camera.getPosition();
        org.joml.Matrix4f projMatrix = new org.joml.Matrix4f(RenderSystem.getProjectionMatrix());
        org.joml.Matrix4f viewMatrix = new org.joml.Matrix4f().rotation(camera.rotation());
        org.joml.Matrix4f viewProj = new org.joml.Matrix4f();
        projMatrix.mul(viewMatrix, viewProj);
        shader.setUniformMat4("u_viewProj", viewProj);
        shader.setUniformVec3("u_cameraPos", (float) camPos.x, (float) camPos.y, (float) camPos.z);

        shader.setUniformFloat("u_intensity", intensity);
        shader.setUniformFloat("u_gameTime", gameTime);
        shader.setUniformFloat("u_snowCoverage", BRWeatherEngine.getSnowCoverage());

        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDepthMask(false);

        GL30.glBindVertexArray(vao);
        GL31.glDrawArraysInstanced(GL11.GL_TRIANGLES, 0, 6, aliveCount);
        GL30.glBindVertexArray(0);

        GL11.glDepthMask(true);
        GL11.glDisable(GL11.GL_BLEND);
        shader.unbind();
    }
}
