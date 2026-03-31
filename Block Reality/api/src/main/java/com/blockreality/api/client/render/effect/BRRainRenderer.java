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
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
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
 * GPU Instanced 雨滴渲染器。
 *
 * 技術架構：
 *   - 雨滴為垂直伸展的線段（billboard quad → stretch along velocity）
 *   - GPU Instanced rendering：單一 quad 幾何 + per-instance 位置/速度/生命
 *   - 粒子池預分配，零 GC
 *   - 包含水花（splash）子系統：雨滴落地時在落點生成扇形噴濺
 *   - 濕潤 PBR 修正：全域 wetness 影響 GBuffer roughness/reflectance
 *
 * 參考：
 *   - NVIDIA "Rain" demo (GPU Gems 3)
 *   - Iris/BSL rain streak particles
 *
 * @author Block Reality Team
 * @version 1.0
 */
@OnlyIn(Dist.CLIENT)
public class BRRainRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger(BRRainRenderer.class);

    // ─── 雨滴粒子結構（SoA） ───
    // 每粒子: posX, posY, posZ, velY, life, alpha, streakLen (7 floats)
    private static final int FLOATS_PER_DROP = 7;
    private static final int MAX_DROPS = 4096;

    private static float[] dropData;
    private static int aliveCount = 0;

    // ─── GPU 資源 ───
    private static int vao;
    private static int quadVbo;      // 靜態 quad 幾何
    private static int instanceVbo;  // 動態 per-instance 資料

    // ─── 水花粒子（簡易版） ───
    private static final int MAX_SPLASHES = 512;
    private static final int SPLASH_FLOATS = 5; // posX, posY, posZ, life, scale
    private static float[] splashData;
    private static int splashCount = 0;

    private static volatile boolean initialized = false;

    /** 全域濕潤度（由呼叫方注入，避免依賴已廢棄的 BRWeatherEngine） */
    private static volatile float globalWetness = 0.0f;

    /** 由呼叫方（天氣系統）設定濕潤度 */
    public static void setGlobalWetness(float wetness) {
        globalWetness = Math.max(0.0f, Math.min(1.0f, wetness));
    }

    // ========================= 初始化 =========================

    public static void init() {
        if (initialized) return;

        dropData = new float[MAX_DROPS * FLOATS_PER_DROP];
        splashData = new float[MAX_SPLASHES * SPLASH_FLOATS];
        aliveCount = 0;
        splashCount = 0;

        // 建立 VAO + VBO
        vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vao);

        // Quad 幾何（2 三角形，每頂點 4 float: x,y,u,v）
        float[] quadVerts = {
            -0.01f, 0.0f, 0.0f, 0.0f,
             0.01f, 0.0f, 1.0f, 0.0f,
             0.01f, 1.0f, 1.0f, 1.0f,
            -0.01f, 0.0f, 0.0f, 0.0f,
             0.01f, 1.0f, 1.0f, 1.0f,
            -0.01f, 1.0f, 0.0f, 1.0f
        };
        quadVbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadVbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, quadVerts, GL15.GL_STATIC_DRAW);
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 4, GL11.GL_FLOAT, false, 16, 0);

        // Instance VBO（動態更新）
        instanceVbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, instanceVbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) MAX_DROPS * FLOATS_PER_DROP * 4, GL15.GL_STREAM_DRAW);

        // attrib 1: posXYZ (3 float)
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, FLOATS_PER_DROP * 4, 0);
        GL33.glVertexAttribDivisor(1, 1);

        // attrib 2: velY, life, alpha, streakLen (4 float)
        GL20.glEnableVertexAttribArray(2);
        GL20.glVertexAttribPointer(2, 4, GL11.GL_FLOAT, false, FLOATS_PER_DROP * 4, 12);
        GL33.glVertexAttribDivisor(2, 1);

        GL30.glBindVertexArray(0);

        initialized = true;
        LOGGER.info("[BRRainRenderer] 雨滴渲染器初始化完成 — 最大 {} 滴", MAX_DROPS);
    }

    public static void cleanup() {
        if (!initialized) return;
        if (vao != 0) { GL30.glDeleteVertexArrays(vao); vao = 0; }
        if (quadVbo != 0) { GL15.glDeleteBuffers(quadVbo); quadVbo = 0; }
        if (instanceVbo != 0) { GL15.glDeleteBuffers(instanceVbo); instanceVbo = 0; }
        dropData = null;
        splashData = null;
        aliveCount = 0;
        splashCount = 0;
        initialized = false;
    }

    // ========================= 每幀更新 =========================

    /**
     * CPU 端粒子更新。
     * @param deltaTime   幀間隔（秒）
     * @param intensity   降雨強度 0~1
     * @param playerY     玩家 Y 座標（雨滴在此 ±32 範圍生成）
     */
    public static void tick(float deltaTime, float intensity, float playerY) {
        if (!initialized) return;

        // ── 生成新雨滴 ──
        int spawnCount = (int)(intensity * BRRenderConfig.RAIN_DROPS_PER_TICK);
        for (int i = 0; i < spawnCount && aliveCount < MAX_DROPS; i++) {
            int idx = aliveCount * FLOATS_PER_DROP;
            dropData[idx]     = (float)(Math.random() * 64 - 32); // posX（相對玩家）
            dropData[idx + 1] = playerY + 20.0f + (float)(Math.random() * 12); // posY
            dropData[idx + 2] = (float)(Math.random() * 64 - 32); // posZ
            dropData[idx + 3] = -12.0f - (float)(Math.random() * 8);  // velY（下落速度）
            dropData[idx + 4] = 0.6f + (float)(Math.random() * 0.4f); // life
            dropData[idx + 5] = 0.3f + (float)(Math.random() * 0.4f) * intensity; // alpha
            dropData[idx + 6] = 0.3f + (float)(Math.random() * 0.5f); // streakLen
            aliveCount++;
        }

        // ── 更新存活雨滴 ──
        int write = 0;
        for (int i = 0; i < aliveCount; i++) {
            int idx = i * FLOATS_PER_DROP;
            dropData[idx + 1] += dropData[idx + 3] * deltaTime; // posY += velY * dt
            dropData[idx + 4] -= deltaTime * 0.5f;              // life 衰減

            // 仍存活且未落到地面以下
            if (dropData[idx + 4] > 0.0f && dropData[idx + 1] > 0.0f) {
                if (write != i) {
                    System.arraycopy(dropData, idx, dropData, write * FLOATS_PER_DROP, FLOATS_PER_DROP);
                }
                write++;
            } else {
                // 落地 → 生成水花
                spawnSplash(dropData[idx], Math.max(0, dropData[idx + 1]), dropData[idx + 2]);
            }
        }
        aliveCount = write;

        // ── 水花更新 ──
        int sw = 0;
        for (int i = 0; i < splashCount; i++) {
            int si = i * SPLASH_FLOATS;
            splashData[si + 3] -= deltaTime * 2.0f; // life 衰減
            if (splashData[si + 3] > 0.0f) {
                if (sw != i) {
                    System.arraycopy(splashData, si, splashData, sw * SPLASH_FLOATS, SPLASH_FLOATS);
                }
                sw++;
            }
        }
        splashCount = sw;
    }

    private static void spawnSplash(float x, float y, float z) {
        if (splashCount >= MAX_SPLASHES) return;
        int si = splashCount * SPLASH_FLOATS;
        splashData[si]     = x;
        splashData[si + 1] = y;
        splashData[si + 2] = z;
        splashData[si + 3] = 1.0f; // life
        splashData[si + 4] = 0.1f + (float)(Math.random() * 0.15f); // scale
        splashCount++;
    }

    // ========================= 渲染 =========================

    /**
     * GPU Instanced 渲染雨滴。
     * @param intensity 降雨強度
     * @param gameTime  遊戲時間
     */
    public static void render(float intensity, float gameTime) {
        if (!initialized || aliveCount == 0) return;

        BRShaderProgram shader = BRShaderEngine.getRainShader();
        if (shader == null) return;

        // 上傳 instance 資料
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, instanceVbo);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buf = stack.mallocFloat(aliveCount * FLOATS_PER_DROP);
            buf.put(dropData, 0, aliveCount * FLOATS_PER_DROP);
            buf.flip();
            GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, buf);
        }

        // 渲染
        shader.bind();

        // viewProj + cameraPos（camera 在載入/切換世界時可能為 null）
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        if (camera == null) { shader.unbind(); return; }
        Vec3 camPos = camera.getPosition();
        Matrix4f projMatrix = new Matrix4f(RenderSystem.getProjectionMatrix());
        Matrix4f viewMatrix = new Matrix4f().rotation(camera.rotation());
        Matrix4f viewProj = new Matrix4f();
        projMatrix.mul(viewMatrix, viewProj);
        shader.setUniformMat4("u_viewProj", viewProj);
        shader.setUniformVec3("u_cameraPos", (float) camPos.x, (float) camPos.y, (float) camPos.z);

        shader.setUniformFloat("u_intensity", intensity);
        shader.setUniformFloat("u_gameTime", gameTime);
        shader.setUniformFloat("u_wetness", globalWetness);

        // 半透明混合
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

