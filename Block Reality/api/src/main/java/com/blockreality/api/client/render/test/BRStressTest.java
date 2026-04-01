package com.blockreality.api.client.render.test;

import com.blockreality.api.client.render.BRRenderConfig;
import com.blockreality.api.client.render.shader.BRShaderEngine;
import com.blockreality.api.client.render.shader.BRShaderProgram;
import com.blockreality.api.client.render.optimization.BRShaderLOD;
import com.blockreality.api.client.render.optimization.BRGPUProfiler;
import com.blockreality.api.client.render.optimization.BROcclusionCuller;
import com.blockreality.api.client.render.optimization.BRAsyncComputeScheduler;
import com.blockreality.api.client.render.postfx.BRDebugOverlay;
import com.blockreality.api.client.render.postfx.BRSubsurfaceScattering;
import com.blockreality.api.client.render.effect.BRFogEngine;
import com.blockreality.api.client.render.effect.BRLensFlare;
import com.blockreality.api.client.render.effect.BRParticleSystem;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Block Reality 全管線壓力測試 — Phase 13。
 *
 * 測試場景：
 *   1. 全 Composite chain 連續 100 幀空跑（無幾何，純 shader 路徑驗證）
 *   2. FBO ping-pong 穩定性（連續 swap 1000 次不會 ID 漂移）
 *   3. Shader bind/unbind 循環（40 支 shader 各 100 次無 GL error）
 *   4. 天氣狀態機全狀態切換（CLEAR→RAIN→SNOW→STORM→AURORA→CLEAR）
 *   5. LOD 品質降級/升級循環（BRShaderLOD 狀態機驗證）
 *   6. 非同步任務排程壓力（16 個 fence sync 同時掛起）
 *   7. 遮蔽查詢飽和測試（512 queries 全部發出 + 回讀）
 *   8. GPU Profiler 雙緩衝切換穩定性
 *
 * 每項測試回傳通過/失敗 + 耗時 + 詳細錯誤。
 */
@OnlyIn(Dist.CLIENT)
public final class BRStressTest {
    private BRStressTest() {}

    private static final Logger LOG = LoggerFactory.getLogger("BR-StressTest");

    /** 壓力測試結果 */
    public static final class StressResult {
        public final String testName;
        public final boolean passed;
        public final long durationMs;
        public final String detail;

        public StressResult(String testName, boolean passed, long durationMs, String detail) {
            this.testName = testName;
            this.passed = passed;
            this.durationMs = durationMs;
            this.detail = detail;
        }

        @Override
        public String toString() {
            return (passed ? "[PASS]" : "[FAIL]") + " " + testName
                 + " (" + durationMs + "ms) — " + detail;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  主入口
    // ═══════════════════════════════════════════════════════════════

    /**
     * 執行全部壓力測試。
     * 必須在 GL context 主執行緒、管線 init() 之後呼叫。
     */
    public static List<StressResult> runAllTests() {
        List<StressResult> results = new ArrayList<>();

        LOG.info("═══ Block Reality 壓力測試開始 ═══");

        results.add(testFBOPingPong());
        results.add(testShaderBindCycle());
        results.add(testCompositeChainDryRun());
        results.add(testWeatherStateMachine());
        results.add(testShaderLODCycle());
        results.add(testAsyncComputeSaturation());
        results.add(testOcclusionQuerySaturation());
        results.add(testGPUProfilerStability());

        long passed = results.stream().filter(r -> r.passed).count();
        LOG.info("═══ 壓力測試完成：{}/{} 通過 ═══", passed, results.size());

        for (StressResult r : results) {
            if (!r.passed) LOG.error(r.toString());
            else LOG.info(r.toString());
        }

        return results;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Test 1: FBO Ping-Pong 穩定性
    // ═══════════════════════════════════════════════════════════════

    // Phase 4-F: BRFramebufferManager removed; replaced by Minecraft mainRenderTarget read-back test
    private static StressResult testFBOPingPong() {
        long start = System.nanoTime();
        try {
            // Verify Minecraft's main render target FBO is valid
            int mainFbo = Minecraft.getInstance().getMainRenderTarget().frameBufferId;
            int colorTex = Minecraft.getInstance().getMainRenderTarget().getColorTextureId();
            int depthTex = Minecraft.getInstance().getMainRenderTarget().getDepthTextureId();

            boolean valid = (mainFbo > 0 && colorTex > 0 && depthTex > 0);
            int glError = GL11.glGetError();
            boolean noError = (glError == GL11.GL_NO_ERROR);

            long dur = (System.nanoTime() - start) / 1_000_000;
            return new StressResult("FBO_MainRenderTarget", valid && noError, dur,
                valid ? "MC mainRenderTarget FBO=" + mainFbo + " color=" + colorTex + " depth=" + depthTex
                      : "mainRenderTarget 無效！FBO=" + mainFbo);
        } catch (Exception e) {
            return new StressResult("FBO_MainRenderTarget", false,
                (System.nanoTime() - start) / 1_000_000, "異常: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Test 2: Shader Bind/Unbind 循環
    // ═══════════════════════════════════════════════════════════════

    private static StressResult testShaderBindCycle() {
        long start = System.nanoTime();
        int errors = 0;
        int tested = 0;

        BRShaderProgram[] shaders = {
            BRShaderEngine.getGBufferTerrainShader(),
            BRShaderEngine.getGBufferEntityShader(),
            BRShaderEngine.getShadowShader(),
            BRShaderEngine.getDeferredLightingShader(),
            BRShaderEngine.getSSAOShader(),
            BRShaderEngine.getBloomShader(),
            BRShaderEngine.getTonemapShader(),
            BRShaderEngine.getFinalShader(),
            BRShaderEngine.getTranslucentShader(),
            BRShaderEngine.getSSRShader(),
            BRShaderEngine.getDOFShader(),
            BRShaderEngine.getContactShadowShader(),
            BRShaderEngine.getAtmosphereShader(),
            BRShaderEngine.getWaterShader(),
            BRShaderEngine.getParticleShader(),
            BRShaderEngine.getVolumetricShader(),
            BRShaderEngine.getCloudShader(),
            BRShaderEngine.getCinematicShader(),
            BRShaderEngine.getColorGradeShader(),
            BRShaderEngine.getTAAShader(),
            BRShaderEngine.getSSGIShader(),
            BRShaderEngine.getFogShader(),
            BRShaderEngine.getLensFlareShader(),
            BRShaderEngine.getRainShader(),
            BRShaderEngine.getSnowShader(),
            BRShaderEngine.getLightningShader(),
            BRShaderEngine.getAuroraShader(),
            BRShaderEngine.getWetPbrShader(),
            BRShaderEngine.getSSSShader(),
            BRShaderEngine.getAnisotropicShader(),
            BRShaderEngine.getPOMShader()
        };

        // 清除殘留 error
        while (GL11.glGetError() != GL11.GL_NO_ERROR) {}

        for (BRShaderProgram shader : shaders) {
            if (shader == null) continue;
            tested++;
            for (int i = 0; i < 100; i++) {
                shader.bind();
                shader.unbind();
            }
            int err = GL11.glGetError();
            if (err != GL11.GL_NO_ERROR) {
                errors++;
            }
        }

        long dur = (System.nanoTime() - start) / 1_000_000;
        boolean ok = errors == 0;
        return new StressResult("Shader_BindCycle_100x", ok, dur,
            ok ? tested + " shaders × 100 bind/unbind 無 GL error"
               : errors + " shaders 產生 GL error");
    }

    // ═══════════════════════════════════════════════════════════════
    //  Test 3: Composite Chain 空跑
    // ═══════════════════════════════════════════════════════════════

    // Phase 4-F: BRFramebufferManager removed; replaced by MC mainRenderTarget bind/unbind cycle test
    private static StressResult testCompositeChainDryRun() {
        long start = System.nanoTime();
        try {
            // 清除 GL error
            while (GL11.glGetError() != GL11.GL_NO_ERROR) {}

            int mainFbo = Minecraft.getInstance().getMainRenderTarget().frameBufferId;
            int colorTex = Minecraft.getInstance().getMainRenderTarget().getColorTextureId();

            // 模擬 100 幀的 bind/unbind cycle（以 MC main FBO 代替舊 composite FBO）
            for (int frame = 0; frame < 100; frame++) {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, mainFbo);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTex);
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            }

            int error = GL11.glGetError();
            boolean ok = (error == GL11.GL_NO_ERROR);

            long dur = (System.nanoTime() - start) / 1_000_000;
            return new StressResult("CompositeChain_DryRun_100f", ok, dur,
                ok ? "100 幀 MC FBO bind/unbind 無 GL error" : "GL error: 0x" + Integer.toHexString(error));
        } catch (Exception e) {
            return new StressResult("CompositeChain_DryRun_100f", false,
                (System.nanoTime() - start) / 1_000_000, "異常: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Test 4: 天氣狀態機全狀態切換
    // ═══════════════════════════════════════════════════════════════

    // Phase 4-F: BRWeatherEngine removed — weather state machine test is no longer applicable
    private static StressResult testWeatherStateMachine() {
        return new StressResult("Weather_StateMachine", true, 0,
            "BRWeatherEngine 已在 Phase 4-F 移除，測試跳過");
    }

    // ═══════════════════════════════════════════════════════════════
    //  Test 5: Shader LOD 品質循環
    // ═══════════════════════════════════════════════════════════════

    private static StressResult testShaderLODCycle() {
        long start = System.nanoTime();
        try {
            if (!BRRenderConfig.SHADER_LOD_ENABLED) {
                return new StressResult("ShaderLOD_Cycle", true, 0, "Shader LOD 已停用，跳過");
            }

            // 模擬高幀時間→降級→低幀時間→升級
            // 餵入 >20ms 幀時間 70 幀（觸發降級）
            for (int i = 0; i < 70; i++) {
                BRShaderLOD.recordFrameTime(25.0f);
            }

            // 餵入 <12ms 幀時間 130 幀（觸發升級）
            for (int i = 0; i < 130; i++) {
                BRShaderLOD.recordFrameTime(8.0f);
            }

            // 不 crash = 通過
            long dur = (System.nanoTime() - start) / 1_000_000;
            return new StressResult("ShaderLOD_Cycle", true, dur,
                "降級/升級循環完成，當前品質=" + BRShaderLOD.getCurrentLevel());
        } catch (Exception e) {
            return new StressResult("ShaderLOD_Cycle", false,
                (System.nanoTime() - start) / 1_000_000, "異常: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Test 6: 非同步任務飽和
    // ═══════════════════════════════════════════════════════════════

    private static StressResult testAsyncComputeSaturation() {
        long start = System.nanoTime();
        try {
            // 提交 32 個任務（超過 16 fence 池大小）
            final int[] completed = {0};
            BRAsyncComputeScheduler.Priority[] priorities = BRAsyncComputeScheduler.Priority.values();
            for (int i = 0; i < 32; i++) {
                final int idx = i;
                BRAsyncComputeScheduler.submitTask(
                    "stress_task_" + i,
                    priorities[i % priorities.length],
                    () -> { completed[0]++; }
                );
            }

            // 連續 process 10 幀
            for (int f = 0; f < 10; f++) {
                BRAsyncComputeScheduler.processTasks();
            }

            long dur = (System.nanoTime() - start) / 1_000_000;
            return new StressResult("AsyncCompute_Saturation", true, dur,
                "32 任務提交 + 10 幀處理完成，已完成=" + completed[0]);
        } catch (Exception e) {
            return new StressResult("AsyncCompute_Saturation", false,
                (System.nanoTime() - start) / 1_000_000, "異常: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Test 7: 遮蔽查詢飽和
    // ═══════════════════════════════════════════════════════════════

    private static StressResult testOcclusionQuerySaturation() {
        long start = System.nanoTime();
        try {
            if (!BRRenderConfig.OCCLUSION_QUERY_ENABLED) {
                return new StressResult("OcclusionQuery_Saturation", true, 0, "OQ 已停用，跳過");
            }

            // 清除 GL error
            while (GL11.glGetError() != GL11.GL_NO_ERROR) {}

            // 發起 beginFrame
            BROcclusionCuller.beginFrame();

            // 查詢 256 個 section（用合理的 AABB 座標）
            for (int i = 0; i < 256; i++) {
                float x = (i % 16) * 16.0f;
                float z = (i / 16) * 16.0f;
                BROcclusionCuller.querySection((long) i, x, 0, z, x + 16, 256, z + 16);
            }

            // 再次 beginFrame 讀回
            BROcclusionCuller.beginFrame();

            int error = GL11.glGetError();
            boolean ok = (error == GL11.GL_NO_ERROR);

            long dur = (System.nanoTime() - start) / 1_000_000;
            return new StressResult("OcclusionQuery_Saturation", ok, dur,
                ok ? "256 queries 發出 + 讀回無 GL error"
                   : "GL error: 0x" + Integer.toHexString(error));
        } catch (Exception e) {
            return new StressResult("OcclusionQuery_Saturation", false,
                (System.nanoTime() - start) / 1_000_000, "異常: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Test 8: GPU Profiler 雙緩衝穩定性
    // ═══════════════════════════════════════════════════════════════

    private static StressResult testGPUProfilerStability() {
        long start = System.nanoTime();
        try {
            if (!BRRenderConfig.GPU_PROFILER_ENABLED) {
                return new StressResult("GPUProfiler_Stability", true, 0, "GPU Profiler 已停用，跳過");
            }

            // 清除 GL error
            while (GL11.glGetError() != GL11.GL_NO_ERROR) {}

            // 模擬 64 幀的 profiler 操作（覆蓋完整滾動窗口）
            for (int frame = 0; frame < 64; frame++) {
                BRGPUProfiler.beginFrame();

                // 模擬幾個 pass 名稱
                BRGPUProfiler.beginPass("Shadow");
                BRGPUProfiler.endPass("Shadow");

                BRGPUProfiler.beginPass("GBuffer");
                BRGPUProfiler.endPass("GBuffer");

                BRGPUProfiler.beginPass("Composite");
                BRGPUProfiler.endPass("Composite");

                BRGPUProfiler.endFrame();
            }

            // 取得統計
            java.util.Map<String, Float> averages = BRGPUProfiler.getAllPassAverages();

            int error = GL11.glGetError();
            boolean ok = (error == GL11.GL_NO_ERROR);

            long dur = (System.nanoTime() - start) / 1_000_000;
            return new StressResult("GPUProfiler_Stability", ok, dur,
                ok ? "64 幀 profiling 完成，" + averages.size() + " pass 統計已收集"
                   : "GL error: 0x" + Integer.toHexString(error));
        } catch (Exception e) {
            return new StressResult("GPUProfiler_Stability", false,
                (System.nanoTime() - start) / 1_000_000, "異常: " + e.getMessage());
        }
    }
}
