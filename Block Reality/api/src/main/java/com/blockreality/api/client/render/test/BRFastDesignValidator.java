package com.blockreality.api.client.render.test;

import com.blockreality.api.spi.ModuleRegistry;
import com.blockreality.api.spi.ICommandProvider;
import com.blockreality.api.client.render.pipeline.BRRenderPipeline;
import com.blockreality.api.client.render.shader.BRShaderEngine;
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
 *
 * 驗證項目：
 *   1. SPI 介面可用性 — 7 個 SPI 介面全部可解析
 *   2. ModuleRegistry 單例完整性 — getInstance() 非 null
 *   3. SPI 註冊/反註冊循環 — 驗證 register + unregister 不會破壞狀態
 *   4. IRenderLayerProvider 渲染掛接 — 確認管線支援外部 render layer
 *   5. BatchBlockPlacer 可實例化 — Fast Design 核心批次操作
 *   6. 選取引擎 / 藍圖預覽 / 快速放置器就緒 — Fast Design UI 基礎設施
 *   7. LoadPathChangedEvent 事件匯流排可訂閱 — Fast Design 載荷路徑視覺化
 *   8. Shader Engine 渲染 layer 支援 — GBuffer terrain/entity/translucent shader 完整
 *   9. Effect Renderer translucent 掛接 — 幽靈方塊/選框渲染路徑
 *  10. 全管線 enabled 狀態可切換 — Fast Design 可暫停/恢復管線
 */
@OnlyIn(Dist.CLIENT)
public final class BRFastDesignValidator {
    private BRFastDesignValidator() {}

    private static final Logger LOG = LoggerFactory.getLogger("BR-FastDesignValidator");

    /** 驗證結果 */
    public static final class FDValidationResult {
        public final String testName;
        public final boolean passed;
        public final String detail;

        public FDValidationResult(String testName, boolean passed, String detail) {
            this.testName = testName;
            this.passed = passed;
            this.detail = detail;
        }

        @Override
        public String toString() {
            return (passed ? "[PASS]" : "[FAIL]") + " [FastDesign] " + testName + " — " + detail;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  主驗證入口
    // ═══════════════════════════════════════════════════════════════

    public static List<FDValidationResult> runFullValidation() {
        List<FDValidationResult> results = new ArrayList<>();

        LOG.info("═══ Fast Design 接入驗證開始 ═══");

        // 1. SPI 介面可解析
        validateSPIInterfaces(results);

        // 2. ModuleRegistry 單例
        validateModuleRegistry(results);

        // 3. SPI 註冊/反註冊循環
        validateSPIRegistrationCycle(results);

        // 4. 渲染管線基礎設施
        validateRenderInfrastructure(results);

        // 5. BatchBlockPlacer
        validateBatchBlockPlacer(results);

        // 6. UI 子系統就緒
        validateUISubsystems(results);

        // 7. 事件匯流排
        validateEventBus(results);

        // 8. 管線可切換
        validatePipelineToggle(results);

        // 統計
        long passed = results.stream().filter(r -> r.passed).count();
        LOG.info("═══ Fast Design 驗證完成：{}/{} 通過 ═══", passed, results.size());

        for (FDValidationResult r : results) {
            if (!r.passed) LOG.error(r.toString());
            else LOG.info(r.toString());
        }

        return results;
    }

    // ═══════════════════════════════════════════════════════════════
    //  1. SPI 介面可解析
    // ═══════════════════════════════════════════════════════════════

    private static void validateSPIInterfaces(List<FDValidationResult> results) {
        String[] spiClasses = {
            "com.blockreality.api.spi.ICommandProvider",
            "com.blockreality.api.spi.IRenderLayerProvider",
            "com.blockreality.api.spi.IBlockTypeExtension",
            "com.blockreality.api.spi.ICableManager",
            "com.blockreality.api.spi.ICuringManager",
            "com.blockreality.api.spi.ILoadPathManager",
            "com.blockreality.api.spi.IMaterialRegistry",
            "com.blockreality.api.spi.IFusionDetector",
            "com.blockreality.api.spi.BatchBlockPlacer"
        };

        for (String className : spiClasses) {
            try {
                Class<?> clazz = Class.forName(className);
                String simpleName = clazz.getSimpleName();
                boolean isInterface = clazz.isInterface();
                results.add(new FDValidationResult("SPI_" + simpleName, true,
                    "介面可解析" + (isInterface ? "（interface）" : "（class）")));
            } catch (ClassNotFoundException e) {
                String simpleName = className.substring(className.lastIndexOf('.') + 1);
                results.add(new FDValidationResult("SPI_" + simpleName, false,
                    "介面無法解析: " + e.getMessage()));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  2. ModuleRegistry 單例
    // ═══════════════════════════════════════════════════════════════

    private static void validateModuleRegistry(List<FDValidationResult> results) {
        ModuleRegistry registry = ModuleRegistry.getInstance();
        boolean ok = registry != null;
        results.add(new FDValidationResult("ModuleRegistry_Singleton", ok,
            ok ? "單例可取得" : "getInstance() 回傳 null！"));

        if (ok) {
            // 檢查基本功能
            List<ICommandProvider> cmds = ModuleRegistry.getCommandProviders();
            results.add(new FDValidationResult("ModuleRegistry_CommandProviders", true,
                "已註冊 " + cmds.size() + " 個 CommandProvider"));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  3. SPI 註冊/反註冊循環
    // ═══════════════════════════════════════════════════════════════

    private static void validateSPIRegistrationCycle(List<FDValidationResult> results) {
        try {
            // 透過反射建立測試用 CommandProvider（避免直接引入 Brigadier 依賴）
            // 驗證 register/unregister 循環不破壞內部狀態
            Class<?> registryClass = Class.forName("com.blockreality.api.spi.ModuleRegistry");
            java.lang.reflect.Method getProviders = registryClass.getMethod("getCommandProviders");

            int beforeCount = ((java.util.List<?>) getProviders.invoke(null)).size();

            // 使用動態代理建立 ICommandProvider
            Class<?> providerInterface = Class.forName("com.blockreality.api.spi.ICommandProvider");
            Object testProvider = java.lang.reflect.Proxy.newProxyInstance(
                providerInterface.getClassLoader(),
                new Class[]{providerInterface},
                (proxy, method, args) -> {
                    if ("getModuleId".equals(method.getName())) return "__test_fd_validator__";
                    if ("registerCommands".equals(method.getName())) return null;
                    return null;
                }
            );

            java.lang.reflect.Method register = registryClass.getMethod("registerCommandProvider", providerInterface);
            java.lang.reflect.Method unregister = registryClass.getMethod("unregisterCommandProvider", providerInterface);

            register.invoke(null, testProvider);
            int afterRegister = ((java.util.List<?>) getProviders.invoke(null)).size();

            unregister.invoke(null, testProvider);
            int afterUnregister = ((java.util.List<?>) getProviders.invoke(null)).size();

            boolean registerOk = afterRegister == beforeCount + 1;
            boolean unregisterOk = afterUnregister == beforeCount;

            results.add(new FDValidationResult("SPI_RegisterCycle", registerOk && unregisterOk,
                registerOk && unregisterOk
                    ? "register(+1)/unregister(-1) 循環正常"
                    : "計數異常: before=" + beforeCount + " afterReg=" + afterRegister + " afterUnreg=" + afterUnregister));
        } catch (Exception e) {
            results.add(new FDValidationResult("SPI_RegisterCycle", false,
                "異常: " + e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  4. 渲染管線基礎設施
    // ═══════════════════════════════════════════════════════════════

    private static void validateRenderInfrastructure(List<FDValidationResult> results) {
        // GBuffer terrain shader（Fast Design 方塊渲染用）
        boolean terrainShader = BRShaderEngine.getGBufferTerrainShader() != null;
        results.add(new FDValidationResult("Render_GBufferTerrain", terrainShader,
            terrainShader ? "GBuffer terrain shader 就緒" : "缺少 terrain shader"));

        // GBuffer entity shader（Fast Design 自訂方塊實體用）
        boolean entityShader = BRShaderEngine.getGBufferEntityShader() != null;
        results.add(new FDValidationResult("Render_GBufferEntity", entityShader,
            entityShader ? "GBuffer entity shader 就緒" : "缺少 entity shader"));

        // Translucent shader（幽靈方塊用）
        boolean transShader = BRShaderEngine.getTranslucentShader() != null;
        results.add(new FDValidationResult("Render_Translucent", transShader,
            transShader ? "Translucent shader 就緒" : "缺少 translucent shader"));

        // 管線初始化狀態
        boolean pipelineOk = BRRenderPipeline.isInitialized();
        results.add(new FDValidationResult("Render_Pipeline", pipelineOk,
            pipelineOk ? "渲染管線已初始化" : "管線未初始化！"));
    }

    // ═══════════════════════════════════════════════════════════════
    //  5. BatchBlockPlacer
    // ═══════════════════════════════════════════════════════════════

    private static void validateBatchBlockPlacer(List<FDValidationResult> results) {
        try {
            Class<?> clazz = Class.forName("com.blockreality.api.spi.BatchBlockPlacer");
            // 確認關鍵方法存在
            boolean hasPlaceMethod = false;
            boolean hasUndoMethod = false;
            for (java.lang.reflect.Method m : clazz.getDeclaredMethods()) {
                if (m.getName().contains("place") || m.getName().contains("Place")) hasPlaceMethod = true;
                if (m.getName().contains("undo") || m.getName().contains("Undo")) hasUndoMethod = true;
            }

            results.add(new FDValidationResult("BatchBlockPlacer_PlaceAPI", hasPlaceMethod,
                hasPlaceMethod ? "批次放置 API 存在" : "缺少 place 方法"));
            results.add(new FDValidationResult("BatchBlockPlacer_UndoAPI", hasUndoMethod,
                hasUndoMethod ? "Undo API 存在" : "缺少 undo 方法"));
        } catch (Exception e) {
            results.add(new FDValidationResult("BatchBlockPlacer", false,
                "類別無法載入: " + e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  6. UI 子系統就緒
    // ═══════════════════════════════════════════════════════════════

    private static void validateUISubsystems(List<FDValidationResult> results) {
        // 選取引擎（Fast Design 核心 — 區域選取、魔術棒等）
        try {
            Class<?> selClass = Class.forName("com.blockreality.api.client.render.ui.BRSelectionEngine");
            results.add(new FDValidationResult("UI_SelectionEngine", true,
                "選取引擎可用"));
        } catch (Exception e) {
            results.add(new FDValidationResult("UI_SelectionEngine", false,
                "選取引擎無法載入"));
        }

        // 藍圖預覽（Fast Design 幽靈方塊）
        try {
            Class<?> bpClass = Class.forName("com.blockreality.api.client.render.ui.BRBlueprintPreview");
            results.add(new FDValidationResult("UI_BlueprintPreview", true,
                "藍圖預覽可用"));
        } catch (Exception e) {
            results.add(new FDValidationResult("UI_BlueprintPreview", false,
                "藍圖預覽無法載入"));
        }

        // 快速放置器
        try {
            Class<?> qpClass = Class.forName("com.blockreality.api.client.render.ui.BRQuickPlacer");
            results.add(new FDValidationResult("UI_QuickPlacer", true,
                "快速放置器可用"));
        } catch (Exception e) {
            results.add(new FDValidationResult("UI_QuickPlacer", false,
                "快速放置器無法載入"));
        }

        // 輪盤選單
        try {
            Class<?> rmClass = Class.forName("com.blockreality.api.client.render.ui.BRRadialMenu");
            results.add(new FDValidationResult("UI_RadialMenu", true,
                "輪盤選單可用"));
        } catch (Exception e) {
            results.add(new FDValidationResult("UI_RadialMenu", false,
                "輪盤選單無法載入"));
        }

        // ToolMask
        try {
            Class<?> tmClass = Class.forName("com.blockreality.api.client.render.ui.BRToolMask");
            results.add(new FDValidationResult("UI_ToolMask", true,
                "工具遮罩可用"));
        } catch (Exception e) {
            results.add(new FDValidationResult("UI_ToolMask", false,
                "工具遮罩無法載入"));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  7. 事件匯流排
    // ═══════════════════════════════════════════════════════════════

    private static void validateEventBus(List<FDValidationResult> results) {
        // LoadPathChangedEvent（Fast Design 載荷路徑視覺化的核心事件）
        try {
            Class<?> eventClass = Class.forName("com.blockreality.api.event.LoadPathChangedEvent");
            boolean hasFields = eventClass.getDeclaredFields().length > 0
                || eventClass.getDeclaredMethods().length > 0;
            results.add(new FDValidationResult("Event_LoadPathChanged", hasFields,
                hasFields ? "事件類別有效" : "事件類別為空殼"));
        } catch (Exception e) {
            results.add(new FDValidationResult("Event_LoadPathChanged", false,
                "事件類別無法載入: " + e.getMessage()));
        }

        // StressUpdateEvent
        try {
            Class.forName("com.blockreality.api.event.StressUpdateEvent");
            results.add(new FDValidationResult("Event_StressUpdate", true,
                "應力更新事件可用"));
        } catch (Exception e) {
            results.add(new FDValidationResult("Event_StressUpdate", false,
                "事件無法載入"));
        }

        // FusionCompletedEvent
        try {
            Class.forName("com.blockreality.api.event.FusionCompletedEvent");
            results.add(new FDValidationResult("Event_FusionCompleted", true,
                "融合完成事件可用"));
        } catch (Exception e) {
            results.add(new FDValidationResult("Event_FusionCompleted", false,
                "事件無法載入"));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  8. 管線可切換
    // ═══════════════════════════════════════════════════════════════

    private static void validatePipelineToggle(List<FDValidationResult> results) {
        try {
            boolean wasBefore = BRRenderPipeline.isEnabled();

            // 關閉
            BRRenderPipeline.setEnabled(false);
            boolean disabled = !BRRenderPipeline.isEnabled();

            // 恢復
            BRRenderPipeline.setEnabled(true);
            boolean enabled = BRRenderPipeline.isEnabled();

            // 還原原始狀態
            BRRenderPipeline.setEnabled(wasBefore);

            boolean ok = disabled && enabled;
            results.add(new FDValidationResult("Pipeline_Toggle", ok,
                ok ? "setEnabled(false/true) 切換正常"
                   : "切換異常: disabled=" + disabled + " enabled=" + enabled));
        } catch (Exception e) {
            results.add(new FDValidationResult("Pipeline_Toggle", false,
                "異常: " + e.getMessage()));
        }
    }
}
