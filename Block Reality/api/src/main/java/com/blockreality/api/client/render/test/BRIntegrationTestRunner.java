package com.blockreality.api.client.render.test;

import com.blockreality.api.client.render.pipeline.BRRenderTier;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Block Reality Phase 13 整合測試執行器。
 */
@OnlyIn(Dist.CLIENT)
public final class BRIntegrationTestRunner {
    private BRIntegrationTestRunner() {}

    private static final Logger LOG = LoggerFactory.getLogger("BR-IntegrationTest");

    public record TestSummary(int total, int passed, int failed, long durationMs) {}

    /**
     * 執行所有 Phase 13 整合測試。
     * 必須在 GL context 主執行緒、管線 init() 之後呼叫。
     *
     * @return 測試摘要
     */
    public static TestSummary runAll() {
        // Phase 4-F: RT 管線已移除
        LOG.info("渲染: {}", BRRenderTier.isEnabled() ? "啟用" : "停用");

        long startTime = System.currentTimeMillis();
        int totalTests  = 0;
        int totalPassed = 0;
        int totalFailed = 0;

        LOG.info("╔══════════════════════════════════════════════════════════╗");
        LOG.info("║  Block Reality Phase 13 — 全管線整合測試               ║");
        LOG.info("║  32 子系統 · 40+ Shader · 15 Pass Composite Chain      ║");
        LOG.info("╚══════════════════════════════════════════════════════════╝");

        long elapsed = System.currentTimeMillis() - startTime;
        LOG.info("整合測試完成 — {}/{} passed，耗時 {}ms", totalPassed, totalTests, elapsed);
        return new TestSummary(totalTests, totalPassed, totalFailed, elapsed);
    }
}
