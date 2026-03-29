package com.blockreality.api.node.nodes.render;

import com.blockreality.api.node.BRNode;
import com.blockreality.api.node.NodePort;
import com.blockreality.api.node.PortType;

/**
 * 通用效果切換節點 — 對應 A3 的每個後處理效果。
 *
 * TODO [API-BOUNDARY]: 此節點屬於渲染互動層，不屬於 API 計算層。
 *   API 只負責提供 BRNode 基類和節點圖引擎。
 *   效果開關節點應移至建築師模組 (fastdesign/architect)。
 *   遷移時保留 BRNode 基類在 API，將此類移至 fastdesign.client.node.impl.render。
 *
 * 每個實例代表一個特定效果（SSAO、SSR、TAA、Bloom 等）。
 * 提供 enabled 開關 + 效果特定參數輸入 + 輸出。
 *
 * 使用靜態工廠方法建立特定效果的節點實例，每個工廠方法
 * 自動新增該效果專屬的參數端口與範圍限制。
 */
public class EffectToggleNode extends BRNode {

    private final String effectName; // "ssao", "ssr", "taa" etc.

    public EffectToggleNode(String effectName, String displayName) {
        super(displayName, "render", 0x2196F3);
        this.effectName = effectName;

        // Common input
        addInput("enabled", PortType.BOOL, true);

        // Common output
        addOutput("enabled", PortType.BOOL);
        addOutput("effectName", PortType.ENUM);
    }

    // ═══════════════════════════════════════════════════════
    //  靜態工廠方法 — 各效果專屬配置
    // ═══════════════════════════════════════════════════════

    /** SSAO 環境遮蔽 — Screen-Space Ambient Occlusion (GTAO variant) */
    public static EffectToggleNode createSSAO() {
        EffectToggleNode n = new EffectToggleNode("ssao", "SSAO 環境遮蔽");
        n.addInput("kernelSize", PortType.INT, 32).setRange(4, 128);
        n.addInput("radius", PortType.FLOAT, 0.5f).setRange(0.1f, 2.0f);
        n.addInput("intensity", PortType.FLOAT, 1.0f).setRange(0.0f, 3.0f);
        n.addOutput("samples", PortType.INT);
        return n;
    }

    /** SSR 螢幕空間反射 — Screen-Space Reflections */
    public static EffectToggleNode createSSR() {
        EffectToggleNode n = new EffectToggleNode("ssr", "SSR 螢幕空間反射");
        n.addInput("maxDistance", PortType.FLOAT, 50.0f).setRange(5.0f, 200.0f);
        n.addInput("raySteps", PortType.INT, 64).setRange(16, 256);
        n.addInput("thickness", PortType.FLOAT, 0.1f).setRange(0.01f, 1.0f);
        return n;
    }

    /** TAA 時間抗鋸齒 — Temporal Anti-Aliasing */
    public static EffectToggleNode createTAA() {
        EffectToggleNode n = new EffectToggleNode("taa", "TAA 時間抗鋸齒");
        n.addInput("jitterScale", PortType.FLOAT, 1.0f).setRange(0.0f, 2.0f);
        n.addInput("feedbackMin", PortType.FLOAT, 0.88f).setRange(0.5f, 0.99f);
        n.addInput("feedbackMax", PortType.FLOAT, 0.97f).setRange(0.5f, 0.99f);
        n.addInput("sharpen", PortType.FLOAT, 0.1f).setRange(0.0f, 1.0f);
        return n;
    }

    /** Bloom 泛光 — HDR Bloom */
    public static EffectToggleNode createBloom() {
        EffectToggleNode n = new EffectToggleNode("bloom", "Bloom 泛光");
        n.addInput("threshold", PortType.FLOAT, 1.0f).setRange(0.0f, 5.0f);
        n.addInput("intensity", PortType.FLOAT, 0.5f).setRange(0.0f, 3.0f);
        n.addInput("radius", PortType.FLOAT, 4.0f).setRange(1.0f, 16.0f);
        n.addInput("passes", PortType.INT, 6).setRange(1, 12);
        return n;
    }

    /** Volumetric 體積光 — Volumetric Lighting / God Rays */
    public static EffectToggleNode createVolumetric() {
        EffectToggleNode n = new EffectToggleNode("volumetric", "Volumetric 體積光");
        n.addInput("samples", PortType.INT, 64).setRange(16, 256);
        n.addInput("density", PortType.FLOAT, 0.02f).setRange(0.001f, 0.1f);
        n.addInput("scattering", PortType.FLOAT, 0.7f).setRange(0.0f, 1.0f);
        n.addInput("maxDistance", PortType.FLOAT, 128.0f).setRange(16.0f, 512.0f);
        return n;
    }

    /** DOF 景深 — Depth of Field */
    public static EffectToggleNode createDOF() {
        EffectToggleNode n = new EffectToggleNode("dof", "DOF 景深");
        n.addInput("focalDistance", PortType.FLOAT, 10.0f).setRange(0.5f, 100.0f);
        n.addInput("focalLength", PortType.FLOAT, 50.0f).setRange(10.0f, 200.0f);
        n.addInput("aperture", PortType.FLOAT, 2.8f).setRange(1.0f, 22.0f);
        n.addInput("maxBlur", PortType.FLOAT, 4.0f).setRange(1.0f, 16.0f);
        return n;
    }

    /** Motion Blur 動態模糊 — Camera/Object Motion Blur */
    public static EffectToggleNode createMotionBlur() {
        EffectToggleNode n = new EffectToggleNode("motion_blur", "Motion Blur 動態模糊");
        n.addInput("intensity", PortType.FLOAT, 0.5f).setRange(0.0f, 2.0f);
        n.addInput("samples", PortType.INT, 8).setRange(2, 32);
        n.addInput("maxVelocity", PortType.FLOAT, 40.0f).setRange(5.0f, 100.0f);
        return n;
    }

    /** Contact Shadow 接觸陰影 — Screen-Space Contact Shadows */
    public static EffectToggleNode createContactShadow() {
        EffectToggleNode n = new EffectToggleNode("contact_shadow", "Contact Shadow 接觸陰影");
        n.addInput("rayLength", PortType.FLOAT, 0.15f).setRange(0.01f, 0.5f);
        n.addInput("raySteps", PortType.INT, 16).setRange(4, 64);
        n.addInput("fadeDistance", PortType.FLOAT, 20.0f).setRange(5.0f, 50.0f);
        return n;
    }

    /** SSGI 螢幕空間全局照明 — Screen-Space Global Illumination */
    public static EffectToggleNode createSSGI() {
        EffectToggleNode n = new EffectToggleNode("ssgi", "SSGI 螢幕空間全局照明");
        n.addInput("samples", PortType.INT, 16).setRange(4, 64);
        n.addInput("radius", PortType.FLOAT, 3.0f).setRange(0.5f, 10.0f);
        n.addInput("intensity", PortType.FLOAT, 1.0f).setRange(0.0f, 5.0f);
        n.addInput("bounces", PortType.INT, 1).setRange(1, 4);
        return n;
    }

    /** VCT 體素錐追蹤 — Voxel Cone Tracing */
    public static EffectToggleNode createVCT() {
        EffectToggleNode n = new EffectToggleNode("vct", "VCT 體素錐追蹤");
        n.addInput("resolution", PortType.INT, 128).setRange(32, 512);
        n.addInput("coneAngle", PortType.FLOAT, 0.5f).setRange(0.1f, 1.5f);
        n.addInput("traceDistance", PortType.FLOAT, 64.0f).setRange(8.0f, 256.0f);
        n.addInput("giIntensity", PortType.FLOAT, 1.0f).setRange(0.0f, 5.0f);
        return n;
    }

    /** SSS 次表面散射 — Subsurface Scattering */
    public static EffectToggleNode createSSS() {
        EffectToggleNode n = new EffectToggleNode("sss", "SSS 次表面散射");
        n.addInput("scatterRadius", PortType.FLOAT, 1.0f).setRange(0.1f, 5.0f);
        n.addInput("intensity", PortType.FLOAT, 1.0f).setRange(0.0f, 3.0f);
        n.addInput("samples", PortType.INT, 16).setRange(4, 64);
        return n;
    }

    /** Anisotropic 各向異性反射 — Anisotropic Reflections */
    public static EffectToggleNode createAnisotropic() {
        EffectToggleNode n = new EffectToggleNode("anisotropic", "Anisotropic 各向異性反射");
        n.addInput("anisotropy", PortType.FLOAT, 0.5f).setRange(0.0f, 1.0f);
        n.addInput("roughnessScale", PortType.FLOAT, 1.0f).setRange(0.1f, 2.0f);
        return n;
    }

    /** POM 視差遮蔽貼圖 — Parallax Occlusion Mapping */
    public static EffectToggleNode createPOM() {
        EffectToggleNode n = new EffectToggleNode("pom", "POM 視差遮蔽貼圖");
        n.addInput("heightScale", PortType.FLOAT, 0.05f).setRange(0.01f, 0.2f);
        n.addInput("layers", PortType.INT, 32).setRange(8, 128);
        n.addInput("refinementSteps", PortType.INT, 5).setRange(1, 16);
        return n;
    }

    // ═══════════════════════════════════════════════════════
    //  評估
    // ═══════════════════════════════════════════════════════

    @Override
    public void evaluate() {
        boolean enabled = getBool("enabled");
        setOutput("enabled", enabled);
        setOutput("effectName", effectName);

        // Forward effect-specific outputs when enabled
        if (enabled) {
            // SSAO: forward kernelSize as samples output
            if ("ssao".equals(effectName)) {
                NodePort kernelPort = getInput("kernelSize");
                if (kernelPort != null && kernelPort.getValue() != null) {
                    setOutput("samples", ((Number) kernelPort.getValue()).intValue());
                }
            }
        } else {
            // When disabled, zero out numeric outputs
            NodePort samplesOutput = getOutput("samples");
            if (samplesOutput != null) {
                setOutput("samples", 0);
            }
        }
    }

    /**
     * 取得此節點代表的效果名稱。
     *
     * @return effect identifier (e.g. "ssao", "ssr", "bloom")
     */
    public String getEffectName() {
        return effectName;
    }
}
