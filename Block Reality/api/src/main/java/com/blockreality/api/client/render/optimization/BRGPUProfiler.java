package com.blockreality.api.client.render.optimization;

import com.blockreality.api.client.render.BRRenderConfig;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GPU Timeline Profiler — 精準量測每個渲染 pass 的 GPU 耗時。
 *
 * 技術架構：
 *   - GL_TIME_ELAPSED（OpenGL 3.3 Timer Query）
 *   - 雙緩衝查詢池（Frame N 提交，Frame N+1 讀回，避免 stall）
 *   - 命名區間（begin/end pass tag）
 *   - 滾動平均統計（64 幀）
 *   - 瓶頸偵測（標記最耗時的前 3 個 pass）
 *   - 整合到 BRDebugOverlay 的效能 HUD 顯示
 *
 * 使用方式：
 *   BRGPUProfiler.beginPass("Shadow");
 *   ... 渲染 shadow pass ...
 *   BRGPUProfiler.endPass("Shadow");
 *
 * 參考：
 *   - "OpenGL SuperBible" ch.11: Timer Queries
 *   - RenderDoc / NSight style GPU profiling
 *   - Sodium: chunk render timing
 *
 * @author Block Reality Team
 * @version 1.0
 */
@OnlyIn(Dist.CLIENT)
public class BRGPUProfiler {

    private static final Logger LOGGER = LoggerFactory.getLogger(BRGPUProfiler.class);

    /** 最大同時追蹤的 pass 數 */
    private static final int MAX_PASSES = 32;

    /** 滾動平均幀數 */
    private static final int AVG_FRAMES = 64;

    // ─── 雙緩衝 Timer Query ───
    // 每個 pass 需要 2 個 query（begin/end），2 組緩衝 = 4 個 query per pass
    private static int[][] queryBeginIds; // [buffer][pass]
    private static int[][] queryEndIds;   // [buffer][pass]
    private static int currentBuffer = 0; // 0 或 1

    // ─── Pass 名稱 → 索引映射 ───
    private static final LinkedHashMap<String, Integer> passNameToIndex = new LinkedHashMap<>();
    private static String[] passNames = new String[MAX_PASSES];
    private static int passCount = 0;

    // ─── 統計結果（奈秒） ───
    private static long[] lastPassTimeNs = new long[MAX_PASSES];
    private static float[][] rollingHistory; // [pass][frame]
    private static int rollingIndex = 0;
    private static boolean rollingFull = false;

    // ─── 幀級統計 ───
    private static long frameTotalGPUTimeNs = 0;
    private static String bottleneckPass = "";
    private static float bottleneckTimeMs = 0;

    /** 當前幀是否已在 profiling 中 */
    private static boolean frameActive = false;

    private static boolean initialized = false;
    private static boolean enabled = false;

    // ========================= 初始化 =========================

    public static void init() {
        if (initialized) return;

        queryBeginIds = new int[2][MAX_PASSES];
        queryEndIds = new int[2][MAX_PASSES];

        for (int b = 0; b < 2; b++) {
            for (int p = 0; p < MAX_PASSES; p++) {
                queryBeginIds[b][p] = GL15.glGenQueries();
                queryEndIds[b][p] = GL15.glGenQueries();
            }
        }

        lastPassTimeNs = new long[MAX_PASSES];
        rollingHistory = new float[MAX_PASSES][AVG_FRAMES];
        passCount = 0;
        passNameToIndex.clear();
        rollingIndex = 0;
        rollingFull = false;
        currentBuffer = 0;
        enabled = BRRenderConfig.GPU_PROFILER_ENABLED;

        initialized = true;
        LOGGER.info("[BRGPUProfiler] GPU Timeline Profiler 初始化完成 — {} query 池", MAX_PASSES * 4);
    }

    public static void cleanup() {
        if (!initialized) return;
        for (int b = 0; b < 2; b++) {
            for (int p = 0; p < MAX_PASSES; p++) {
                GL15.glDeleteQueries(queryBeginIds[b][p]);
                GL15.glDeleteQueries(queryEndIds[b][p]);
            }
        }
        passNameToIndex.clear();
        initialized = false;
    }

    // ========================= 開關 =========================

    public static void setEnabled(boolean on) { enabled = on; }
    public static boolean isEnabled() { return enabled && initialized; }

    // ========================= 每幀生命週期 =========================

    /**
     * 幀開始 — 讀回前一幀的 timer query 結果。
     */
    public static void beginFrame() {
        if (!initialized || !enabled) return;
        frameActive = true;

        int readBuffer = 1 - currentBuffer; // 讀前一幀的 buffer

        frameTotalGPUTimeNs = 0;
        bottleneckTimeMs = 0;
        bottleneckPass = "";

        for (int i = 0; i < passCount; i++) {
            // 檢查是否就緒
            int available = GL15.glGetQueryObjecti(queryEndIds[readBuffer][i],
                GL15.GL_QUERY_RESULT_AVAILABLE);
            if (available == 1) { // GL_TRUE
                long beginNs = GL33.glGetQueryObjecti64(queryBeginIds[readBuffer][i],
                    GL15.GL_QUERY_RESULT);
                long endNs = GL33.glGetQueryObjecti64(queryEndIds[readBuffer][i],
                    GL15.GL_QUERY_RESULT);
                long elapsed = endNs - beginNs;
                lastPassTimeNs[i] = elapsed;
                frameTotalGPUTimeNs += elapsed;

                float elapsedMs = elapsed / 1_000_000.0f;
                rollingHistory[i][rollingIndex] = elapsedMs;

                if (elapsedMs > bottleneckTimeMs) {
                    bottleneckTimeMs = elapsedMs;
                    bottleneckPass = passNames[i];
                }
            }
        }

        rollingIndex = (rollingIndex + 1) % AVG_FRAMES;
        if (rollingIndex == 0) rollingFull = true;
    }

    /**
     * 幀結束 — 切換 query buffer。
     */
    public static void endFrame() {
        if (!initialized || !enabled) return;
        currentBuffer = 1 - currentBuffer;
        frameActive = false;
    }

    // ========================= Pass 計時 =========================

    /**
     * 開始計時指定 pass。
     */
    public static void beginPass(String name) {
        if (!initialized || !enabled || !frameActive) return;

        int idx = getOrCreatePassIndex(name);
        if (idx < 0) return;

        GL33.glQueryCounter(queryBeginIds[currentBuffer][idx], GL33.GL_TIMESTAMP);
    }

    /**
     * 結束計時指定 pass。
     */
    public static void endPass(String name) {
        if (!initialized || !enabled || !frameActive) return;

        Integer idx = passNameToIndex.get(name);
        if (idx == null) return;

        GL33.glQueryCounter(queryEndIds[currentBuffer][idx], GL33.GL_TIMESTAMP);
    }

    // ========================= 查詢結果 =========================

    /**
     * 取得指定 pass 的上一幀 GPU 耗時（毫秒）。
     */
    public static float getPassTimeMs(String name) {
        Integer idx = passNameToIndex.get(name);
        if (idx == null) return 0.0f;
        return lastPassTimeNs[idx] / 1_000_000.0f;
    }

    /**
     * 取得指定 pass 的滾動平均 GPU 耗時（毫秒）。
     */
    public static float getPassAverageMs(String name) {
        Integer idx = passNameToIndex.get(name);
        if (idx == null) return 0.0f;
        int count = rollingFull ? AVG_FRAMES : rollingIndex;
        if (count == 0) return 0.0f;
        float sum = 0;
        for (int i = 0; i < count; i++) {
            sum += rollingHistory[idx][i];
        }
        return sum / count;
    }

    /**
     * 取得所有 pass 的統計快照（名稱 → 平均耗時 ms）。
     */
    public static Map<String, Float> getAllPassAverages() {
        Map<String, Float> result = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : passNameToIndex.entrySet()) {
            result.put(entry.getKey(), getPassAverageMs(entry.getKey()));
        }
        return result;
    }

    /** 本幀 GPU 總耗時（ms） */
    public static float getFrameTotalGPUTimeMs() {
        return frameTotalGPUTimeNs / 1_000_000.0f;
    }

    /** 瓶頸 pass 名稱 */
    public static String getBottleneckPass() { return bottleneckPass; }

    /** 瓶頸 pass 耗時（ms） */
    public static float getBottleneckTimeMs() { return bottleneckTimeMs; }

    /** 已追蹤 pass 數 */
    public static int getPassCount() { return passCount; }

    // ========================= 內部 =========================

    private static int getOrCreatePassIndex(String name) {
        Integer idx = passNameToIndex.get(name);
        if (idx != null) return idx;
        if (passCount >= MAX_PASSES) return -1;
        int newIdx = passCount++;
        passNameToIndex.put(name, newIdx);
        passNames[newIdx] = name;
        return newIdx;
    }
}
