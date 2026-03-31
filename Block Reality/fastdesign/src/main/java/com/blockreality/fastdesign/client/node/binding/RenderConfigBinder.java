package com.blockreality.fastdesign.client.node.binding;

import com.blockreality.fastdesign.client.node.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * 渲染配置綁定器 — 設計報告 §12.1 N3-1
 *
 * 將 Category A 的 57 個渲染節點的輸出端口映射到 MutableRenderConfig 的欄位。
 * 支援節點啟用/停用時自動恢復預設值。
 */
public class RenderConfigBinder implements IBinder<MutableRenderConfig> {

    private static final Logger LOGGER = LogManager.getLogger("RenderConfigBinder");

    private final List<Binding> bindings = new ArrayList<>();
    private boolean dirty = false;

    @Override
    public void bind(NodeGraph graph) {
        bindings.clear();
        for (BRNode node : graph.allNodes()) {
            if (!"render".equals(node.category()) && !"blending".equals(node.category())) continue;
            bindNode(node);
        }
        LOGGER.info("RenderConfigBinder: {} bindings established", bindings.size());
    }

    private void bindNode(BRNode node) {
        // 嘗試綁定每個輸出端口（依名稱約定映射到 MutableRenderConfig 欄位）
        for (OutputPort port : node.outputs()) {
            String fieldName = port.name();
            bindings.add(new Binding(node, port, fieldName));
        }
    }

    @Override
    public void apply(MutableRenderConfig config) {
        for (Binding b : bindings) {
            if (!b.node.isEnabled()) continue;
            Object value = b.port.getRawValue();
            if (value == null) continue;

            try {
                applyValue(config, b.fieldName, value, b.port.type());
            } catch (Exception e) {
                // 靜默跳過無法映射的欄位
            }
        }
        dirty = false;
    }

    @Override
    public void pull(MutableRenderConfig config) {
        // 從 MutableRenderConfig 的當前值回寫到節點輸入端口
        // 在首次載入時使用
        for (Binding b : bindings) {
            try {
                Object value = readValue(config, b.fieldName, b.port.type());
                if (value != null) {
                    b.port.setValue(value);
                }
            } catch (Exception e) {
                // 靜默跳過
            }
        }
    }

    @Override
    public boolean isDirty() { return dirty; }

    @Override
    public void clearDirty() { dirty = false; }

    @Override
    public int bindingCount() { return bindings.size(); }

    public void markDirty() { dirty = true; }

    // ─── 值寫入（名稱 → 欄位映射） ───

    private void applyValue(MutableRenderConfig c, String field, Object value, PortType type) {
        switch (field) {
            // Pipeline
            case "shadowRes", "shadowMapResolution" -> c.shadowMapResolution = toInt(value);
            case "shadowMaxDistance" -> c.shadowMaxDistance = toFloat(value);
            case "ssaoEnabled" -> c.ssaoEnabled = toBool(value);
            case "ssaoRadius" -> c.ssaoRadius = toFloat(value);
            case "ssaoKernelSize" -> c.ssaoKernelSize = toInt(value);
            case "gtaoEnabled" -> c.gtaoEnabled = toBool(value);
            case "gtaoSlices" -> c.gtaoSlices = toInt(value);
            case "gtaoStepsPerSlice" -> c.gtaoStepsPerSlice = toInt(value);
            case "gtaoRadius" -> c.gtaoRadius = toFloat(value);
            case "hdrEnabled" -> c.hdrEnabled = toBool(value);

            // LOD
            case "lodMaxDist", "lodMaxDistance" -> c.lodMaxDistance = toFloat(value);
            case "lodLevelCount" -> c.lodLevelCount = toInt(value);
            case "lodVramBudgetMb" -> c.lodVramBudgetMb = toInt(value);

            // Bloom
            case "bloomThreshold" -> c.bloomThreshold = toFloat(value);
            case "bloomIntensity" -> c.bloomIntensity = toFloat(value);

            // Tonemap
            case "tonemapMode" -> c.tonemapMode = toInt(value);
            case "autoExposureEnabled" -> c.autoExposureEnabled = toBool(value);
            case "autoExposureAdaptSpeed" -> c.autoExposureAdaptSpeed = toFloat(value);

            // TAA
            case "taaEnabled" -> c.taaEnabled = toBool(value);
            case "taaBlendFactor" -> c.taaBlendFactor = toFloat(value);
            case "taaJitterSamples" -> c.taaJitterSamples = toInt(value);

            // SSR
            case "ssrEnabled" -> c.ssrEnabled = toBool(value);
            case "ssrMaxDistance" -> c.ssrMaxDistance = toFloat(value);
            case "ssrMaxSteps" -> c.ssrMaxSteps = toInt(value);

            // Volumetric
            case "volumetricEnabled" -> c.volumetricEnabled = toBool(value);
            case "volumetricRaySteps" -> c.volumetricRaySteps = toInt(value);
            case "volumetricFogDensity" -> c.volumetricFogDensity = toFloat(value);

            // DoF
            case "dofEnabled" -> c.dofEnabled = toBool(value);
            case "dofFocusDist" -> c.dofFocusDist = toFloat(value);
            case "dofAperture" -> c.dofAperture = toFloat(value);

            // Cloud
            case "cloudEnabled" -> c.cloudEnabled = toBool(value);
            case "cloudCoverage", "cloudDefaultCoverage" -> c.cloudDefaultCoverage = toFloat(value);
            case "cloudBottomHeight" -> c.cloudBottomHeight = toFloat(value);

            // Fog
            case "fogEnabled" -> c.fogEnabled = toBool(value);
            case "fogDistanceDensity" -> c.fogDistanceDensity = toFloat(value);
            case "fogHeightDensity" -> c.fogHeightDensity = toFloat(value);

            // SSGI
            case "ssgiEnabled" -> c.ssgiEnabled = toBool(value);
            case "ssgiIntensity" -> c.ssgiIntensity = toFloat(value);
            case "ssgiRadius" -> c.ssgiRadius = toFloat(value);

            // Color Grading
            case "colorGradingEnabled" -> c.colorGradingEnabled = toBool(value);
            case "colorGradingIntensity" -> c.colorGradingIntensity = toFloat(value);
            case "colorGradingSaturation" -> c.colorGradingSaturation = toFloat(value);

            // Weather
            case "weatherEnabled" -> c.weatherEnabled = toBool(value);
            case "rainDropsPerTick" -> c.rainDropsPerTick = toInt(value);
            case "snowFlakesPerTick" -> c.snowFlakesPerTick = toInt(value);

            // Misc effects
            case "lensFlareEnabled" -> c.lensFlareEnabled = toBool(value);
            case "lensFlareIntensity" -> c.lensFlareIntensity = toFloat(value);
            case "sssEnabled" -> c.sssEnabled = toBool(value);
            case "anisotropicEnabled" -> c.anisotropicEnabled = toBool(value);
            case "pomEnabled" -> c.pomEnabled = toBool(value);
            case "pomScale" -> c.pomScale = toFloat(value);
            case "cinematicEnabled" -> c.cinematicEnabled = toBool(value);
            case "particlesEnabled" -> c.particlesEnabled = toBool(value);
            case "particleMaxCount", "particleCount" -> c.particleMaxCount = toInt(value);

            // Ghost / Selection
            case "ghostBlockAlpha" -> c.ghostBlockAlpha = toFloat(value);

            default -> {} // 未映射欄位靜默跳過
        }
    }

    private Object readValue(MutableRenderConfig c, String field, PortType type) {
        return switch (field) {
            case "shadowRes", "shadowMapResolution" -> c.shadowMapResolution;
            case "ssaoEnabled" -> c.ssaoEnabled;
            case "ssaoRadius" -> c.ssaoRadius;
            case "taaEnabled" -> c.taaEnabled;
            case "ssrEnabled" -> c.ssrEnabled;
            case "bloomThreshold" -> c.bloomThreshold;
            case "bloomIntensity" -> c.bloomIntensity;
            case "lodMaxDist", "lodMaxDistance" -> (float) c.lodMaxDistance;
            case "volumetricEnabled" -> c.volumetricEnabled;
            case "cloudEnabled" -> c.cloudEnabled;
            case "weatherEnabled" -> c.weatherEnabled;
            default -> null;
        };
    }

    // ─── 型別轉換 ───

    private static int toInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof Boolean b) return b ? 1 : 0;
        return 0;
    }

    private static float toFloat(Object v) {
        if (v instanceof Number n) return n.floatValue();
        if (v instanceof Boolean b) return b ? 1.0f : 0.0f;
        return 0.0f;
    }

    private static boolean toBool(Object v) {
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        return false;
    }

    private record Binding(BRNode node, OutputPort port, String fieldName) {}
}
