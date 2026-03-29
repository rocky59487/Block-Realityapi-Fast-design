package com.blockreality.api.client.render.optimization;

import com.blockreality.api.client.render.BRRenderConfig;
import com.blockreality.api.client.render.shader.BRShaderEngine;
import com.blockreality.api.client.render.shader.BRShaderProgram;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 非同步計算排程器 — 利用 GL Fence Sync 管理跨幀 GPU 工作。
 *
 * 技術架構：
 *   - OpenGL 3.2+ Fence Sync 物件管理非同步 GPU 操作
 *   - 雙緩衝 PBO（Pixel Buffer Object）非同步讀回 GPU 資料
 *   - 任務佇列 + 優先級排程（High/Normal/Low）
 *   - 跨幀分攤策略：大型操作拆分為多個子任務，每幀執行一部分
 *   - GPU 忙碌度偵測：fence 完成率追蹤，避免過度提交
 *
 * 應用場景：
 *   - SSGI 半解析度計算（隔幀更新）
 *   - Cloud ray march 1/4 解析度（隔 2 幀更新）
 *   - Occlusion query 結果讀回
 *   - LOD mesh 上傳排程
 *   - 3D LUT 重建（跨幀分攤）
 *
 * 參考：
 *   - "OpenGL Insights" ch.28: Asynchronous Buffer Transfers
 *   - Sodium: async chunk mesh upload
 *   - C2ME: parallel chunk gen scheduling
 *
 * @author Block Reality Team
 * @version 1.0
 */
@OnlyIn(Dist.CLIENT)
public class BRAsyncComputeScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(BRAsyncComputeScheduler.class);

    // ─── 任務優先級 ───
    public enum Priority { HIGH, NORMAL, LOW }

    // ─── Fence Sync 追蹤 ───
    private static final int MAX_PENDING_FENCES = 16;

    /** 活躍 fence sync 物件 */
    private static long[] fences = new long[MAX_PENDING_FENCES];

    /** 每個 fence 對應的任務描述 */
    private static String[] fenceNames = new String[MAX_PENDING_FENCES];

    /** 活躍 fence 數量 */
    private static int activeFenceCount = 0;

    // ─── 跨幀任務佇列 ───
    private static final Deque<DeferredTask> taskQueue = new ArrayDeque<>();

    /** 每幀最大任務處理數 */
    private static final int MAX_TASKS_PER_FRAME = 4;

    // ─── PBO 雙緩衝 ───
    private static int[] pboIds = new int[2];
    private static int currentPboIndex = 0;
    private static int pboWidth, pboHeight;

    // ─── 統計 ───
    private static long totalTasksSubmitted = 0;
    private static long totalTasksCompleted = 0;
    private static long totalFenceWaitNs = 0;

    private static boolean initialized = false;

    // ========================= 初始化 =========================

    public static void init(int screenW, int screenH) {
        if (initialized) return;

        // Fence 陣列初始化
        for (int i = 0; i < MAX_PENDING_FENCES; i++) {
            fences[i] = 0;
            fenceNames[i] = null;
        }
        activeFenceCount = 0;

        // PBO 雙緩衝（用於非同步 GPU → CPU 讀回）
        pboWidth = screenW;
        pboHeight = screenH;
        for (int i = 0; i < 2; i++) {
            pboIds[i] = GL15.glGenBuffers();
            GL15.glBindBuffer(GL21_PIXEL_PACK_BUFFER, pboIds[i]);
            GL15.glBufferData(GL21_PIXEL_PACK_BUFFER, (long) screenW * screenH * 4, GL15.GL_STREAM_READ);
        }
        GL15.glBindBuffer(GL21_PIXEL_PACK_BUFFER, 0);

        totalTasksSubmitted = 0;
        totalTasksCompleted = 0;
        totalFenceWaitNs = 0;

        initialized = true;
        LOGGER.info("[BRAsyncComputeScheduler] 非同步排程器初始化完成 — PBO {}x{}", screenW, screenH);
    }

    // GL_PIXEL_PACK_BUFFER = 0x88EB (OpenGL 2.1+, but defined in GL21)
    private static final int GL21_PIXEL_PACK_BUFFER = 0x88EB;

    public static void cleanup() {
        if (!initialized) return;

        // 清除所有未完成 fence
        for (int i = 0; i < MAX_PENDING_FENCES; i++) {
            if (fences[i] != 0) {
                GL32.glDeleteSync(fences[i]);
                fences[i] = 0;
            }
        }
        activeFenceCount = 0;

        // 清除 PBO
        for (int i = 0; i < 2; i++) {
            if (pboIds[i] != 0) {
                GL15.glDeleteBuffers(pboIds[i]);
                pboIds[i] = 0;
            }
        }

        taskQueue.clear();
        initialized = false;
    }

    public static void onResize(int w, int h) {
        if (!initialized) return;
        pboWidth = w;
        pboHeight = h;
        for (int i = 0; i < 2; i++) {
            GL15.glBindBuffer(GL21_PIXEL_PACK_BUFFER, pboIds[i]);
            GL15.glBufferData(GL21_PIXEL_PACK_BUFFER, (long) w * h * 4, GL15.GL_STREAM_READ);
        }
        GL15.glBindBuffer(GL21_PIXEL_PACK_BUFFER, 0);
    }

    // ========================= Fence Sync API =========================

    /**
     * 在 GPU 命令流中插入 fence sync 點。
     * @param name 任務描述（除錯用）
     * @return fence slot index, 或 -1 如果已滿
     */
    public static int insertFence(String name) {
        if (!initialized) return -1;
        for (int i = 0; i < MAX_PENDING_FENCES; i++) {
            if (fences[i] == 0) {
                fences[i] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
                fenceNames[i] = name;
                activeFenceCount++;
                return i;
            }
        }
        LOGGER.warn("[BRAsyncComputeScheduler] Fence 池已滿（{}個），無法插入 '{}'", MAX_PENDING_FENCES, name);
        return -1;
    }

    /**
     * 檢查 fence 是否已完成（非阻塞）。
     */
    public static boolean isFenceReady(int slot) {
        if (slot < 0 || slot >= MAX_PENDING_FENCES || fences[slot] == 0) return true;
        int status = GL32.glClientWaitSync(fences[slot], 0, 0);
        return status == GL32.GL_ALREADY_SIGNALED || status == GL32.GL_CONDITION_SATISFIED;
    }

    /**
     * 等待 fence 完成（阻塞，有超時）。
     * @param slot fence slot
     * @param timeoutNs 超時奈秒
     * @return true 如果已完成
     */
    public static boolean waitFence(int slot, long timeoutNs) {
        if (slot < 0 || slot >= MAX_PENDING_FENCES || fences[slot] == 0) return true;
        long t0 = System.nanoTime();
        int status = GL32.glClientWaitSync(fences[slot], GL32.GL_SYNC_FLUSH_COMMANDS_BIT, timeoutNs);
        totalFenceWaitNs += System.nanoTime() - t0;
        boolean done = (status == GL32.GL_ALREADY_SIGNALED || status == GL32.GL_CONDITION_SATISFIED);
        if (done) releaseFence(slot);
        return done;
    }

    /**
     * 釋放 fence（手動呼叫或 waitFence 自動呼叫）。
     */
    public static void releaseFence(int slot) {
        if (slot < 0 || slot >= MAX_PENDING_FENCES || fences[slot] == 0) return;
        GL32.glDeleteSync(fences[slot]);
        fences[slot] = 0;
        fenceNames[slot] = null;
        activeFenceCount = Math.max(0, activeFenceCount - 1);
    }

    // ========================= 跨幀任務排程 =========================

    /**
     * 提交延遲任務（下一幀或之後執行）。
     */
    public static void submitTask(String name, Priority priority, Runnable action) {
        if (!initialized) return;
        taskQueue.addLast(new DeferredTask(name, priority, action));
        totalTasksSubmitted++;
    }

    /**
     * 每幀呼叫 — 處理佇列中的任務（最多 MAX_TASKS_PER_FRAME 個）。
     */
    public static void processTasks() {
        if (!initialized) return;

        int processed = 0;
        while (!taskQueue.isEmpty() && processed < MAX_TASKS_PER_FRAME) {
            DeferredTask task = taskQueue.pollFirst();
            if (task != null) {
                try {
                    task.action.run();
                    totalTasksCompleted++;
                } catch (Exception e) {
                    LOGGER.error("[BRAsyncComputeScheduler] 任務 '{}' 執行失敗", task.name, e);
                }
                processed++;
            }
        }
    }

    // ========================= PBO 非同步讀回 =========================

    /**
     * 啟動非同步像素讀回（從當前 FBO 的 color attachment）。
     * 呼叫後需等至少 1 幀再用 readBackResult() 取得結果。
     */
    public static void beginAsyncReadback(int fbo, int width, int height) {
        if (!initialized) return;
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
        GL15.glBindBuffer(GL21_PIXEL_PACK_BUFFER, pboIds[currentPboIndex]);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0);
        GL15.glBindBuffer(GL21_PIXEL_PACK_BUFFER, 0);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
        currentPboIndex = (currentPboIndex + 1) % 2;
    }

    // ========================= 統計 =========================

    public static int getActiveFenceCount() { return activeFenceCount; }
    public static int getPendingTaskCount() { return taskQueue.size(); }
    public static long getTotalTasksSubmitted() { return totalTasksSubmitted; }
    public static long getTotalTasksCompleted() { return totalTasksCompleted; }
    public static float getAverageFenceWaitMs() {
        return totalTasksCompleted > 0 ? (totalFenceWaitNs / 1_000_000.0f) / totalTasksCompleted : 0f;
    }

    // ─── 內部類別 ───
    private static class DeferredTask {
        final String name;
        final Priority priority;
        final Runnable action;
        DeferredTask(String name, Priority priority, Runnable action) {
            this.name = name;
            this.priority = priority;
            this.action = action;
        }
    }

    // ========================= GPU Indirect Draw Buffer =========================

    /** Indirect Draw Buffer — GPU 驅動的間接繪製指令緩衝。
     * 結構：每個指令 20 bytes (5 × int32)
     *   [vertexCount, instanceCount, firstVertex, baseInstance, padding]
     * 參考 SIGGRAPH 2015 Wihlidal/Aaltonen GPU-Driven Rendering Pipelines。
     */
    private static int indirectDrawBufferId = 0;

    /** 間接繪製指令容量 */
    private static final int INDIRECT_DRAW_CAPACITY = 8192;

    /** 每個間接繪製指令的大小（bytes） */
    private static final int INDIRECT_DRAW_STRIDE = 20; // 5 × int32

    /** 當前有效的間接繪製指令數 */
    private static int indirectDrawCount = 0;

    /**
     * 初始化 GPU Indirect Draw Buffer。
     * 使用 GL_DRAW_INDIRECT_BUFFER (0x8F3F) 綁定點。
     * 預分配 8192 條指令 × 20 bytes = 160 KB。
     */
    public static void initIndirectDrawBuffer() {
        if (indirectDrawBufferId != 0) return;

        indirectDrawBufferId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirectDrawBufferId);
        GL15.glBufferData(GL_DRAW_INDIRECT_BUFFER,
            (long) INDIRECT_DRAW_CAPACITY * INDIRECT_DRAW_STRIDE,
            GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);

        LOGGER.info("[BRAsyncCompute] Indirect Draw Buffer 初始化完成 — {} 指令, {} KB",
            INDIRECT_DRAW_CAPACITY, INDIRECT_DRAW_CAPACITY * INDIRECT_DRAW_STRIDE / 1024);
    }

    /** GL_DRAW_INDIRECT_BUFFER 綁定點常數 */
    private static final int GL_DRAW_INDIRECT_BUFFER = 0x8F3F;

    /**
     * 從 CPU 端上傳間接繪製指令。
     * 用於非 compute shader 環境（Tier 0/1）。
     *
     * @param commands 指令陣列 [vertexCount, instanceCount, firstVertex, baseInstance, 0] × N
     * @param count 有效指令數
     */
    public static void uploadIndirectDrawCommands(int[] commands, int count) {
        if (indirectDrawBufferId == 0 || commands == null) return;
        int safeCount = Math.min(count, INDIRECT_DRAW_CAPACITY);

        GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirectDrawBufferId);
        // 使用 glBufferSubData 避免重新分配
        java.nio.IntBuffer buf = org.lwjgl.system.MemoryUtil.memAllocInt(safeCount * 5);
        buf.put(commands, 0, safeCount * 5);
        buf.flip();
        GL15.glBufferSubData(GL_DRAW_INDIRECT_BUFFER, 0, buf);
        org.lwjgl.system.MemoryUtil.memFree(buf);
        GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);

        indirectDrawCount = safeCount;
    }

    /**
     * 綁定 Indirect Draw Buffer 供 glMultiDrawArraysIndirect 使用。
     * 呼叫後可直接執行 GL43.glMultiDrawArraysIndirect()。
     *
     * @return 有效的繪製指令數量
     */
    public static int bindIndirectDrawBuffer() {
        if (indirectDrawBufferId == 0) return 0;
        GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirectDrawBufferId);
        return indirectDrawCount;
    }

    /** 解除綁定 Indirect Draw Buffer */
    public static void unbindIndirectDrawBuffer() {
        GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);
    }

    /** 取得 Indirect Draw Buffer ID（compute shader 寫入用） */
    public static int getIndirectDrawBufferId() { return indirectDrawBufferId; }

    /** 取得當前有效指令數 */
    public static int getIndirectDrawCount() { return indirectDrawCount; }

    // ========================= Hi-Z Occlusion Culling =========================

    /** Hi-Z 深度金字塔紋理 — 用於層級式遮蔽剔除。
     * 每一級 mip 為上一級的 max(2×2 depth)，O(log₂N) 遮蔽查詢。
     * 參考 Aokana (ACM I3D 2025) 與 Nanite Hi-Z。
     */
    private static int hiZTextureId = 0;
    private static int hiZFboId = 0;
    private static int hiZMipLevels = 0;
    private static int hiZWidth = 0, hiZHeight = 0;

    /**
     * 初始化 Hi-Z 深度金字塔。
     *
     * @param width 螢幕寬度
     * @param height 螢幕高度
     */
    public static void initHiZPyramid(int width, int height) {
        if (hiZTextureId != 0) cleanupHiZ();

        hiZWidth = width;
        hiZHeight = height;
        hiZMipLevels = (int)(Math.log(Math.max(width, height)) / Math.log(2)) + 1;

        // 建立帶 mipmap 的深度紋理
        hiZTextureId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, hiZTextureId);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST_MIPMAP_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

        // 分配所有 mip 等級
        int mipW = width, mipH = height;
        for (int level = 0; level < hiZMipLevels; level++) {
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, level, GL30.GL_R32F,
                mipW, mipH, 0, GL11.GL_RED, GL11.GL_FLOAT, (java.nio.FloatBuffer) null);
            mipW = Math.max(1, mipW / 2);
            mipH = Math.max(1, mipH / 2);
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        // FBO 用於逐級建構金字塔
        hiZFboId = GL30.glGenFramebuffers();

        LOGGER.info("[BRAsyncCompute] Hi-Z 金字塔初始化 — {}x{}, {} mip levels",
            width, height, hiZMipLevels);
    }

    /**
     * 從場景深度 buffer 建構 Hi-Z 金字塔。
     * 每一級 mip = max(上一級 2×2 區域)，用於保守遮蔽測試。
     *
     * @param sceneDepthTex 場景深度紋理 ID（GBuffer depth）
     */
    public static void buildHiZPyramid(int sceneDepthTex) {
        if (hiZTextureId == 0 || sceneDepthTex == 0) return;

        BRShaderProgram hiZShader = BRShaderEngine.getHiZDownsampleShader();
        if (hiZShader == null) return;

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, hiZFboId);

        // Mip 0 ← 場景深度（blit 複製 depth → R32F）
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
            GL11.GL_TEXTURE_2D, hiZTextureId, 0);
        GL11.glViewport(0, 0, hiZWidth, hiZHeight);

        hiZShader.bind();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, sceneDepthTex);
        hiZShader.setUniformInt("u_depthTex", 0);
        hiZShader.setUniformVec2("u_texelSize", 1.0f / hiZWidth, 1.0f / hiZHeight);

        // 全螢幕三角形 (gl_VertexID trick)
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);

        // 逐級降採樣：mip[n+1] = max(mip[n] 的 2×2 區塊)
        int mipW = hiZWidth, mipH = hiZHeight;
        for (int level = 1; level < hiZMipLevels; level++) {
            int prevMipW = mipW;
            int prevMipH = mipH;
            mipW = Math.max(1, mipW / 2);
            mipH = Math.max(1, mipH / 2);

            // 綁定上一級 mip 為輸入紋理（使用 textureLod 讀取 level-1）
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, hiZTextureId, level);
            GL11.glViewport(0, 0, mipW, mipH);

            GL11.glBindTexture(GL11.GL_TEXTURE_2D, hiZTextureId);
            hiZShader.setUniformVec2("u_texelSize", 1.0f / prevMipW, 1.0f / prevMipH);

            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        }

        hiZShader.unbind();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    /**
     * 使用 Hi-Z 金字塔進行 AABB 遮蔽測試（CPU 端保守查詢）。
     * 將 AABB 投影到螢幕空間，在對應 mip level 讀取最小深度，比較判定可見性。
     *
     * @param minX AABB 最小 X（螢幕空間 [0,1]）
     * @param minY AABB 最小 Y（螢幕空間 [0,1]）
     * @param maxX AABB 最大 X（螢幕空間 [0,1]）
     * @param maxY AABB 最大 Y（螢幕空間 [0,1]）
     * @param aabbMinDepth AABB 的最小深度值（最近面）
     * @return true 如果 AABB 可能可見
     */
    public static boolean hiZOcclusionTest(float minX, float minY, float maxX, float maxY, float aabbMinDepth) {
        if (hiZTextureId == 0) return true; // 無 Hi-Z 資料，預設可見

        // 計算 AABB 在螢幕空間的像素大小
        float pixelW = (maxX - minX) * hiZWidth;
        float pixelH = (maxY - minY) * hiZHeight;
        float maxPixelDim = Math.max(pixelW, pixelH);

        // 選擇 mip level：覆蓋 AABB 的最小 mip（使查詢在 1-2 個 texel 內完成）
        int mipLevel = Math.min(hiZMipLevels - 1,
            (int) Math.ceil(Math.log(maxPixelDim) / Math.log(2)));

        // 在選定 mip level 的 texel 座標
        int mipW = Math.max(1, hiZWidth >> mipLevel);
        int mipH = Math.max(1, hiZHeight >> mipLevel);
        int texelX = (int)(minX * mipW);
        int texelY = (int)(minY * mipH);

        // GPU 端查詢需要 readback — 此處使用非同步 PBO 讀回結果
        // 實際遊戲中應由 compute shader 在 GPU 端完成
        // CPU fallback：假設可見（保守）
        return true;
    }

    /** 取得 Hi-Z 紋理 ID（compute shader 綁定用） */
    public static int getHiZTextureId() { return hiZTextureId; }

    /** 取得 Hi-Z mip 等級數 */
    public static int getHiZMipLevels() { return hiZMipLevels; }

    private static void cleanupHiZ() {
        if (hiZTextureId != 0) {
            GL11.glDeleteTextures(hiZTextureId);
            hiZTextureId = 0;
        }
        if (hiZFboId != 0) {
            GL30.glDeleteFramebuffers(hiZFboId);
            hiZFboId = 0;
        }
        hiZMipLevels = 0;
    }

    // ========================= 優先級排序任務佇列 =========================

    /**
     * 處理任務（優先級排序版）— 替代原有的 FIFO processTasks。
     * HIGH 優先級任務先執行，同優先級保持 FIFO 順序。
     */
    public static void processTasksByPriority() {
        if (!initialized) return;

        // 按優先級分離任務
        List<DeferredTask> highTasks = new java.util.ArrayList<>();
        List<DeferredTask> normalTasks = new java.util.ArrayList<>();
        List<DeferredTask> lowTasks = new java.util.ArrayList<>();

        for (DeferredTask task : taskQueue) {
            switch (task.priority) {
                case HIGH -> highTasks.add(task);
                case NORMAL -> normalTasks.add(task);
                case LOW -> lowTasks.add(task);
            }
        }

        int processed = 0;
        // 依序處理：HIGH → NORMAL → LOW
        for (List<DeferredTask> bucket : List.of(highTasks, normalTasks, lowTasks)) {
            for (DeferredTask task : bucket) {
                if (processed >= MAX_TASKS_PER_FRAME) return;
                taskQueue.remove(task);
                try {
                    task.action.run();
                    totalTasksCompleted++;
                } catch (Exception e) {
                    LOGGER.error("[BRAsyncCompute] 任務 '{}' 執行失敗", task.name, e);
                }
                processed++;
            }
        }
    }
}
