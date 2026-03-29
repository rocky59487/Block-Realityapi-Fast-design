package com.blockreality.api.client.render.effect;

import com.blockreality.api.client.render.BRRenderConfig;
import com.blockreality.api.client.render.shader.BRShaderEngine;
import com.blockreality.api.client.render.shader.BRShaderProgram;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import org.lwjgl.system.MemoryStack;

/**
 * 高效能粒子系統 — 支援建築特效、環境粒子、UI 回饋。
 *
 * 設計原則：
 * - 預分配粒子池（零 GC）
 * - 結構化粒子陣列（SoA 風格，CPU 更新）
 * - 單次 VBO 上傳 + instanced draw
 * - 粒子排序可選（半透明需要，不透明不需要）
 * - 多發射器支援（每個發射器獨立參數）
 *
 * 粒子類型：
 * - 建築放置火花
 * - 選取框高光粒子
 * - 方塊破壞碎片
 * - 環境灰塵 / 雪花
 * - UI 確認特效
 *
 * @author Block Reality Team
 * @version 1.0
 */
@OnlyIn(Dist.CLIENT)
public class BRParticleSystem {

    private static final Logger LOGGER = LoggerFactory.getLogger(BRParticleSystem.class);

    // ========================= 粒子結構 =========================

    /** 粒子類型 */
    public enum ParticleType {
        SPARK,          // 火花（建築放置）
        DUST,           // 灰塵（環境）
        FRAGMENT,       // 碎片（破壞）
        GLOW,           // 發光點（選取高光）
        SNOWFLAKE,      // 雪花（天氣）
        BUBBLE,         // 氣泡（水中）
        CONFIRM_RING    // 確認環（UI 回饋）
    }

    // ========================= SoA 粒子池 =========================

    /** 最大粒子數 */
    private static final int MAX_PARTICLES = 8192;

    /** 每粒子 GPU 資料大小（float 數量）: pos(3) + vel(3) + color(4) + size(1) + life(1) + type(1) = 13 */
    private static final int FLOATS_PER_PARTICLE = 13;

    // SoA 陣列（CPU 端）
    private static float[] posX, posY, posZ;
    private static float[] velX, velY, velZ;
    private static float[] colorR, colorG, colorB, colorA;
    private static float[] size;
    private static float[] life;      // 剩餘壽命（秒）
    private static float[] maxLife;   // 初始壽命（用於插值）
    private static int[] type;        // ParticleType.ordinal()
    private static boolean[] alive;

    /** 活躍粒子數 */
    private static int activeCount = 0;

    /** GPU 上傳緩衝 */
    private static float[] gpuBuffer;

    // ========================= GL 資源 =========================

    private static int particleVAO = 0;
    private static int particleVBO = 0;

    /** Billboard quad 頂點（instanced 繪製的基底） */
    private static int quadVAO = 0;
    private static int quadVBO = 0;

    private static boolean initialized = false;

    // ========================= 發射器 =========================

    /** 發射器定義 */
    public static class Emitter {
        public final ParticleType type;
        public float x, y, z;           // 發射位置
        public float spreadX, spreadY, spreadZ; // 發射範圍
        public float minVelY, maxVelY;   // 垂直速度範圍
        public float minLife, maxLife;    // 壽命範圍
        public float minSize, maxSize;   // 大小範圍
        public float r, g, b, a;         // 顏色
        public float gravity;            // 重力（正=向下）
        public int burstCount;           // 每次爆發粒子數
        public float rate;               // 持續發射速率（粒子/秒，0=僅爆發）
        private float accumulator;       // 持續發射累加器

        public Emitter(ParticleType type) {
            this.type = type;
            this.spreadX = 0.5f; this.spreadY = 0.5f; this.spreadZ = 0.5f;
            this.minVelY = 0.5f; this.maxVelY = 2.0f;
            this.minLife = 0.5f; this.maxLife = 1.5f;
            this.minSize = 0.05f; this.maxSize = 0.15f;
            this.r = 1.0f; this.g = 1.0f; this.b = 1.0f; this.a = 1.0f;
            this.gravity = 2.0f;
            this.burstCount = 8;
            this.rate = 0.0f;
        }
    }

    /** 活躍的持續發射器 */
    private static final Deque<Emitter> activeEmitters = new ArrayDeque<>();
    private static final int MAX_EMITTERS = 32;

    // ========================= 初始化 =========================

    public static void init() {
        // 分配 SoA 陣列
        posX = new float[MAX_PARTICLES]; posY = new float[MAX_PARTICLES]; posZ = new float[MAX_PARTICLES];
        velX = new float[MAX_PARTICLES]; velY = new float[MAX_PARTICLES]; velZ = new float[MAX_PARTICLES];
        colorR = new float[MAX_PARTICLES]; colorG = new float[MAX_PARTICLES];
        colorB = new float[MAX_PARTICLES]; colorA = new float[MAX_PARTICLES];
        size = new float[MAX_PARTICLES];
        life = new float[MAX_PARTICLES];
        maxLife = new float[MAX_PARTICLES];
        type = new int[MAX_PARTICLES];
        alive = new boolean[MAX_PARTICLES];

        gpuBuffer = new float[MAX_PARTICLES * FLOATS_PER_PARTICLE];

        // Billboard quad（2 三角形，帶 UV）
        float[] quadVerts = {
            -0.5f, -0.5f, 0.0f, 0.0f,
             0.5f, -0.5f, 1.0f, 0.0f,
             0.5f,  0.5f, 1.0f, 1.0f,
            -0.5f, -0.5f, 0.0f, 0.0f,
             0.5f,  0.5f, 1.0f, 1.0f,
            -0.5f,  0.5f, 0.0f, 1.0f,
        };

        quadVAO = GL30.glGenVertexArrays();
        quadVBO = GL15.glGenBuffers();
        GL30.glBindVertexArray(quadVAO);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadVBO);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buf = stack.mallocFloat(quadVerts.length);
            buf.put(quadVerts).flip();
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buf, GL15.GL_STATIC_DRAW);
        }

        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 16, 0);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 16, 8);

        // 粒子實例資料 VBO
        particleVBO = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, particleVBO);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) MAX_PARTICLES * FLOATS_PER_PARTICLE * 4, GL15.GL_STREAM_DRAW);

        // Instanced attributes（從 location 2 開始）
        int stride = FLOATS_PER_PARTICLE * 4;
        // location 2: position (vec3)
        GL20.glEnableVertexAttribArray(2);
        GL20.glVertexAttribPointer(2, 3, GL11.GL_FLOAT, false, stride, 0);
        GL33.glVertexAttribDivisor(2, 1);

        // location 3: velocity (vec3) — 用於 motion stretch
        GL20.glEnableVertexAttribArray(3);
        GL20.glVertexAttribPointer(3, 3, GL11.GL_FLOAT, false, stride, 12);
        GL33.glVertexAttribDivisor(3, 1);

        // location 4: color (vec4)
        GL20.glEnableVertexAttribArray(4);
        GL20.glVertexAttribPointer(4, 4, GL11.GL_FLOAT, false, stride, 24);
        GL33.glVertexAttribDivisor(4, 1);

        // location 5: size + life + type (vec3)
        GL20.glEnableVertexAttribArray(5);
        GL20.glVertexAttribPointer(5, 3, GL11.GL_FLOAT, false, stride, 40);
        GL33.glVertexAttribDivisor(5, 1);

        GL30.glBindVertexArray(0);

        initialized = true;
        LOGGER.info("BRParticleSystem 初始化完成（最大 {} 粒子）", MAX_PARTICLES);
    }

    public static void cleanup() {
        if (particleVAO != 0) { GL30.glDeleteVertexArrays(particleVAO); particleVAO = 0; }
        if (quadVAO != 0) { GL30.glDeleteVertexArrays(quadVAO); quadVAO = 0; }
        if (quadVBO != 0) { GL15.glDeleteBuffers(quadVBO); quadVBO = 0; }
        if (particleVBO != 0) { GL15.glDeleteBuffers(particleVBO); particleVBO = 0; }

        posX = posY = posZ = null;
        velX = velY = velZ = null;
        colorR = colorG = colorB = colorA = null;
        size = life = maxLife = null;
        type = null;
        alive = null;
        gpuBuffer = null;
        activeEmitters.clear();

        initialized = false;
        LOGGER.info("BRParticleSystem 已清理");
    }

    // ========================= 粒子發射 =========================

    /** 單次爆發 */
    public static void burst(Emitter emitter) {
        for (int i = 0; i < emitter.burstCount; i++) {
            spawnOne(emitter);
        }
    }

    /** 添加持續發射器 */
    public static void addEmitter(Emitter emitter) {
        if (activeEmitters.size() >= MAX_EMITTERS) {
            activeEmitters.pollFirst(); // 移除最舊的
        }
        activeEmitters.add(emitter);
    }

    /** 移除持續發射器 */
    public static void removeEmitter(Emitter emitter) {
        activeEmitters.remove(emitter);
    }

    /** 生成一個粒子 */
    private static void spawnOne(Emitter e) {
        int slot = findFreeSlot();
        if (slot < 0) return; // 池滿

        float rx = (float)(Math.random() * 2.0 - 1.0);
        float ry = (float)(Math.random() * 2.0 - 1.0);
        float rz = (float)(Math.random() * 2.0 - 1.0);

        posX[slot] = e.x + rx * e.spreadX;
        posY[slot] = e.y + ry * e.spreadY;
        posZ[slot] = e.z + rz * e.spreadZ;

        // 發散速度
        velX[slot] = rx * 1.5f;
        velY[slot] = e.minVelY + (float) Math.random() * (e.maxVelY - e.minVelY);
        velZ[slot] = rz * 1.5f;

        colorR[slot] = e.r;
        colorG[slot] = e.g;
        colorB[slot] = e.b;
        colorA[slot] = e.a;

        size[slot] = e.minSize + (float) Math.random() * (e.maxSize - e.minSize);
        life[slot] = e.minLife + (float) Math.random() * (e.maxLife - e.minLife);
        maxLife[slot] = life[slot];
        type[slot] = e.type.ordinal();
        alive[slot] = true;
        activeCount++;
    }

    private static int findFreeSlot() {
        for (int i = 0; i < MAX_PARTICLES; i++) {
            if (!alive[i]) return i;
        }
        return -1;
    }

    // ========================= 預設發射器工廠 =========================

    /** 建築放置火花 */
    public static Emitter createPlacementSpark(float x, float y, float z) {
        Emitter e = new Emitter(ParticleType.SPARK);
        e.x = x; e.y = y; e.z = z;
        e.burstCount = 12;
        e.r = 1.0f; e.g = 0.8f; e.b = 0.3f; e.a = 1.0f;
        e.minSize = 0.02f; e.maxSize = 0.06f;
        e.minLife = 0.3f; e.maxLife = 0.8f;
        e.minVelY = 1.0f; e.maxVelY = 3.0f;
        e.gravity = 5.0f;
        return e;
    }

    /** 方塊破壞碎片 */
    public static Emitter createBreakFragment(float x, float y, float z, float r, float g, float b) {
        Emitter e = new Emitter(ParticleType.FRAGMENT);
        e.x = x; e.y = y; e.z = z;
        e.burstCount = 16;
        e.r = r; e.g = g; e.b = b; e.a = 1.0f;
        e.minSize = 0.04f; e.maxSize = 0.12f;
        e.minLife = 0.5f; e.maxLife = 1.2f;
        e.minVelY = 2.0f; e.maxVelY = 5.0f;
        e.spreadX = 0.3f; e.spreadY = 0.3f; e.spreadZ = 0.3f;
        e.gravity = 9.8f;
        return e;
    }

    /** 選取高光粒子（持續發射） */
    public static Emitter createSelectionGlow(float x, float y, float z) {
        Emitter e = new Emitter(ParticleType.GLOW);
        e.x = x; e.y = y; e.z = z;
        e.rate = 20.0f; // 20 粒子/秒
        e.burstCount = 0;
        e.r = 0.3f; e.g = 0.6f; e.b = 1.0f; e.a = 0.8f;
        e.minSize = 0.03f; e.maxSize = 0.08f;
        e.minLife = 0.5f; e.maxLife = 1.0f;
        e.minVelY = 0.2f; e.maxVelY = 0.5f;
        e.gravity = -0.5f; // 向上漂浮
        return e;
    }

    /** 環境灰塵（持續發射） */
    public static Emitter createDust(float x, float y, float z, float radius) {
        Emitter e = new Emitter(ParticleType.DUST);
        e.x = x; e.y = y; e.z = z;
        e.spreadX = radius; e.spreadY = radius * 0.5f; e.spreadZ = radius;
        e.rate = 5.0f;
        e.burstCount = 0;
        e.r = 0.8f; e.g = 0.75f; e.b = 0.65f; e.a = 0.4f;
        e.minSize = 0.01f; e.maxSize = 0.04f;
        e.minLife = 2.0f; e.maxLife = 5.0f;
        e.minVelY = -0.1f; e.maxVelY = 0.1f;
        e.gravity = 0.2f;
        return e;
    }

    // ========================= 更新 =========================

    /**
     * 每幀更新所有粒子（CPU 端物理）。
     *
     * @param deltaSeconds 幀間隔（秒）
     */
    public static void tick(float deltaSeconds) {
        if (!initialized) return;

        // 更新持續發射器
        for (Emitter e : activeEmitters) {
            if (e.rate > 0.0f) {
                e.accumulator += e.rate * deltaSeconds;
                while (e.accumulator >= 1.0f) {
                    spawnOne(e);
                    e.accumulator -= 1.0f;
                }
            }
        }

        // 更新粒子
        activeCount = 0;
        for (int i = 0; i < MAX_PARTICLES; i++) {
            if (!alive[i]) continue;

            life[i] -= deltaSeconds;
            if (life[i] <= 0.0f) {
                alive[i] = false;
                continue;
            }

            // 簡單歐拉積分
            velY[i] -= 2.0f * deltaSeconds; // 預設重力
            posX[i] += velX[i] * deltaSeconds;
            posY[i] += velY[i] * deltaSeconds;
            posZ[i] += velZ[i] * deltaSeconds;

            // 壽命衰減透明度
            float lifeRatio = life[i] / maxLife[i];
            colorA[i] = lifeRatio;

            activeCount++;
        }
    }

    // ========================= 渲染 =========================

    /**
     * 渲染所有活躍粒子（instanced draw）。
     */
    public static void render(Matrix4f projMatrix, Matrix4f viewMatrix, Vector3f cameraPos) {
        if (!initialized || activeCount == 0) return;

        BRShaderProgram shader = BRShaderEngine.getParticleShader();
        if (shader == null) return;

        // 打包 GPU 資料
        int writeIdx = 0;
        for (int i = 0; i < MAX_PARTICLES; i++) {
            if (!alive[i]) continue;

            gpuBuffer[writeIdx++] = posX[i];
            gpuBuffer[writeIdx++] = posY[i];
            gpuBuffer[writeIdx++] = posZ[i];
            gpuBuffer[writeIdx++] = velX[i];
            gpuBuffer[writeIdx++] = velY[i];
            gpuBuffer[writeIdx++] = velZ[i];
            gpuBuffer[writeIdx++] = colorR[i];
            gpuBuffer[writeIdx++] = colorG[i];
            gpuBuffer[writeIdx++] = colorB[i];
            gpuBuffer[writeIdx++] = colorA[i];
            gpuBuffer[writeIdx++] = size[i];
            gpuBuffer[writeIdx++] = life[i] / maxLife[i]; // 正規化壽命
            gpuBuffer[writeIdx++] = (float) type[i];
        }

        // 上傳 VBO
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, particleVBO);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buf = stack.mallocFloat(writeIdx);
            buf.put(gpuBuffer, 0, writeIdx).flip();
            GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, buf);
        }

        // 繪製
        shader.bind();

        shader.setUniformMat4("u_projMatrix", projMatrix);
        shader.setUniformMat4("u_viewMatrix", viewMatrix);

        shader.setUniformVec3("u_cameraPos", cameraPos.x, cameraPos.y, cameraPos.z);

        // Billboard: 從 view matrix 提取 right / up 向量
        shader.setUniformVec3("u_cameraRight",
            viewMatrix.m00(), viewMatrix.m10(), viewMatrix.m20());
        shader.setUniformVec3("u_cameraUp",
            viewMatrix.m01(), viewMatrix.m11(), viewMatrix.m21());

        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDepthMask(false); // 粒子不寫深度

        GL30.glBindVertexArray(quadVAO);
        GL31.glDrawArraysInstanced(GL11.GL_TRIANGLES, 0, 6, activeCount);
        GL30.glBindVertexArray(0);

        GL11.glDepthMask(true);
        GL11.glDisable(GL11.GL_BLEND);

        shader.unbind();
    }

    // ========================= 統計 =========================

    public static int getActiveCount() { return activeCount; }
    public static int getMaxParticles() { return MAX_PARTICLES; }
    public static boolean isInitialized() { return initialized; }
}
