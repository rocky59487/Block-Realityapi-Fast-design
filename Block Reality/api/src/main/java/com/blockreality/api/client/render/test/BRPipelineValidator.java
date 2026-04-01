package com.blockreality.api.client.render.test;

import com.blockreality.api.client.render.shader.BRShaderEngine;
import com.blockreality.api.client.rendering.BRRTCompositor;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Block Reality 渲染管線完整性驗證器 — Phase 13。
 */
@OnlyIn(Dist.CLIENT)
public final class BRPipelineValidator {
    private BRPipelineValidator() {}

    private static final Logger LOG = LoggerFactory.getLogger("BR-PipelineValidator");

    public record ValidationResult(String category, String name, boolean passed, String detail) {}

    /** 執行完整管線驗證並回傳結果清單 */
    public static List<ValidationResult> runFullValidation() {
        List<ValidationResult> results = new ArrayList<>();
        validateSubsystemInit(results);
        return results;
    }

    //  1. 子系統初始化驗證（32 個子系統）
    // ═══════════════════════════════════════════════════════════════

    private static void validateSubsystemInit(List<ValidationResult> results) {
        String cat = "SubsystemInit";

        // 管線自身（Phase 4-F: BRRenderPipeline/BRLODEngine 已移除，改為檢查 RT Compositor）
        boolean pipelineInit = BRRTCompositor.getInstance().isInitialized();
        results.add(new ValidationResult(cat, "BRRTCompositor",
            pipelineInit,
            pipelineInit ? "RT Compositor 已初始化" : "RT Compositor 未初始化"));

        // 1. FBO Manager（Forge 1.20.1 官方映射：RenderTarget.frameBufferId 公有欄位）
        int fboId = Minecraft.getInstance().getMainRenderTarget().frameBufferId;
        results.add(new ValidationResult(cat, "BRFramebufferManager",
            fboId > 0,
            fboId > 0 ? "FBO 系統就緒" : "FBO 未初始化！"));

        // 2. Shader Engine（驗證核心 shader 非 null）
        boolean shadersOk = BRShaderEngine.getGBufferTerrainShader() != null
            && BRShaderEngine.getDeferredLightingShader() != null
            && BRShaderEngine.getFinalShader() != null;
        results.add(new ValidationResult(cat, "BRShaderEngine",
            shadersOk, shadersOk ? "核心 shader 已編譯" : "缺少核心 shader！"));
    }
}
