package com.blockreality.api.client.render.test;

import com.blockreality.api.spi.ModuleRegistry;
import com.blockreality.api.spi.ICommandProvider;
import com.blockreality.api.client.render.shader.BRShaderEngine;
import com.blockreality.api.client.rendering.BRRTCompositor;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Block Reality Fast Design 接入驗證器 — Phase 13。
 *
 * 驗證 Fast Design 模組能否透過 API SPI 正確接入渲染管線。
 */
@OnlyIn(Dist.CLIENT)
public final class BRFastDesignValidator {
    private BRFastDesignValidator() {}

    private static final Logger LOG = LoggerFactory.getLogger("BR-FastDesignValidator");

    /** 驗證結果 */
    public static final class FDValidationResult {
        public final String  testName;
        public final boolean passed;
        public final String  detail;

        public FDValidationResult(String testName, boolean passed, String detail) {
            this.testName = testName;
            this.passed   = passed;
            this.detail   = detail;
        }
    }

    public static List<FDValidationResult> validate() {
        List<FDValidationResult> results = new ArrayList<>();

        // 1. SPI ModuleRegistry
        boolean regOk = ModuleRegistry.getInstance() != null;
        results.add(new FDValidationResult("ModuleRegistry", regOk,
            regOk ? "ModuleRegistry 單例就緒" : "ModuleRegistry 未初始化！"));

        // 2. Shader Engine
        boolean shaderOk = BRShaderEngine.getGBufferTerrainShader() != null;
        results.add(new FDValidationResult("ShaderEngine", shaderOk,
            shaderOk ? "GBuffer shader 就緒" : "GBuffer shader 未編譯！"));

        // 3. RT Compositor（Phase 4-F: BRLODEngine 已移除，改為檢查 RT Compositor）
        boolean lodOk = BRRTCompositor.getInstance().isInitialized();
        results.add(new FDValidationResult("BRRTCompositor", lodOk,
            lodOk ? "RT Compositor 已初始化" : "RT Compositor 未初始化（非 TIER_3 時正常）"));

        int passed = (int) results.stream().filter(r -> r.passed).count();
        LOG.info("Fast Design 驗證完成：{}/{} 通過", passed, results.size());
        return results;
    }
}
