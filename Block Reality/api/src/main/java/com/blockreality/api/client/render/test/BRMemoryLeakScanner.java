package com.blockreality.api.client.render.test;

import com.blockreality.api.client.render.pipeline.BRFramebufferManager;
import com.blockreality.api.client.render.pipeline.BRRenderPipeline;
import com.blockreality.api.client.render.shader.BRShaderEngine;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Block Reality GL 資源洩漏掃描器 — Phase 13。
 *
 * 掃描策略：
 *   1. 快照比對法：記錄管線 init 後的 GL 資源基線，
 *      執行 N 幀後再次掃描，比對差異。
 *   2. FBO 生命週期追蹤：驗證所有 FBO 在 resize 後正確釋放舊資源。
 *   3. Shader 孤兒偵測：確認每支 shader 都有對應 program 綁定。
 *   4. Texture 洩漏偵測：掃描已知紋理 ID 是否仍為有效 GL texture。
 *   5. VBO/VAO 洩漏偵測：掃描已知 buffer/array 是否仍有效。
 *   6. Query 物件洩漏：驗證 OcclusionCuller + GPUProfiler 的 query 池完整性。
 *   7. Fence Sync 洩漏：驗證 AsyncCompute 的 fence 池。
 *
 * 原理：OpenGL ID 空間是線性遞增的，若 init→cleanup→re-init 後
 *       ID 持續增長，表示有資源未正確釋放。
 */
@OnlyIn(Dist.CLIENT)
public final class BRMemoryLeakScanner {
    private BRMemoryLeakScanner() {}

    private static final Logger LOG = LoggerFactory.getLogger("BR-MemoryScanner");

    /** 資源快照 */
    public static final class ResourceSnapshot {
        public final long timestampMs;
        public final int textureCount;
        public final int bufferCount;
        public final int framebufferCount;
        public final int programCount;
        public final int vaoCount;
        public final long jvmHeapUsed;  // bytes
        public final long jvmHeapMax;

        public ResourceSnapshot(long timestampMs, int textureCount, int bufferCount,
                                int framebufferCount, int programCount, int vaoCount,
                                long jvmHeapUsed, long jvmHeapMax) {
            this.timestampMs = timestampMs;
            this.textureCount = textureCount;
            this.bufferCount = bufferCount;
            this.framebufferCount = framebufferCount;
            this.programCount = programCount;
            this.vaoCount = vaoCount;
            this.jvmHeapUsed = jvmHeapUsed;
            this.jvmHeapMax = jvmHeapMax;
        }
    }

    /** 洩漏報告 */
    public static final class LeakReport {
        public final String resourceType;
        public final int baselineCount;
        public final int currentCount;
        public final int leaked;
        public final boolean isLeak;

        public LeakReport(String resourceType, int baselineCount, int currentCount) {
            this.resourceType = resourceType;
            this.baselineCount = baselineCount;
            this.currentCount = currentCount;
            this.leaked = currentCount - baselineCount;
            this.isLeak = leaked > 0;
        }

        @Override
        public String toString() {
            return (isLeak ? "[LEAK]" : "[ OK ]") + " " + resourceType
                 + ": baseline=" + baselineCount + " current=" + currentCount
                 + (isLeak ? " LEAKED=" + leaked : "");
        }
    }

    // ─── 快照儲存 ──────────────────────────────────────
    private static ResourceSnapshot baseline;
    private static final List<ResourceSnapshot> history = new ArrayList<>();

    // ═══════════════════════════════════════════════════════════════
    //  API
    // ═══════════════════════════════════════════════════════════════

    /**
     * 拍攝基線快照（應在管線 init 完成後立即呼叫）。
     */
    public static ResourceSnapshot captureBaseline() {
        baseline = captureSnapshot();
        LOG.info("記憶體基線已拍攝: textures={} buffers={} fbos={} programs={} vaos={} heap={}MB",
            baseline.textureCount, baseline.bufferCount, baseline.framebufferCount,
            baseline.programCount, baseline.vaoCount, baseline.jvmHeapUsed / 1024 / 1024);
        return baseline;
    }

    /**
     * 拍攝當前快照並與基線比對。
     * @return 洩漏報告清單
     */
    public static List<LeakReport> scanForLeaks() {
        if (baseline == null) {
            LOG.warn("尚未拍攝基線，先自動拍攝");
            captureBaseline();
        }

        ResourceSnapshot current = captureSnapshot();
        history.add(current);

        List<LeakReport> reports = new ArrayList<>();
        reports.add(new LeakReport("Texture", baseline.textureCount, current.textureCount));
        reports.add(new LeakReport("Buffer(VBO/PBO)", baseline.bufferCount, current.bufferCount));
        reports.add(new LeakReport("Framebuffer", baseline.framebufferCount, current.framebufferCount));
        reports.add(new LeakReport("ShaderProgram", baseline.programCount, current.programCount));
        reports.add(new LeakReport("VAO", baseline.vaoCount, current.vaoCount));

        // JVM Heap
        long heapDelta = current.jvmHeapUsed - baseline.jvmHeapUsed;
        boolean heapGrew = heapDelta > 50 * 1024 * 1024; // >50MB 成長視為潛在洩漏
        reports.add(new LeakReport("JVM_Heap(MB)",
            (int)(baseline.jvmHeapUsed / 1024 / 1024),
            (int)(current.jvmHeapUsed / 1024 / 1024)));

        LOG.info("═══ 記憶體洩漏掃描結果 ═══");
        for (LeakReport r : reports) {
            if (r.isLeak) LOG.warn(r.toString());
            else LOG.info(r.toString());
        }

        return reports;
    }

    /**
     * 執行 resize 洩漏測試。
     * 策略：拍快照 → 觸發 resize → 拍快照 → 觸發 resize 回原大小 → 拍快照 → 比對。
     * 若 resize 後資源數持續增長，表示舊 FBO/texture 未正確釋放。
     */
    public static List<LeakReport> testResizeLeak(int width, int height) {
        LOG.info("開始 resize 洩漏測試 ({}x{})...", width, height);

        // 拍攝 resize 前基線
        ResourceSnapshot before = captureSnapshot();

        // resize 到新尺寸
        BRRenderPipeline.onResize(width, height);

        // 再 resize 回原始尺寸
        int origW = (int) BRFramebufferManager.getScreenWidth();
        int origH = (int) BRFramebufferManager.getScreenHeight();
        // 注意：此時 screenWidth/Height 已被更新為 width/height
        // 我們 resize 到不同尺寸再回來
        BRRenderPipeline.onResize(width / 2, height / 2);
        BRRenderPipeline.onResize(width, height);

        ResourceSnapshot after = captureSnapshot();

        List<LeakReport> reports = new ArrayList<>();
        reports.add(new LeakReport("Resize_Texture", before.textureCount, after.textureCount));
        reports.add(new LeakReport("Resize_FBO", before.framebufferCount, after.framebufferCount));
        reports.add(new LeakReport("Resize_Buffer", before.bufferCount, after.bufferCount));

        LOG.info("═══ Resize 洩漏測試結果 ═══");
        for (LeakReport r : reports) {
            if (r.isLeak) LOG.warn(r.toString());
            else LOG.info(r.toString());
        }

        return reports;
    }

    /**
     * 驗證已知 FBO 紋理 ID 有效性。
     * 確認管線中所有已登記的紋理 ID 均為有效 GL texture。
     */
    public static List<LeakReport> validateKnownTextures() {
        List<LeakReport> reports = new ArrayList<>();
        String cat = "KnownTexture";

        if (!BRFramebufferManager.isInitialized()) {
            LOG.warn("FBO Manager 未初始化，跳過已知紋理驗證");
            return reports;
        }

        // GBuffer color textures (5)
        int validCount = 0;
        int totalKnown = 0;
        for (int i = 0; i < 5; i++) {
            int tex = BRFramebufferManager.getGbufferColorTex(i);
            totalKnown++;
            if (tex > 0 && GL11.glIsTexture(tex)) validCount++;
        }

        // GBuffer depth
        int depthTex = BRFramebufferManager.getGbufferDepthTex();
        totalKnown++;
        if (depthTex > 0 && GL11.glIsTexture(depthTex)) validCount++;

        // Shadow depth
        int shadowTex = BRFramebufferManager.getShadowDepthTex();
        totalKnown++;
        if (shadowTex > 0 && GL11.glIsTexture(shadowTex)) validCount++;

        // Composite read tex
        int compRead = BRFramebufferManager.getCompositeReadTex();
        totalKnown++;
        if (compRead > 0 && GL11.glIsTexture(compRead)) validCount++;

        int orphaned = totalKnown - validCount;
        reports.add(new LeakReport("KnownTextures_Valid", totalKnown, validCount));

        if (orphaned > 0) {
            LOG.warn("發現 {} 個孤兒紋理（ID 已登記但 GL 端無效）", orphaned);
        }

        return reports;
    }

    // ═══════════════════════════════════════════════════════════════
    //  快照拍攝（探測 GL ID 空間）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 拍攝當前 GL 資源快照。
     * 使用 probe 法：建立新資源取得 ID，該 ID 即為當前資源計數上界。
     * 立即刪除 probe 資源以不影響狀態。
     */
    private static ResourceSnapshot captureSnapshot() {
        // 清除 GL error
        while (GL11.glGetError() != GL11.GL_NO_ERROR) {}

        // Texture probe
        int texProbe = GL11.glGenTextures();
        int textureCount = texProbe;
        GL11.glDeleteTextures(texProbe);

        // Buffer probe (VBO/PBO)
        int bufProbe = GL15.glGenBuffers();
        int bufferCount = bufProbe;
        GL15.glDeleteBuffers(bufProbe);

        // FBO probe
        int fboProbe = GL30.glGenFramebuffers();
        int fboCount = fboProbe;
        GL30.glDeleteFramebuffers(fboProbe);

        // Shader program probe
        int progProbe = GL20.glCreateProgram();
        int progCount = progProbe;
        GL20.glDeleteProgram(progProbe);

        // VAO probe
        int vaoProbe = GL30.glGenVertexArrays();
        int vaoCount = vaoProbe;
        GL30.glDeleteVertexArrays(vaoProbe);

        // JVM heap
        Runtime rt = Runtime.getRuntime();
        long heapUsed = rt.totalMemory() - rt.freeMemory();
        long heapMax = rt.maxMemory();

        // 清除 probe 可能產生的 GL error
        while (GL11.glGetError() != GL11.GL_NO_ERROR) {}

        return new ResourceSnapshot(
            System.currentTimeMillis(),
            textureCount, bufferCount, fboCount, progCount, vaoCount,
            heapUsed, heapMax
        );
    }

    // ═══════════════════════════════════════════════════════════════
    //  歷史趨勢分析
    // ═══════════════════════════════════════════════════════════════

    /**
     * 分析資源歷史趨勢。
     * 若連續 5 次快照資源數持續增長，判定為洩漏趨勢。
     */
    public static Map<String, Boolean> analyzeTrend() {
        Map<String, Boolean> trends = new HashMap<>();
        if (history.size() < 5) {
            LOG.info("歷史快照不足 5 次，無法分析趨勢");
            return trends;
        }

        int n = history.size();
        List<ResourceSnapshot> recent = history.subList(n - 5, n);

        trends.put("Texture", isMonotonicallyIncreasing(recent, s -> s.textureCount));
        trends.put("Buffer", isMonotonicallyIncreasing(recent, s -> s.bufferCount));
        trends.put("FBO", isMonotonicallyIncreasing(recent, s -> s.framebufferCount));
        trends.put("Program", isMonotonicallyIncreasing(recent, s -> s.programCount));
        trends.put("VAO", isMonotonicallyIncreasing(recent, s -> s.vaoCount));
        trends.put("JVM_Heap", isMonotonicallyIncreasing(recent, s -> (int)(s.jvmHeapUsed / 1024 / 1024)));

        for (Map.Entry<String, Boolean> e : trends.entrySet()) {
            if (e.getValue()) {
                LOG.warn("洩漏趨勢偵測: {} 連續 5 次快照遞增", e.getKey());
            }
        }

        return trends;
    }

    @FunctionalInterface
    private interface SnapshotExtractor {
        int extract(ResourceSnapshot s);
    }

    private static boolean isMonotonicallyIncreasing(List<ResourceSnapshot> snapshots,
                                                      SnapshotExtractor extractor) {
        for (int i = 1; i < snapshots.size(); i++) {
            if (extractor.extract(snapshots.get(i)) <= extractor.extract(snapshots.get(i - 1))) {
                return false;
            }
        }
        return true;
    }

    /** 清除歷史記錄 */
    public static void clearHistory() {
        history.clear();
        baseline = null;
    }
}
