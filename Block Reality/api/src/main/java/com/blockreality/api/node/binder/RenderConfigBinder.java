package com.blockreality.api.node.binder;

import com.blockreality.api.client.render.BRRenderSettings;
import com.blockreality.api.node.BRNode;
import com.blockreality.api.node.NodeGraph;
import com.blockreality.api.node.NodePort;
import com.blockreality.api.node.nodes.render.EffectToggleNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Render Config Binder — 節點圖輸出 → BRRenderSettings 映射。
 *
 * TODO [API-BOUNDARY]: 此 binder 屬於渲染互動層（將節點輸出推送到渲染設定），
 *   不屬於 API 計算層。API 只應提供 binder 介面/抽象類。
 *   此實作應移至建築師模組 (fastdesign/architect)。
 *   遷移步驟：
 *     1. 在 API 保留 binder 介面 (IBinder<T>)
 *     2. 將此實作移至 fastdesign.client.node.binding.RenderConfigBinder
 *     3. 在 fastdesign 初始化時註冊 binder
 *
 * 每次節點圖評估後，此 binder 讀取渲染節點的輸出端口值，
 * 並推送到 BRRenderSettings 對應的運行時設定。
 *
 * 映射規則：
 *   QualityPreset.ssaoEnabled (BOOL) → BRRenderSettings.setEffect("ssao", value)
 *   SSAO_GTAO.kernelSize (INT)       → BRRenderSettings.setSSAOSamples(value)
 *   ShadowConfig.resolution (INT)     → BRRenderSettings.setShadowResolution(value)
 *   TierSelector.tier (ENUM)          → BRRenderTier.setTier(value)
 */
public final class RenderConfigBinder {

    private RenderConfigBinder() {}

    private static final Logger LOG = LoggerFactory.getLogger("BR-NodeBinder");

    /** nodeType → effect name mapping (e.g. "EffectToggleNode_ssao" → "ssao") */
    private static final Map<String, String> nodeEffectMap = new ConcurrentHashMap<>();

    private static boolean initialized = false;

    /**
     * 初始化 binder — 註冊已知節點類型到效果名稱的映射。
     */
    public static void init() {
        if (initialized) return;

        // Register default effect mappings
        registerMapping("ssao", "ssao");
        registerMapping("ssr", "ssr");
        registerMapping("ssgi", "ssgi");
        registerMapping("taa", "taa");
        registerMapping("bloom", "bloom");
        registerMapping("dof", "dof");
        registerMapping("volumetric", "volumetric");
        registerMapping("contact_shadow", "contact_shadow");
        registerMapping("motion_blur", "motion_blur");
        registerMapping("cloud", "cloud");
        registerMapping("weather", "weather");
        registerMapping("atmosphere", "atmosphere");
        registerMapping("water", "water");
        registerMapping("fog", "fog");
        registerMapping("sss", "sss");
        registerMapping("anisotropic", "anisotropic");
        registerMapping("pom", "pom");
        registerMapping("vct", "vct");
        registerMapping("rt_shadow", "rt_shadow");

        initialized = true;
        LOG.info("[NodeBinder] 渲染設定綁定器初始化完成 — {} 組映射", nodeEffectMap.size());
    }

    /**
     * 清理 binder 狀態。
     */
    public static void cleanup() {
        nodeEffectMap.clear();
        initialized = false;
        LOG.info("[NodeBinder] 渲染設定綁定器已清理");
    }

    /**
     * 在節點圖評估後呼叫。掃描所有渲染類節點，推送輸出值到 BRRenderSettings。
     */
    public static void pushToSettings(NodeGraph graph) {
        if (!initialized) {
            LOG.warn("[NodeBinder] pushToSettings 呼叫但 binder 尚未初始化");
            return;
        }

        for (BRNode node : graph.getAllNodes()) {
            if (!"render".equals(node.getCategory())) continue;
            if (!node.isEnabled()) continue;

            // Handle EffectToggleNode instances — push enabled state and parameters
            if (node instanceof EffectToggleNode effectNode) {
                String effectName = effectNode.getEffectName();
                pushEffectToggleOutputs(effectNode, effectName);
                continue;
            }

            // Handle generic render nodes with standard output port names
            pushGenericRenderOutputs(node);
        }
    }

    /**
     * Push EffectToggleNode outputs to BRRenderSettings.
     */
    private static void pushEffectToggleOutputs(EffectToggleNode node, String effectName) {
        // "enabled" output → effect toggle
        NodePort enabledPort = node.getOutput("enabled");
        if (enabledPort != null && enabledPort.getValue() != null) {
            boolean enabled = (boolean) enabledPort.getValue();
            BRRenderSettings.setEffect(effectName, enabled);
        }

        // "samples" output → SSAO samples (if present)
        NodePort samplesPort = node.getOutput("samples");
        if (samplesPort != null && samplesPort.getValue() != null) {
            int samples = ((Number) samplesPort.getValue()).intValue();
            if ("ssao".equals(effectName)) {
                BRRenderSettings.setSSAOSamples(samples);
            }
        }

        // "resolution" output → shadow resolution (if present)
        NodePort resPort = node.getOutput("resolution");
        if (resPort != null && resPort.getValue() != null) {
            int resolution = ((Number) resPort.getValue()).intValue();
            BRRenderSettings.setShadowResolution(resolution);
        }

        // "intensity" output → could be used for per-effect intensity tuning
        // Currently BRRenderSettings only supports on/off; reserved for future use
    }

    /**
     * Push generic render node outputs (shadowRes, ssaoSamples, etc.) to BRRenderSettings.
     */
    private static void pushGenericRenderOutputs(BRNode node) {
        // "shadowRes" output → shadow resolution
        NodePort shadowResPort = node.getOutput("shadowRes");
        if (shadowResPort != null && shadowResPort.getValue() != null) {
            int res = ((Number) shadowResPort.getValue()).intValue();
            BRRenderSettings.setShadowResolution(res);
        }

        // "ssaoSamples" output → SSAO samples
        NodePort ssaoSamplesPort = node.getOutput("ssaoSamples");
        if (ssaoSamplesPort != null && ssaoSamplesPort.getValue() != null) {
            int samples = ((Number) ssaoSamplesPort.getValue()).intValue();
            BRRenderSettings.setSSAOSamples(samples);
        }

        // Boolean effect outputs: map output port name to effect name
        String[] boolEffectOutputs = {
            "ssaoEnabled", "ssrEnabled", "ssgiEnabled", "taaEnabled",
            "bloomEnabled", "dofEnabled", "volumetricEnabled",
            "contactShadowEnabled", "motionBlurEnabled",
            "cloudEnabled", "weatherEnabled", "waterEnabled",
            "fogEnabled", "atmosphereEnabled",
            "sssEnabled", "anisotropicEnabled", "pomEnabled",
            "vctEnabled", "rtShadowEnabled"
        };

        for (String outputName : boolEffectOutputs) {
            NodePort port = node.getOutput(outputName);
            if (port != null && port.getValue() != null) {
                boolean value = (boolean) port.getValue();
                String effectName = outputNameToEffectName(outputName);
                BRRenderSettings.setEffect(effectName, value);
            }
        }
    }

    /**
     * Convert output port name (e.g. "ssaoEnabled") to BRRenderSettings effect name (e.g. "ssao").
     */
    private static String outputNameToEffectName(String outputName) {
        // Strip "Enabled" suffix and convert camelCase to snake_case
        String base = outputName.replace("Enabled", "");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                sb.append('_');
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /**
     * 反向：從 BRRenderSettings 拉取值填入節點輸入。
     * 初始化節點圖時呼叫，確保節點反映當前設定。
     */
    public static void pullFromSettings(NodeGraph graph) {
        if (!initialized) {
            LOG.warn("[NodeBinder] pullFromSettings 呼叫但 binder 尚未初始化");
            return;
        }

        for (BRNode node : graph.getAllNodes()) {
            if (!"render".equals(node.getCategory())) continue;

            // Handle EffectToggleNode instances — pull enabled state
            if (node instanceof EffectToggleNode effectNode) {
                String effectName = effectNode.getEffectName();
                boolean enabled = BRRenderSettings.isEffectEnabled(effectName);
                NodePort enabledInput = node.getInput("enabled");
                if (enabledInput != null) {
                    enabledInput.setValue(enabled);
                }
                continue;
            }

            // Handle QualityPresetNode — pull shadow resolution into preset detection
            NodePort presetInput = node.getInput("preset");
            if (presetInput != null) {
                // Detect current preset from BRRenderSettings state
                int detectedPreset = detectCurrentPreset();
                presetInput.setValue(detectedPreset);
            }
        }
    }

    /**
     * Detect which quality preset best matches the current BRRenderSettings state.
     *
     * @return preset index: 0=Potato, 1=Low, 2=Medium, 3=High, 4=Ultra, 5=Custom
     */
    private static int detectCurrentPreset() {
        int shadowRes = BRRenderSettings.getShadowResolution();
        boolean ssao = BRRenderSettings.isSSAOEnabled();
        boolean ssr = BRRenderSettings.isSSREnabled();
        boolean volumetric = BRRenderSettings.isVolumetricEnabled();

        if (shadowRes >= 4096 && ssao && ssr && volumetric) return 4; // Ultra
        if (shadowRes >= 2048 && ssao && ssr && volumetric) return 3; // High
        if (shadowRes >= 2048 && ssao && !ssr) return 2;              // Medium
        if (shadowRes >= 1024 && ssao && !ssr) return 1;              // Low
        if (shadowRes <= 512 && !ssao) return 0;                       // Potato
        return 5; // Custom
    }

    /**
     * 註冊節點類型到效果名稱的映射。
     *
     * @param nodeType   the node type identifier (e.g. "ssao", "ssr")
     * @param effectName the BRRenderSettings effect name (e.g. "ssao", "ssr")
     */
    public static void registerMapping(String nodeType, String effectName) {
        nodeEffectMap.put(nodeType, effectName);
    }
}
